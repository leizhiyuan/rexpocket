//go:build netbird

// The Netbird node itself: the build with the netbird tag. See platform.go for the package.
package netbird

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"net"
	"net/netip"
	"os"
	"path/filepath"
	"strings"
	"sync/atomic"
	"time"

	"github.com/netbirdio/netbird/client/embed"
	socks5 "github.com/things-go/go-socks5"
)

// Available reports whether this build has the Netbird node.
func Available() bool { return true }

// startTimeout bounds how long Start may wait for the engine: a management server that does not
// answer must not hold the app's connect forever.
const startTimeout = 90 * time.Second

// pingPoll is how often Ping looks again for a peer whose connection is still coming up.
const pingPoll = 200 * time.Millisecond

// stateInterval is how often the platform is told to read the node's state again. The embedded
// client has no IPN bus to subscribe to, so its peers are polled.
const stateInterval = 5 * time.Second

// Node is one running Netbird node and its local SOCKS5 proxy.
type Node struct {
	platform Platform
	client   *embed.Client
	proxy    net.Listener
	cancel   context.CancelFunc
	closed   atomic.Bool
}

// savedConfig is the part of the node's config file the app reads back: the WireGuard private key
// the node registered with.
type savedConfig struct {
	PrivateKey string
}

// Start brings the node up with its state in dir - the registration lives there, so it survives a
// restart - and opens the proxy. setupKey is needed the first time a node joins; after that it may
// be empty. managementURL may be empty for Netbird Cloud.
func Start(dir, hostname, setupKey, managementURL string, platform Platform) (*Node, error) {
	if platform == nil {
		return nil, errors.New("netbird: no platform")
	}
	if err := useAppDirs(dir); err != nil {
		return nil, err
	}
	opts := embed.Options{
		DeviceName:    hostname,
		SetupKey:      setupKey,
		ManagementURL: managementURL,
		ConfigPath:    filepath.Join(dir, "config.json"),
		StatePath:     filepath.Join(dir, "state.json"),
		LogOutput:     logWriter{platform},
		LogLevel:      "info",
	}
	if setupKey == "" {
		// A node that already joined has no use for a setup key, but the embedded client wants one
		// credential to start: the WireGuard key it registered with stands in for it.
		saved, err := readPrivateKey(opts.ConfigPath)
		if err != nil {
			return nil, err
		}
		if saved == "" {
			return nil, errors.New("netbird: a setup key is needed to join the network")
		}
		opts.PrivateKey = saved
	}
	client, err := embed.New(opts)
	if err != nil {
		return nil, err
	}
	return startClient(client, platform)
}

// startClient starts the embedded client, then opens the SOCKS5 proxy that dials through it.
func startClient(client *embed.Client, platform Platform) (*Node, error) {
	ctx, cancel := context.WithTimeout(context.Background(), startTimeout)
	defer cancel()
	if err := client.Start(ctx); err != nil {
		return nil, fmt.Errorf("netbird: %w", err)
	}
	n := &Node{platform: platform, client: client}

	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		n.Close()
		return nil, err
	}
	n.proxy = ln
	server := socks5.NewServer(
		socks5.WithDial(n.dial),
		socks5.WithLogger(socksLogger{platform}),
	)
	go server.Serve(ln)

	watchCtx, cancelWatch := context.WithCancel(context.Background())
	n.cancel = cancelWatch
	go n.watch(watchCtx)
	return n, nil
}

// readPrivateKey returns the WireGuard key a saved config records, or "" when there is none yet.
func readPrivateKey(configPath string) (string, error) {
	b, err := os.ReadFile(configPath)
	if errors.Is(err, os.ErrNotExist) {
		return "", nil
	}
	if err != nil {
		return "", err
	}
	var saved savedConfig
	if err := json.Unmarshal(b, &saved); err != nil {
		return "", fmt.Errorf("netbird: read %s: %w", configPath, err)
	}
	return saved.PrivateKey, nil
}

// useAppDirs gives the node somewhere to write besides its state files. An Android app process has
// no $HOME or $TMPDIR, so everything goes under the app's private state directory.
func useAppDirs(dir string) error {
	if err := os.MkdirAll(dir, 0o700); err != nil {
		return fmt.Errorf("netbird: %w", err)
	}
	for env, sub := range map[string]string{"TMPDIR": "tmp", "HOME": "home"} {
		if os.Getenv(env) != "" {
			continue
		}
		path := filepath.Join(dir, sub)
		if err := os.MkdirAll(path, 0o700); err != nil {
			return fmt.Errorf("netbird: %s: %w", env, err)
		}
		os.Setenv(env, path)
	}
	return nil
}

// ProxyPort is the loopback port of the SOCKS5 proxy into the network.
func (n *Node) ProxyPort() int32 {
	if n.proxy == nil {
		return 0
	}
	return int32(n.proxy.Addr().(*net.TCPAddr).Port)
}

// State is the node's state as JSON, in the shape the tailnet package reports:
// {"backendState", "authUrl", "tailnet", "self", "peers": [{"dnsName", "hostName", "ips": [...]}]}.
// The node is "Running" once its management and signal connections are up, and "Starting" until
// then; the embedded client does not report an auth URL.
func (n *Node) State() (string, error) {
	if n.closed.Load() {
		return "", errors.New("netbird: node is closed")
	}
	st, err := n.client.Status()
	if err != nil {
		return "", err
	}
	out := state{BackendState: "Starting"}
	if st.ManagementState.Connected && st.SignalState.Connected {
		out.BackendState = "Running"
	}
	if st.LocalPeerState.FQDN != "" {
		out.Self = strings.TrimSuffix(st.LocalPeerState.FQDN, ".")
	}
	for _, p := range st.Peers {
		name := strings.TrimSuffix(p.FQDN, ".")
		if name == "" {
			continue
		}
		var ips []string
		if p.IP != "" {
			ips = []string{p.IP}
		}
		out.Peers = append(out.Peers, peer{
			DNSName:  name,
			HostName: strings.SplitN(name, ".", 2)[0],
			IPs:      ips,
		})
	}
	b, err := json.Marshal(out)
	return string(b), err
}

// Ping reports the path to the peer at ip as JSON, in the shape the tailnet package reports:
// {"latencyMs", "endpoint", "peerRelay", "derpRegionId", "derpRegionCode", "relayServer", "error"}.
// Netbird keeps the latency of its own keepalives, so nothing is sent: it waits up to timeoutMillis
// for the peer's connection to come up, then reports the path it settled on. A direct path names
// its endpoint; a relayed one names the relay server, which is never a peer of the network.
func (n *Node) Ping(ip string, timeoutMillis int32) (string, error) {
	addr, err := netip.ParseAddr(ip)
	if err != nil {
		return "", err
	}
	deadline := time.Now().Add(time.Duration(timeoutMillis) * time.Millisecond)
	for {
		res := n.pathTo(addr)
		if res.Error == "" || time.Now().After(deadline) {
			b, err := json.Marshal(res)
			return string(b), err
		}
		time.Sleep(pingPoll)
	}
}

// pathTo reads the path to the peer at addr from the client's status once.
func (n *Node) pathTo(addr netip.Addr) ping {
	st, err := n.client.Status()
	if err != nil {
		return ping{Error: err.Error()}
	}
	for _, p := range st.Peers {
		if p.IP != addr.String() {
			continue
		}
		if p.ConnStatus != embed.PeerStatusConnected {
			return ping{Error: "the peer is not connected yet"}
		}
		res := ping{LatencyMs: float64(p.Latency) / float64(time.Millisecond)}
		if p.Relayed {
			res.RelayServer = p.RelayServerAddress
		} else {
			res.Endpoint = p.RemoteIceCandidateEndpoint
		}
		return res
	}
	return ping{Error: "not a peer of this network"}
}

type ping struct {
	LatencyMs      float64 `json:"latencyMs"`
	Endpoint       string  `json:"endpoint"`
	PeerRelay      string  `json:"peerRelay"`
	DERPRegionID   int64   `json:"derpRegionId"`
	DERPRegionCode string  `json:"derpRegionCode"`
	RelayServer    string  `json:"relayServer"`
	Error          string  `json:"error"`
}

// Close stops the proxy and the node.
func (n *Node) Close() {
	if n.closed.Swap(true) {
		return
	}
	if n.cancel != nil {
		n.cancel()
	}
	if n.proxy != nil {
		n.proxy.Close()
	}
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	n.client.Stop(ctx)
}

// watch tells the platform, at a steady pace, to read the state again.
func (n *Node) watch(ctx context.Context) {
	ticker := time.NewTicker(stateInterval)
	defer ticker.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
			n.platform.StateChanged()
		}
	}
}

type state struct {
	BackendState string `json:"backendState"`
	AuthURL      string `json:"authUrl"`
	Tailnet      string `json:"tailnet"`
	Self         string `json:"self"`
	Peers        []peer `json:"peers"`
}

type peer struct {
	DNSName  string   `json:"dnsName"`
	HostName string   `json:"hostName"`
	IPs      []string `json:"ips"`
}

// logWriter sends the embedded client's log lines to the platform.
type logWriter struct{ p Platform }

func (w logWriter) Write(b []byte) (int, error) {
	w.p.Log(strings.TrimRight(string(b), "\n"))
	return len(b), nil
}

type socksLogger struct{ p Platform }

func (l socksLogger) Errorf(format string, args ...any) {
	l.p.Log("socks: " + fmt.Sprintf(format, args...))
}
