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
	defer node.Close()
	againIP, againSelf := checkNode(t, node, peerName, false)
	if againSelf != self {
		t.Fatalf("the node registered anew instead of reusing its registration: %s, then %s", self, againSelf)
	}
	if againIP != ip {
		t.Fatalf("peer address changed across the restart: %s, then %s", ip, againIP)
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
	}

	banner := sshBanner(t, node.ProxyPort(), peerName)
	t.Logf("banner from %s:22: %q", peerName, banner)
	return ip, self
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
