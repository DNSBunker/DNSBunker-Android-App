package main

import (
	"errors"
	"net"
	"sync/atomic"
	"time"
)

const tcpIdle = 30 * time.Minute
const udpIdle = 2 * time.Minute

type activity struct{ last atomic.Int64 }

func newActivity() *activity { a := &activity{}; a.touch(); return a }
func (a *activity) touch()   { a.last.Store(time.Now().UnixNano()) }
func (a *activity) idle(limit time.Duration) bool {
	return time.Since(time.Unix(0, a.last.Load())) >= limit
}
func pump(dst, src net.Conn, a *activity, limit time.Duration, buffer []byte, writeTimeout time.Duration) error {
	for {
		src.SetReadDeadline(time.Now().Add(limit))
		n, err := src.Read(buffer)
		if n > 0 {
			a.touch()
			dst.SetWriteDeadline(time.Now().Add(writeTimeout))
			if _, werr := dst.Write(buffer[:n]); werr != nil {
				return werr
			}
		}
		if err != nil {
			var timeout net.Error
			if errors.As(err, &timeout) && timeout.Timeout() && !a.idle(limit) {
				continue
			}
			return err
		}
	}
}
