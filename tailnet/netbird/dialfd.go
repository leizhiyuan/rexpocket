//go:build netbird

package netbird

import (
	"context"
	"errors"
	"io"
	"net"
	"os"
	"strconv"
	"sync"
	"syscall"
	"time"
)

// DialFd dials host:port through the node and returns a file descriptor the caller owns: one end of
// a Unix socket pair whose other end is piped to the connection. The Netbird add-on hands it to
// RexPocket over Binder, so a dial needs no port on loopback. host may be a peer's name: it is
// resolved from the node's peers, as the proxy does. On failure the reason is also kept for
// [Node.DialError].
func (n *Node) DialFd(host string, port int32, timeoutMillis int32) (int32, error) {
	if n.closed.Load() {
		return -1, errors.New("netbird: node is closed")
	}
	timeout := time.Duration(timeoutMillis) * time.Millisecond
	if timeout <= 0 {
		timeout = 30 * time.Second
	}
	ctx, cancel := context.WithTimeout(context.Background(), timeout)
	defer cancel()
	conn, err := n.dial(ctx, "tcp", net.JoinHostPort(host, strconv.Itoa(int(port))))
	if err != nil {
		return -1, err
	}
	fd, err := pipeToFd(conn)
	if err != nil {
		conn.Close()
		return -1, err
	}
	return int32(fd), nil
}

// pipeToFd makes a Unix socket pair, pipes one end to conn in both directions until both sides are
// done, and returns the other end's descriptor. A half close is passed on: when the caller shuts
// down its writing side, conn's is shut down too, and the other way round.
func pipeToFd(conn net.Conn) (int, error) {
	fds, err := syscall.Socketpair(syscall.AF_UNIX, syscall.SOCK_STREAM|syscall.SOCK_CLOEXEC, 0)
	if err != nil {
		return -1, err
	}
	file := os.NewFile(uintptr(fds[0]), "netbird-dial")
	local, err := net.FileConn(file) // a duplicate of fds[0]
	file.Close()
	if err != nil {
		syscall.Close(fds[1])
		return -1, err
	}
	go pipe(local, conn)
	return fds[1], nil
}

// pipe copies a to b and b to a, passing each end of input on as a half close, then closes both.
func pipe(a, b net.Conn) {
	var wg sync.WaitGroup
	wg.Add(2)
	copyHalf := func(dst, src net.Conn) {
		defer wg.Done()
		io.Copy(dst, src)
		if cw, ok := dst.(interface{ CloseWrite() error }); ok {
			cw.CloseWrite()
		} else {
			dst.Close()
		}
	}
	go copyHalf(a, b)
	go copyHalf(b, a)
	wg.Wait()
	a.Close()
	b.Close()
}
