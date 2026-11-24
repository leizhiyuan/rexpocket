//go:build netbird

package netbird

import (
	"io"
	"net"
	"os"
	"testing"
)

// The descriptor DialFd returns carries both directions, and a half close, to the connection.
func TestPipeToFd(t *testing.T) {
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer ln.Close()
	served := make(chan string, 1)
	go func() {
		c, err := ln.Accept()
		if err != nil {
			served <- err.Error()
			return
		}
		defer c.Close()
		c.Write([]byte("SSH-2.0-test\r\n"))
		got, _ := io.ReadAll(c) // until the caller's half close
		served <- string(got)
	}()
	conn, err := net.Dial("tcp", ln.Addr().String())
	if err != nil {
		t.Fatal(err)
	}
	fd, err := pipeToFd(conn)
	if err != nil {
		t.Fatal(err)
	}
	caller := fdConn(t, fd)
	defer caller.Close()
	buf := make([]byte, 14)
	if _, err := io.ReadFull(caller, buf); err != nil || string(buf) != "SSH-2.0-test\r\n" {
		t.Fatalf("read %q, %v", buf, err)
	}
	caller.Write([]byte("hello"))
	caller.(*net.UnixConn).CloseWrite()
	if got := <-served; got != "hello" {
		t.Fatalf("the peer got %q", got)
	}
	// The peer closed: the caller reads the end.
	if rest, err := io.ReadAll(caller); err != nil || len(rest) != 0 {
		t.Fatalf("after the peer closed: %q, %v", rest, err)
	}
}

// fdConn is the connection on a descriptor DialFd returned, which the caller then owns.
func fdConn(t *testing.T, fd int) net.Conn {
	t.Helper()
	f := os.NewFile(uintptr(fd), "caller")
	c, err := net.FileConn(f)
	f.Close()
	if err != nil {
		t.Fatal(err)
	}
	return c
}
