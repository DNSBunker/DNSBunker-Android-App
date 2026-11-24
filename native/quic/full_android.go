//go:build android

package main

/*
#include "bridge.h"
*/
import "C"
import (
	"context"
	"encoding/binary"
	"errors"
	"gvisor.dev/gvisor/pkg/buffer"
	"gvisor.dev/gvisor/pkg/tcpip"
	"gvisor.dev/gvisor/pkg/tcpip/adapters/gonet"
	"gvisor.dev/gvisor/pkg/tcpip/header"
	"gvisor.dev/gvisor/pkg/tcpip/link/channel"
	"gvisor.dev/gvisor/pkg/tcpip/network/ipv4"
	"gvisor.dev/gvisor/pkg/tcpip/network/ipv6"
	"gvisor.dev/gvisor/pkg/tcpip/stack"
	"gvisor.dev/gvisor/pkg/tcpip/transport/icmp"
	"gvisor.dev/gvisor/pkg/tcpip/transport/tcp"
	"gvisor.dev/gvisor/pkg/tcpip/transport/udp"
	"gvisor.dev/gvisor/pkg/waiter"
	"io"
	"net"
	"strconv"
	"sync"
	"syscall"
	"time"
	"unsafe"
)

type fullEngine struct {
	token       int64
	ctx         context.Context
	cancel      context.CancelFunc
	stack       *stack.Stack
	link        *channel.Endpoint
	slots       chan struct{}
	dnsSlots    chan struct{}
	mu          sync.Mutex
	connections map[net.Conn]struct{}
	closed      bool
}

var engines sync.Map

func (e *fullEngine) acquire(dns bool) bool {
	c := e.slots
	if dns {
		c = e.dnsSlots
	}
	select {
	case c <- struct{}{}:
		return true
	default:
		return false
	}
}
func (e *fullEngine) release(dns bool) {
	if dns {
		<-e.dnsSlots
	} else {
		<-e.slots
	}
}
func (e *fullEngine) track(c net.Conn) bool {
	e.mu.Lock()
	defer e.mu.Unlock()
	if e.closed {
		c.Close()
		return false
	}
	e.connections[c] = struct{}{}
	return true
}
func (e *fullEngine) untrack(c net.Conn) {
	c.Close()
	e.mu.Lock()
	delete(e.connections, c)
	e.mu.Unlock()
}
func (e *fullEngine) dial(network, address string) (net.Conn, error) {
	d := net.Dialer{Timeout: 6 * time.Second, KeepAlive: 30 * time.Second, Control: func(_, _ string, raw syscall.RawConn) error {
		var result error
		err := raw.Control(func(fd uintptr) {
			if C.bridge_protect(C.longlong(e.token), C.int(fd)) != 1 {
				result = errors.New("no protected underlay")
			}
		})
		if err != nil {
			return err
		}
		return result
	}}
	return d.DialContext(e.ctx, network, address)
}
func (e *fullEngine) dns(q []byte, udp bool, port uint16) ([]byte, error) {
	if len(q) < 12 || len(q) > 65535 {
		return nil, errors.New("DNS size")
	}
	out := make([]byte, 65535)
	flag := 0
	if udp {
		flag = 1
	}
	n := int(C.bridge_dns(C.longlong(e.token), unsafe.Pointer(&q[0]), C.int(len(q)), C.int(flag), C.int(port), unsafe.Pointer(&out[0])))
	if n < 12 || n > 65535 {
		return nil, errors.New("DNS unavailable")
	}
	return out[:n], nil
}
func hijack(id stack.TransportEndpointID) bool {
	if id.LocalPort != 53 {
		return false
	}
	ip := net.IP(id.LocalAddress.AsSlice())
	if ip.Equal(net.ParseIP("198.18.0.1")) || ip.Equal(net.ParseIP("fd77:db4b::1")) {
		return true
	}
	return !(ip.IsPrivate() || ip.IsLoopback() || ip.IsLinkLocalUnicast())
}
func destination(id stack.TransportEndpointID) string {
	return net.JoinHostPort(net.IP(id.LocalAddress.AsSlice()).String(), strconv.Itoa(int(id.LocalPort)))
}

func (e *fullEngine) serveTCPDNS(local net.Conn, port uint16) {
	for {
		local.SetReadDeadline(time.Now().Add(30 * time.Second))
		prefix := make([]byte, 2)
		if _, err := io.ReadFull(local, prefix); err != nil {
			return
		}
		n := int(binary.BigEndian.Uint16(prefix))
		if n < 12 {
			return
		}
		q := make([]byte, n)
		if _, err := io.ReadFull(local, q); err != nil {
			return
		}
		answer, err := e.dns(q, false, port)
		if err != nil {
			return
		}
		frame := make([]byte, len(answer)+2)
		binary.BigEndian.PutUint16(frame, uint16(len(answer)))
		copy(frame[2:], answer)
		local.SetWriteDeadline(time.Now().Add(8 * time.Second))
		if _, err := local.Write(frame); err != nil {
			return
		}
	}
}
func (e *fullEngine) tcp(r *tcp.ForwarderRequest) {
	id := r.ID()
	dns := hijack(id)
	if !e.acquire(dns) {
		r.Complete(true)
		return
	}
	go func() {
		defer e.release(dns)
		var remote net.Conn
		if !dns {
			if id.LocalPort == 53 && !net.IP(id.LocalAddress.AsSlice()).IsPrivate() {
				r.Complete(true)
				return
			}
			conn, err := e.dial("tcp", destination(id))
			if err != nil {
				r.Complete(true)
				return
			}
			if !e.track(conn) {
				r.Complete(true)
				return
			}
			defer e.untrack(conn)
			remote = conn
		}
		var queue waiter.Queue
		endpoint, endpointError := r.CreateEndpoint(&queue)
		if endpointError != nil {
			r.Complete(true)
			return
		}
		r.Complete(false)
		local := gonet.NewTCPConn(&queue, endpoint)
		if !e.track(local) {
			return
		}
		defer e.untrack(local)
		if dns {
			e.serveTCPDNS(local, id.RemotePort)
			return
		}
		a := newActivity()
		done := make(chan struct{}, 2)
		half := func(dst, src net.Conn) {
			err := pump(dst, src, a, tcpIdle, make([]byte, 32768), 30*time.Second)
			if errors.Is(err, io.EOF) {
				if c, ok := dst.(interface{ CloseWrite() error }); ok {
					c.CloseWrite()
				}
			} else {
				dst.Close()
				src.Close()
			}
			done <- struct{}{}
		}
		go half(remote, local)
		go half(local, remote)
		<-done
		<-done
	}()
}
func (e *fullEngine) udp(r *udp.ForwarderRequest) bool {
	id := r.ID()
	dns := hijack(id)
	if !e.acquire(dns) {
		return false
	}
	var queue waiter.Queue
	endpoint, endpointError := r.CreateEndpoint(&queue)
	if endpointError != nil {
		e.release(dns)
		return true
	}
	local := gonet.NewUDPConn(&queue, endpoint)
	go func() {
		defer e.release(dns)
		if !e.track(local) {
			return
		}
		defer e.untrack(local)
		if dns {
			q := make([]byte, 65535)
			for {
				local.SetReadDeadline(time.Now().Add(100 * time.Millisecond))
				n, err := local.Read(q)
				if err != nil {
					return
				}
				answer, err := e.dns(q[:n], true, id.RemotePort)
				if err != nil {
					continue
				}
				local.SetWriteDeadline(time.Now().Add(5 * time.Second))
				if _, err := local.Write(answer); err != nil {
					return
				}
			}
		}
		if id.LocalPort == 53 && !net.IP(id.LocalAddress.AsSlice()).IsPrivate() {
			return
		}
		remote, err := e.dial("udp", destination(id))
		if err != nil {
			return
		}
		if !e.track(remote) {
			return
		}
		defer e.untrack(remote)
		a := newActivity()
		done := make(chan struct{}, 2)
		relay := func(dst, src net.Conn) {
			pump(dst, src, a, udpIdle, make([]byte, 65535), 5*time.Second)
			local.Close()
			remote.Close()
			done <- struct{}{}
		}
		go relay(remote, local)
		go relay(local, remote)
		<-done
		<-done
	}()
	return true
}

//export dnsbunker_full_start
func dnsbunker_full_start(token C.longlong, mtu C.int) C.int {
	if mtu < 1280 || mtu > 1500 {
		return 0
	}
	ctx, cancel := context.WithCancel(context.Background())
	e := &fullEngine{token: int64(token), ctx: ctx, cancel: cancel, slots: make(chan struct{}, 512), dnsSlots: make(chan struct{}, 256), connections: make(map[net.Conn]struct{})}
	e.stack = stack.New(stack.Options{NetworkProtocols: []stack.NetworkProtocolFactory{ipv4.NewProtocol, ipv6.NewProtocol}, TransportProtocols: []stack.TransportProtocolFactory{tcp.NewProtocol, udp.NewProtocol, icmp.NewProtocol4, icmp.NewProtocol6}})
	e.link = channel.New(512, uint32(mtu), "")
	if err := e.stack.CreateNIC(1, e.link); err != nil {
		cancel()
		e.stack.Destroy()
		return 0
	}
	e.stack.SetPromiscuousMode(1, true)
	e.stack.SetSpoofing(1, true)
	sack := tcpip.TCPSACKEnabled(true)
	e.stack.SetTransportProtocolOption(tcp.ProtocolNumber, &sack)
	receive := tcpip.TCPReceiveBufferSizeRangeOption{Min: 4096, Default: 65536, Max: 524288}
	send := tcpip.TCPSendBufferSizeRangeOption{Min: 4096, Default: 65536, Max: 524288}
	e.stack.SetTransportProtocolOption(tcp.ProtocolNumber, &receive)
	e.stack.SetTransportProtocolOption(tcp.ProtocolNumber, &send)
	e.stack.SetRouteTable([]tcpip.Route{{Destination: header.IPv4EmptySubnet, NIC: 1}, {Destination: header.IPv6EmptySubnet, NIC: 1}})
	e.stack.SetTransportProtocolHandler(tcp.ProtocolNumber, tcp.NewForwarder(e.stack, 65536, 512, e.tcp).HandlePacket)
	e.stack.SetTransportProtocolHandler(udp.ProtocolNumber, udp.NewForwarder(e.stack, e.udp).HandlePacket)
	if _, exists := engines.LoadOrStore(int64(token), e); exists {
		cancel()
		e.stack.Destroy()
		return 0
	}
	go func() {
		for {
			packet := e.link.ReadContext(ctx)
			if packet == nil {
				return
			}
			view := packet.ToView()
			packet.DecRef()
			b := view.AsSlice()
			if len(b) > 0 && len(b) <= 65535 {
				C.bridge_emit(token, unsafe.Pointer(&b[0]), C.int(len(b)))
			}
			view.Release()
		}
	}()
	return 1
}

//export dnsbunker_full_input
func dnsbunker_full_input(token C.longlong, data unsafe.Pointer, n C.int) C.int {
	value, ok := engines.Load(int64(token))
	if !ok || n < 20 || n > 65535 {
		return 0
	}
	e := value.(*fullEngine)
	bytes := C.GoBytes(data, n)
	var protocol tcpip.NetworkProtocolNumber
	switch bytes[0] >> 4 {
	case 4:
		protocol = header.IPv4ProtocolNumber
		if bytes[9] == 1 {
			return 0
		}
	case 6:
		if len(bytes) < 40 {
			return 0
		}
		protocol = header.IPv6ProtocolNumber
		if bytes[6] == 58 && len(bytes) > 40 && bytes[40] >= 128 {
			return 0
		}
	default:
		return 0
	}
	packet := stack.NewPacketBuffer(stack.PacketBufferOptions{Payload: buffer.MakeWithData(bytes)})
	defer packet.DecRef()
	e.link.InjectInbound(protocol, packet)
	return 1
}

//export dnsbunker_full_stop
func dnsbunker_full_stop(token C.longlong) {
	value, ok := engines.LoadAndDelete(int64(token))
	if !ok {
		return
	}
	e := value.(*fullEngine)
	e.cancel()
	e.mu.Lock()
	e.closed = true
	for c := range e.connections {
		c.Close()
	}
	e.connections = make(map[net.Conn]struct{})
	e.mu.Unlock()
	e.stack.Close()
	e.link.Close()
	go e.stack.Wait()
}
