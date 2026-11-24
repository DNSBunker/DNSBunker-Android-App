package wire

import (
	"encoding/binary"
	"errors"
	"io"
)

func Exchange(stream io.ReadWriteCloser, query []byte) ([]byte, error) {
	if len(query) < 12 || len(query) > 65535 {
		return nil, errors.New("query size")
	}
	frame := make([]byte, len(query)+2)
	binary.BigEndian.PutUint16(frame, uint16(len(query)))
	copy(frame[2:], query)
	frame[2] = 0
	frame[3] = 0
	n, err := stream.Write(frame)
	if err != nil {
		return nil, err
	}
	if n != len(frame) {
		return nil, io.ErrShortWrite
	}
	if err = stream.Close(); err != nil {
		return nil, err
	}
	prefix := make([]byte, 2)
	if _, err = io.ReadFull(stream, prefix); err != nil {
		return nil, err
	}
	n = int(binary.BigEndian.Uint16(prefix))
	if n < 12 {
		return nil, errors.New("short answer")
	}
	answer := make([]byte, n)
	if _, err = io.ReadFull(stream, answer); err != nil {
		return nil, err
	}
	extra, err := io.ReadAll(io.LimitReader(stream, 1))
	if err != nil {
		return nil, err
	}
	if len(extra) != 0 {
		return nil, errors.New("trailing answer")
	}
	if answer[0] != 0 || answer[1] != 0 {
		return nil, errors.New("nonzero DoQ ID")
	}
	answer[0] = query[0]
	answer[1] = query[1]
	return answer, nil
}
