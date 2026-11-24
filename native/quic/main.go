package main

/*
#include "bridge.h"
*/
import "C"

import (
	"bytes"
	"context"
	"crypto/tls"
	"crypto/x509"
	"encoding/binary"
	"errors"
	"io"
	"mime"
	"net"
	"net/http"
	"os"
	"strings"
	"sync"
	"sync/atomic"
	"time"
	"unsafe"

	"dnsbunker.local/quicbridge/wire"
	"github.com/quic-go/quic-go"
	"github.com/quic-go/quic-go/http3"
)

type client struct {
	transport         *quic.Transport
	udp               net.PacketConn
	address           net.IP
	tls               *tls.Config
	doqMu             sync.Mutex
	doq               *quic.Conn
	h3                *http3.Transport
	ctx               context.Context
	cancel            context.CancelFunc
	handshakeEnd      [2]atomic.Int64
	handshakeDuration [2]atomic.Int64
}

var registry sync.Map
var next atomic.Int64

func main() {}

func config() *quic.Config {
	return &quic.Config{HandshakeIdleTimeout: 2 * time.Second, MaxIdleTimeout: 45 * time.Second, MaxIncomingStreams: -1, MaxIncomingUniStreams: 16, InitialPacketSize: 1200, DisablePathMTUDiscovery: true, InitialStreamReceiveWindow: 65536, MaxStreamReceiveWindow: 131072, InitialConnectionReceiveWindow: 524288, MaxConnectionReceiveWindow: 2097152}
}

//export dnsbunker_create
func dnsbunker_create(fd C.int, ipText *C.char, peerText *C.char, rootsText *C.char) C.longlong {
	file := os.NewFile(uintptr(fd), "protected-dns-udp")
	if file == nil {
		return 0
	}
	defer file.Close()
	ip := net.ParseIP(C.GoString(ipText))
	peer := C.GoString(peerText)
	if ip == nil || !(ip.Equal(net.ParseIP("185.250.250.61")) || ip.Equal(net.ParseIP("2a0a:51c1:a:ea::"))) {
		return 0
	}
	if peer != "dnsbunker.org" && peer != ip.String() {
		return 0
	}
	roots := x509.NewCertPool()
	if !roots.AppendCertsFromPEM([]byte(C.GoString(rootsText))) {
		return 0
	}
	udp, err := net.FilePacketConn(file)
	if err != nil {
		return 0
	}
	ctx, cancel := context.WithCancel(context.Background())
	c := &client{udp: udp, address: ip, ctx: ctx, cancel: cancel, tls: &tls.Config{RootCAs: roots, ServerName: peer, MinVersion: tls.VersionTLS13}}
	c.transport = &quic.Transport{Conn: udp}
	c.h3 = &http3.Transport{TLSClientConfig: c.tls.Clone(), QUICConfig: config(), DisableCompression: true, MaxResponseHeaderBytes: 16384,
		Dial: func(ctx context.Context, _ string, t *tls.Config, q *quic.Config) (*quic.Conn, error) {
			start := time.Now()
			conn, err := c.transport.Dial(ctx, &net.UDPAddr{IP: c.address, Port: 443}, t, q)
			if err == nil {
				c.handshakeDuration[1].Store(time.Since(start).Milliseconds())
				c.handshakeEnd[1].Store(time.Now().UnixNano())
			}
			return conn, err
		}}
	id := next.Add(1)
	registry.Store(id, c)
	return C.longlong(id)
}

func (c *client) connection(ctx context.Context) (*quic.Conn, error) {
	c.doqMu.Lock()
	defer c.doqMu.Unlock()
	if c.doq != nil && c.doq.Context().Err() == nil {
		return c.doq, nil
	}
	t := c.tls.Clone()
	t.NextProtos = []string{"doq"}
	start := time.Now()
	conn, err := c.transport.Dial(ctx, &net.UDPAddr{IP: c.address, Port: 853}, t, config())
	if err != nil {
		return nil, err
	}
	c.handshakeDuration[0].Store(time.Since(start).Milliseconds())
	c.handshakeEnd[0].Store(time.Now().UnixNano())
	c.doq = conn
	return conn, nil
}
func exchangeDoq(ctx context.Context, conn *quic.Conn, query []byte) ([]byte, error) {
	if len(query) < 12 || len(query) > 65535 {
		return nil, errors.New("size")
	}
	stream, err := conn.OpenStreamSync(ctx)
	if err != nil {
		return nil, err
	}
	defer stream.CancelRead(0x3)
	deadline, _ := ctx.Deadline()
	stream.SetDeadline(deadline)
	return wire.Exchange(stream, query)
}
func (c *client) query(ctx context.Context, kind int, q []byte) ([]byte, error) {
	if kind == 0 {
		conn, err := c.connection(ctx)
		if err != nil {
			return nil, err
		}
		return exchangeDoq(ctx, conn, q)
	}
	if kind != 1 {
		return nil, errors.New("protocol")
	}
	host := c.tls.ServerName
	if net.ParseIP(host) != nil && c.address.To4() == nil {
		host = "[" + host + "]"
	}
	request, err := http.NewRequestWithContext(ctx, http.MethodPost, "https://"+host+"/dns-query", bytes.NewReader(q))
	if err != nil {
		return nil, err
	}
	request.Header.Set("Content-Type", "application/dns-message")
	request.Header.Set("Accept", "application/dns-message")
	request.Header.Set("User-Agent", "DNSBunker")
	response, err := c.h3.RoundTrip(request)
	if err != nil {
		return nil, err
	}
	defer response.Body.Close()
	typ, _, err := mime.ParseMediaType(response.Header.Get("Content-Type"))
	if err != nil || typ != "application/dns-message" || response.StatusCode != 200 || response.ProtoMajor != 3 {
		return nil, errors.New("HTTP3 response")
	}
	b, err := io.ReadAll(io.LimitReader(response.Body, 65536))
	if err != nil {
		return nil, err
	}
	if len(b) < 12 || len(b) > 65535 {
		return nil, errors.New("DNS body limit")
	}
	return b, nil
}

//export dnsbunker_query
func dnsbunker_query(id C.longlong, kind C.int, data unsafe.Pointer, length C.int, output unsafe.Pointer, timeout C.int) C.int {
	value, ok := registry.Load(int64(id))
	if !ok || length < 12 || length > 65535 {
		return -1
	}
	if kind < 0 || kind > 1 {
		return -1
	}
	start := time.Now()
	c := value.(*client)
	duration := time.Duration(timeout) * time.Millisecond
	if duration < 100*time.Millisecond || duration > 8*time.Second {
		return -1
	}
	ctx, cancel := context.WithTimeout(c.ctx, duration)
	defer cancel()
	q := C.GoBytes(data, length)
	answer, err := c.query(ctx, int(kind), q)
	if err != nil {
		var cert *tls.CertificateVerificationError
		if errors.As(err, &cert) || strings.Contains(err.Error(), "certificate") {
			return -3
		}
		if errors.Is(err, context.DeadlineExceeded) {
			return -2
		}
		return -4
	}
	if len(answer) > 65535 {
		return -1
	}
	out := unsafe.Slice((*byte)(output), 65552)
	out[0] = 0
	setup := int64(0)
	if c.handshakeEnd[int(kind)].Load() >= start.UnixNano() {
		out[0] = 1
		setup = c.handshakeDuration[int(kind)].Load()
	}
	binary.BigEndian.PutUint64(out[1:9], uint64(setup))
	binary.BigEndian.PutUint64(out[9:17], uint64(time.Since(start).Milliseconds()))
	copy(out[17:], answer)
	return C.int(len(answer) + 17)
}

//export dnsbunker_close
func dnsbunker_close(id C.longlong) {
	value, ok := registry.LoadAndDelete(int64(id))
	if !ok {
		return
	}
	c := value.(*client)
	c.cancel()
	c.h3.Close()
	c.doqMu.Lock()
	if c.doq != nil {
		c.doq.CloseWithError(0, "")
	}
	c.doqMu.Unlock()
	c.transport.Close()
	c.udp.Close()
}
