package main

import (
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/tls"
	"crypto/x509"
	"errors"
	"io"
	"math/big"
	"net"
	"testing"
	"time"
)

func TestPumpKeepsQuietDirectionWhileOtherIsActive(t *testing.T) {
	const limit = 150 * time.Millisecond
	clientA, quietSrc := net.Pipe()
	quietDst, sinkA := net.Pipe()
	busySrc, clientB := net.Pipe()
	busyDst, sinkB := net.Pipe()
	defer func() {
		for _, c := range []net.Conn{clientA, quietSrc, quietDst, sinkA, busySrc, clientB, busyDst, sinkB} {
			c.Close()
		}
	}()
	go io.Copy(io.Discard, sinkA)
	go io.Copy(io.Discard, sinkB)
	a := newActivity()
	quiet := make(chan error, 1)
	busy := make(chan error, 1)
	go func() { quiet <- pump(quietDst, quietSrc, a, limit, make([]byte, 64), time.Second) }()
	go func() { busy <- pump(busyDst, busySrc, a, limit, make([]byte, 64), time.Second) }()
	stop := time.Now().Add(4 * limit)
	for time.Now().Before(stop) {
		if _, err := clientB.Write([]byte("x")); err != nil {
			t.Fatal(err)
		}
		time.Sleep(limit / 3)
	}
	select {
	case err := <-quiet:
		t.Fatalf("quiet direction closed while the flow was active: %v", err)
	default:
	}
	for _, ch := range []chan error{quiet, busy} {
		select {
		case err := <-ch:
			var timeout net.Error
			if !errors.As(err, &timeout) || !timeout.Timeout() {
				t.Fatalf("expected idle timeout, got %v", err)
			}
		case <-time.After(4 * limit):
			t.Fatal("idle flow was not closed")
		}
	}
}

func TestPumpReportsEndOfStream(t *testing.T) {
	src, client := net.Pipe()
	dst, sink := net.Pipe()
	defer src.Close()
	defer dst.Close()
	defer sink.Close()
	got := make(chan []byte, 1)
	go func() { b, _ := io.ReadAll(sink); got <- b }()
	done := make(chan error, 1)
	go func() { done <- pump(dst, src, newActivity(), time.Second, make([]byte, 8), time.Second) }()
	client.Write([]byte("payload"))
	client.Close()
	if err := <-done; !errors.Is(err, io.EOF) {
		t.Fatalf("expected EOF, got %v", err)
	}
	dst.Close()
	if string(<-got) != "payload" {
		t.Fatal("payload not relayed")
	}
}

func TestPublicAddress(t *testing.T) {
	for _, ok := range []string{"185.250.250.61", "2a0a:51c1:a:ea::"} {
		if !publicAddress(net.ParseIP(ok)) {
			t.Fatalf("%s should be accepted", ok)
		}
	}
	for _, bad := range []string{"10.0.0.1", "192.168.1.1", "127.0.0.1", "100.64.0.1", "198.18.0.1", "fd77:db4b::1", "fe80::1", "64:ff9b::b9fa:fa3d", "2001:db8::1"} {
		if publicAddress(net.ParseIP(bad)) {
			t.Fatalf("%s should be rejected", bad)
		}
	}
	if publicAddress(nil) {
		t.Fatal("nil accepted")
	}
}

func testCertificate(t *testing.T, names []string, ips []net.IP) *x509.Certificate {
	key, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	template := &x509.Certificate{SerialNumber: big.NewInt(1), DNSNames: names, IPAddresses: ips,
		NotBefore: time.Now().Add(-time.Hour), NotAfter: time.Now().Add(time.Hour)}
	der, err := x509.CreateCertificate(rand.Reader, template, template, &key.PublicKey, key)
	if err != nil {
		t.Fatal(err)
	}
	cert, err := x509.ParseCertificate(der)
	if err != nil {
		t.Fatal(err)
	}
	return cert
}

func TestVerifyServiceRequiresServiceName(t *testing.T) {
	attacker := testCertificate(t, nil, []net.IP{net.ParseIP("203.0.113.7")})
	if verifyService(tls.ConnectionState{PeerCertificates: []*x509.Certificate{attacker}}) == nil {
		t.Fatal("IP-only certificate accepted")
	}
	genuine := testCertificate(t, []string{"dnsbunker.org"}, []net.IP{net.ParseIP("185.250.250.61")})
	if err := verifyService(tls.ConnectionState{PeerCertificates: []*x509.Certificate{genuine}}); err != nil {
		t.Fatal(err)
	}
	if verifyService(tls.ConnectionState{}) == nil {
		t.Fatal("missing certificate accepted")
	}
}
