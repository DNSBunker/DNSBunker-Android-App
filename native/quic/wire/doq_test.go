package wire

import (
	"bytes"
	"encoding/binary"
	"io"
	"testing"
)

type stream struct {
	in     *bytes.Reader
	out    bytes.Buffer
	closed bool
	short  bool
}

func (s *stream) Write(b []byte) (int, error) {
	if s.short {
		return len(b) - 1, nil
	}
	return s.out.Write(b)
}
func (s *stream) Read(b []byte) (int, error) {
	if !s.closed {
		return 0, io.ErrClosedPipe
	}
	return s.in.Read(b)
}
func (s *stream) Close() error { s.closed = true; return nil }
func framed(b []byte) []byte {
	out := make([]byte, len(b)+2)
	binary.BigEndian.PutUint16(out, uint16(len(b)))
	copy(out[2:], b)
	return out
}
func TestZeroIDFINAndRestoredTransaction(t *testing.T) {
	q := make([]byte, 17)
	q[0] = 0x42
	q[1] = 0x11
	answer := make([]byte, 17)
	answer[2] = 0x81
	s := &stream{in: bytes.NewReader(framed(answer))}
	got, err := Exchange(s, q)
	if err != nil {
		t.Fatal(err)
	}
	if !s.closed || s.out.Bytes()[2] != 0 || s.out.Bytes()[3] != 0 || got[0] != q[0] || got[1] != q[1] {
		t.Fatal("framing or IDs")
	}
	if q[0] != 0x42 {
		t.Fatal("caller query modified")
	}
}
func TestMalformedResponsesAndPartialWrites(t *testing.T) {
	q := make([]byte, 17)
	for _, data := range [][]byte{{0}, {0, 11}, framed(make([]byte, 10)), append(framed(make([]byte, 17)), 1), framed(append([]byte{1}, make([]byte, 16)...))} {
		if _, err := Exchange(&stream{in: bytes.NewReader(data)}, q); err == nil {
			t.Fatal("accepted malformed answer")
		}
	}
	if _, err := Exchange(&stream{in: bytes.NewReader(framed(q)), short: true}, q); err != io.ErrShortWrite {
		t.Fatal(err)
	}
}
func TestInputBounds(t *testing.T) {
	for _, n := range []int{0, 11, 65536} {
		if _, err := Exchange(&stream{}, make([]byte, n)); err == nil {
			t.Fatal(n)
		}
	}
}
