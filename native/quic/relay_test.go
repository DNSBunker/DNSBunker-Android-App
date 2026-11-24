package main

import (
	"errors"
	"io"
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
