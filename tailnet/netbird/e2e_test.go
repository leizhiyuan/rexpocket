//go:build netbird && e2e

// End-to-end test of the Netbird node against a real, self-hosted Netbird control plane and peer:
// tools/ci/netbird-e2e/up.sh brings them up and prints the environment this test reads.
//
//	NETBIRD_E2E_MANAGEMENT_URL  the management server, e.g. http://netbird.e2e:8080
//	NETBIRD_E2E_SETUP_KEY       a reusable setup key of its account
//	NETBIRD_E2E_PEER            the FQDN of a peer of that account with an SSH server on port 22
//	NETBIRD_E2E_PEER_IP         optional: the peer's address, checked against what the node lists
//
// Run with: go test -tags 'netbird e2e' -run E2E -v ./netbird/
package netbird

import (
	"bufio"
	"context"
	"encoding/json"
	"fmt"
	"net"
	"os"
	"strings"
	"sync"
	"testing"
	"time"

	"golang.org/x/net/proxy"
)

// e2ePlatform keeps the node's log, to show it when the test fails.
type e2ePlatform struct {
	mu    sync.Mutex
	lines []string
}

func (p *e2ePlatform) Log(line string) {
	p.mu.Lock()
	p.lines = append(p.lines, line)
	p.mu.Unlock()
}

func (p *e2ePlatform) StateChanged() {}

func (p *e2ePlatform) dump(t *testing.T) {
	p.mu.Lock()
	defer p.mu.Unlock()
	t.Logf("node log (%d lines):\n%s", len(p.lines), strings.Join(p.lines, "\n"))
}

func TestE2E(t *testing.T) {
	mgmt := os.Getenv("NETBIRD_E2E_MANAGEMENT_URL")
	key := os.Getenv("NETBIRD_E2E_SETUP_KEY")
	peerName := os.Getenv("NETBIRD_E2E_PEER")
	if mgmt == "" || key == "" || peerName == "" {
		if os.Getenv("NETBIRD_E2E_REQUIRED") != "" {
			t.Fatal("NETBIRD_E2E_REQUIRED is set but the environment from up.sh is missing")
		}
		t.Skip("set NETBIRD_E2E_MANAGEMENT_URL, NETBIRD_E2E_SETUP_KEY and NETBIRD_E2E_PEER (tools/ci/netbird-e2e/up.sh)")
	}
	dir := t.TempDir()
	platform := &e2ePlatform{}
	t.Cleanup(func() {
		if t.Failed() {
			platform.dump(t)
		}
	})

	t.Log("first start: joining with the setup key")
	node, err := Start(dir, "rexpocket-e2e", key, mgmt, platform)
	if err != nil {
		t.Fatalf("Start with a setup key: %v", err)
	}
	ip, self := checkNode(t, node, peerName, true)
	node.Close()
	if _, err := node.State(); err == nil {
		t.Fatal("State after Close: want an error")
	}

	t.Log("second start: rejoining from the saved registration, no setup key")
	node, err = Start(dir, "rexpocket-e2e", "", mgmt, platform)
	if err != nil {
		t.Fatalf("Start without a setup key: %v", err)
	}
	againIP, againSelf := checkNode(t, node, peerName, false)
	if againSelf != self {
		t.Fatalf("the node registered anew instead of reusing its registration: %s, then %s", self, againSelf)
	}
	if againIP != ip {
		t.Fatalf("peer address changed across the restart: %s, then %s", ip, againIP)
	}
	node.Close()

	t.Log("third start: the add-on's way, no proxy, dialed through descriptors")
	node, err = StartPrivate(dir, "rexpocket-e2e", "", mgmt, platform)
	if err != nil {
		t.Fatalf("StartPrivate: %v", err)
	}
	defer node.Close()
	if port := node.ProxyPort(); port != 0 {
		t.Fatalf("StartPrivate opened a proxy on port %d", port)
	}
	privateIP, _ := waitForPeer(t, node, peerName, 90*time.Second)
	checkDialFd(t, node, peerName, privateIP)
}

// checkDialFd reads the peer's SSH banner by name through [Node.DialFd], as the add-on dials for
// RexPocket, and expects a port nothing listens on to fail with a reason.
func checkDialFd(t *testing.T, node *Node, peerName, ip string) {
	t.Helper()
	var lastErr error
	for attempt := 0; attempt < 10; attempt++ {
		fd, err := node.DialFd(peerName, 22, 15_000)
		if err == nil {
			conn := fdConn(t, int(fd))
			conn.SetDeadline(time.Now().Add(15 * time.Second))
			line, err := bufio.NewReader(conn).ReadString('\n')
			conn.Close()
			if err == nil {
				if !strings.HasPrefix(line, "SSH-2.0") {
					t.Fatalf("banner through DialFd: %q, want SSH-2.0...", line)
				}
				t.Logf("banner from %s:22 through DialFd: %q", peerName, strings.TrimSpace(line))
				break
			}
		}
		lastErr = err
		t.Logf("DialFd %s:22 (attempt %d): %v", peerName, attempt+1, err)
		time.Sleep(3 * time.Second)
		if attempt == 9 {
			t.Fatalf("could not read the SSH banner of %s through DialFd: %v", peerName, lastErr)
		}
	}
	if fd, err := node.DialFd(peerName, 7297, 30_000); err == nil {
		fdConn(t, int(fd)).Close()
		t.Fatalf("%s:7297 answered through DialFd; the test wants a port nothing listens on", peerName)
	} else {
		t.Logf("DialFd %s:7297: %v", peerName, err)
	}
	if node.DialError(ip) == "" && node.DialError(peerName) == "" {
		t.Fatalf("a failed DialFd to %s:7297 left no reason", peerName)
	}
}

// checkNode waits for the node to run and list the peer, checks the path to it when ping is set,
// and reads the SSH banner of the peer by name through the node's proxy. It returns the peer's IP
// and the node's own name.
func checkNode(t *testing.T, node *Node, peerName string, ping bool) (string, string) {
	t.Helper()
	ip, self := waitForPeer(t, node, peerName, 90*time.Second)
	t.Logf("node %s is running; peer %s is at %s", self, peerName, ip)
	if want := os.Getenv("NETBIRD_E2E_PEER_IP"); want != "" && ip != want {
		t.Fatalf("node lists %s at %s, want %s", peerName, ip, want)
	}

	if ping {
		out, err := node.Ping(ip, 60_000)
		if err != nil {
			t.Fatalf("Ping %s: %v", ip, err)
		}
		var res struct {
			LatencyMs   float64 `json:"latencyMs"`
			Endpoint    string  `json:"endpoint"`
			RelayServer string  `json:"relayServer"`
			Error       string  `json:"error"`
		}
		if err := json.Unmarshal([]byte(out), &res); err != nil {
			t.Fatalf("Ping result %q: %v", out, err)
		}
		if res.Error != "" || (res.Endpoint == "" && res.RelayServer == "") {
			t.Fatalf("Ping %s: no path: %s", ip, out)
		}
		t.Logf("path to %s: %s", ip, out)
		checkLink(t, node, peerName)
	}

	banner := sshBanner(t, node.ProxyPort(), peerName)
	t.Logf("banner from %s:22: %q", peerName, banner)
	checkDialError(t, node, peerName, ip)
	return ip, self
}

// checkLink reads the state once the peer is connected: the node's own addresses, and a link to the
// peer the home screen can show - direct or through a relay.
func checkLink(t *testing.T, node *Node, peerName string) {
	t.Helper()
	raw, err := node.State()
	if err != nil {
		t.Fatalf("State: %v", err)
	}
	var st state
	if err := json.Unmarshal([]byte(raw), &st); err != nil {
		t.Fatalf("State %q: %v", raw, err)
	}
	if st.SelfInfo == nil || len(st.SelfInfo.IPs) == 0 || strings.Contains(st.SelfInfo.IPs[0], "/") {
		t.Fatalf("state has no usable self: %s", raw)
	}
	if st.Tailnet == "" {
		t.Fatalf("state names no network: %s", raw)
	}
	want := strings.ToLower(strings.TrimSuffix(peerName, "."))
	for _, p := range st.Peers {
		if strings.ToLower(p.DNSName) != want {
			continue
		}
		if p.Link != "direct" && p.Link != "relay" {
			t.Fatalf("peer %s is connected after a ping, but its link is %q: %s", peerName, p.Link, raw)
		}
		t.Logf("link to %s: %s %s", peerName, p.Link, p.LinkDetail)
		return
	}
	t.Fatalf("state does not list %s: %s", peerName, raw)
}

// checkDialError dials a port nothing listens on and expects the node to say why it failed, which
// a SOCKS5 reply cannot: the app shows that reason when Rex is not reached.
func checkDialError(t *testing.T, node *Node, peerName, ip string) {
	t.Helper()
	dialer, err := proxy.SOCKS5("tcp", fmt.Sprintf("127.0.0.1:%d", node.ProxyPort()), nil, &net.Dialer{Timeout: 10 * time.Second})
	if err != nil {
		t.Fatal(err)
	}
	ctx, cancel := context.WithTimeout(context.Background(), 60*time.Second)
	defer cancel()
	if conn, err := dialer.(proxy.ContextDialer).DialContext(ctx, "tcp", net.JoinHostPort(peerName, "7297")); err == nil {
		conn.Close()
		t.Fatalf("%s:7297 answered; the test wants a port nothing listens on", peerName)
	}
	// The node's own dial may end a moment after the proxy's reply.
	deadline := time.Now().Add(30 * time.Second)
	for {
		reason := node.DialError(ip)
		if reason == "" {
			reason = node.DialError(peerName)
		}
		if reason != "" {
			t.Logf("why %s:7297 failed: %s", peerName, reason)
			return
		}
		if time.Now().After(deadline) {
			t.Fatalf("a failed dial to %s:7297 left no reason", peerName)
		}
		time.Sleep(500 * time.Millisecond)
	}
}

// waitForPeer polls State until the node is Running and lists peerName, then returns the peer's IP
// and the node's own name.
func waitForPeer(t *testing.T, node *Node, peerName string, timeout time.Duration) (string, string) {
	t.Helper()
	want := strings.ToLower(strings.TrimSuffix(peerName, "."))
	deadline := time.Now().Add(timeout)
	var last string
	for {
		raw, err := node.State()
		if err != nil {
			t.Fatalf("State: %v", err)
		}
		last = raw
		var st state
		if err := json.Unmarshal([]byte(raw), &st); err != nil {
			t.Fatalf("State %q: %v", raw, err)
		}
		if st.BackendState == "Running" {
			for _, p := range st.Peers {
				if strings.ToLower(p.DNSName) == want && len(p.IPs) > 0 {
					return p.IPs[0], st.Self
				}
			}
		}
		if time.Now().After(deadline) {
			t.Fatalf("node did not run and list %s within %s; last state: %s", peerName, timeout, last)
		}
		time.Sleep(time.Second)
	}
}

// sshBanner connects to host:22 by name through the SOCKS5 proxy on port and returns the first
// line the server sends. The name is resolved by the proxy (names.go), not here.
func sshBanner(t *testing.T, port int32, host string) string {
	t.Helper()
	if port == 0 {
		t.Fatal("node has no proxy port")
	}
	dialer, err := proxy.SOCKS5("tcp", fmt.Sprintf("127.0.0.1:%d", port), nil, &net.Dialer{Timeout: 10 * time.Second})
	if err != nil {
		t.Fatal(err)
	}
	var lastErr error
	for attempt := 0; attempt < 10; attempt++ {
		line, err := readBanner(dialer, net.JoinHostPort(host, "22"))
		if err == nil {
			if !strings.HasPrefix(line, "SSH-2.0") {
				t.Fatalf("banner from %s: %q, want SSH-2.0...", host, line)
			}
			return line
		}
		lastErr = err
		t.Logf("dial %s:22 through the proxy (attempt %d): %v", host, attempt+1, err)
		time.Sleep(3 * time.Second)
	}
	t.Fatalf("could not read the SSH banner of %s: %v", host, lastErr)
	return ""
}

func readBanner(dialer proxy.Dialer, address string) (string, error) {
	conn, err := dialer.Dial("tcp", address)
	if err != nil {
		return "", err
	}
	defer conn.Close()
	conn.SetDeadline(time.Now().Add(15 * time.Second))
	line, err := bufio.NewReader(conn).ReadString('\n')
	if err != nil {
		return "", err
	}
	return strings.TrimRight(line, "\r\n"), nil
}
