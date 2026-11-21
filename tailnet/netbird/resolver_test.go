//go:build netbird

package netbird

import (
	"context"
	"errors"
	"net"
	"testing"

	socks5 "github.com/things-go/go-socks5"
	"golang.org/x/net/proxy"
)

type failResolver struct{ asked *[]string }

func (f failResolver) Resolve(ctx context.Context, name string) (context.Context, net.IP, error) {
	*f.asked = append(*f.asked, name)
	return ctx, nil, errors.New("no such host")
}

// The SOCKS5 server resolves a name before it dials. A peer's name must resolve to the peer's
// address there, not go to the host's DNS, which does not know it.
func TestTheProxyDialsAPeerByName(t *testing.T) {
	peers := func() []peerName { return []peerName{{ip: "100.102.0.7", fqdn: "peer.netbird.selfhosted."}} }
	var asked []string
	dialed := make(chan string, 1)
	server := socks5.NewServer(
		socks5.WithResolver(peerResolver{peers: peers, next: failResolver{&asked}}),
		socks5.WithDial(func(ctx context.Context, network, address string) (net.Conn, error) {
			dialed <- address
			return nil, errors.New("not dialing in a test")
		}),
	)
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer ln.Close()
	go server.Serve(ln)

	client, err := proxy.SOCKS5("tcp", ln.Addr().String(), nil, proxy.Direct)
	if err != nil {
		t.Fatal(err)
	}
	if conn, err := client.Dial("tcp", "peer.netbird.selfhosted:22"); err == nil {
		conn.Close()
	}
	select {
	case got := <-dialed:
		if got != "100.102.0.7:22" {
			t.Fatalf("dialed %q, want 100.102.0.7:22", got)
		}
	default:
		t.Fatal("the proxy never dialed: the name did not resolve")
	}

	if _, ip, err := (peerResolver{peers: peers, next: failResolver{&asked}}).Resolve(context.Background(), "example.com"); err == nil || ip != nil {
		t.Fatalf("a name no peer has: got %v, %v; want the next resolver's error", ip, err)
	}
	if len(asked) != 1 || asked[0] != "example.com" {
		t.Fatalf("next resolver was asked %v; want only example.com", asked)
	}
}
