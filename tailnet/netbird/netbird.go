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
	"net/url"
	"os"
	"path/filepath"
	"strings"
	"sync/atomic"
	"time"

	"github.com/netbirdio/netbird/client/embed"
	socks5 "github.com/things-go/go-socks5"
	"me.leaver.rexpocket/tailnet/internal/dials"
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
	dials    dials.Memory
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
		socks5.WithResolver(peerResolver{peers: n.peersOf, next: socks5.DNSResolver{}}),
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
// {"backendState", "authUrl", "tailnet", "self", "selfInfo", "health", "peers": [...]}.
// The node is "Running" once its management and signal connections are up, and "Starting" until
// then; the embedded client does not report an auth URL. See [stateOf] for the rest.
func (n *Node) State() (string, error) {
	if n.closed.Load() {
		return "", errors.New("netbird: node is closed")
	}
	st, err := n.client.Status()
	if err != nil {
		return "", err
	}
	in := status{
		managementURL:       st.ManagementState.URL,
		managementConnected: st.ManagementState.Connected,
		signalConnected:     st.SignalState.Connected,
		selfFQDN:            st.LocalPeerState.FQDN,
		selfIPs:             []string{st.LocalPeerState.IP, st.LocalPeerState.IPv6},
	}
	if st.ManagementState.Error != nil {
		in.managementError = st.ManagementState.Error.Error()
	}
	if st.SignalState.Error != nil {
		in.signalError = st.SignalState.Error.Error()
	}
	for _, p := range st.Peers {
		in.peers = append(in.peers, peerStatus{
			fqdn:          p.FQDN,
			ip:            p.IP,
			ipv6:          p.IPv6,
			conn:          p.ConnStatus.String(),
			relayed:       p.Relayed,
			endpoint:      p.RemoteIceCandidateEndpoint,
			relayServer:   p.RelayServerAddress,
			latency:       p.Latency,
			lastHandshake: p.LastWireguardHandshake,
		})
	}
	b, err := json.Marshal(stateOf(in))
	return string(b), err
}

// status is the part of the embedded client's status [stateOf] reads. The client's own type lives
// in an internal package of Netbird's, which this module cannot name.
type status struct {
	managementURL       string
	managementConnected bool
	managementError     string
	signalConnected     bool
	signalError         string
	selfFQDN            string
	selfIPs             []string
	peers               []peerStatus
}

type peerStatus struct {
	fqdn, ip, ipv6        string
	conn                  string // "Idle", "Connecting" or "Connected"
	relayed               bool
	endpoint, relayServer string
	latency               time.Duration
	lastHandshake         time.Time
}

// stateOf is what [Node.State] reports for in. Netbird has no network name, so the management
// server's host stands in for it; a broken management or signal connection is a health line, as
// Tailscale reports its own trouble. A peer's link is the connection the node has to it: none yet
// ("idle", since Netbird connects lazily), "connecting", or up, "direct" or through a "relay". The
// control plane's view of whether a peer is online is not in the embedded client's status, so
// "online" is left out.
func stateOf(in status) state {
	out := state{BackendState: "Starting", Tailnet: hostOf(in.managementURL)}
	if in.managementConnected && in.signalConnected {
		out.BackendState = "Running"
	}
	if in.managementError != "" {
		out.Health = append(out.Health, "management: "+in.managementError)
	}
	if in.signalError != "" {
		out.Health = append(out.Health, "signal: "+in.signalError)
	}
	if in.selfFQDN != "" {
		out.Self = strings.TrimSuffix(in.selfFQDN, ".")
	}
	if out.Self != "" || len(nonEmpty(in.selfIPs)) > 0 {
		var ips []string
		for _, ip := range nonEmpty(in.selfIPs) {
			ips = append(ips, strings.SplitN(ip, "/", 2)[0])
		}
		out.SelfInfo = &selfInfo{
			DNSName:  out.Self,
			HostName: strings.SplitN(out.Self, ".", 2)[0],
			IPs:      ips,
		}
	}
	for _, p := range in.peers {
		name := strings.TrimSuffix(p.fqdn, ".")
		if name == "" {
			continue
		}
		item := peer{
			DNSName:  name,
			HostName: strings.SplitN(name, ".", 2)[0],
			IPs:      nonEmpty([]string{p.ip, p.ipv6}),
		}
		switch {
		case p.conn == "Connecting":
			item.Link = "connecting"
		case p.conn != "Connected":
			item.Link = "idle"
		case p.relayed:
			item.Link, item.LinkDetail = "relay", p.relayServer
		default:
			item.Link, item.LinkDetail = "direct", p.endpoint
		}
		if p.conn == "Connected" && p.latency > 0 {
			item.LatencyMs = float64(p.latency) / float64(time.Millisecond)
		}
		if !p.lastHandshake.IsZero() {
			item.LastHandshake = p.lastHandshake.UTC().Format(time.RFC3339)
		}
		out.Peers = append(out.Peers, item)
	}
	return out
}

func nonEmpty(list []string) []string {
	var out []string
	for _, s := range list {
		if s != "" {
			out = append(out, s)
		}
	}
	return out
}

// hostOf is the host of a management URL such as https://api.netbird.io:443, or the URL itself.
func hostOf(raw string) string {
	if raw == "" {
		return ""
	}
	if u, err := url.Parse(raw); err == nil && u.Hostname() != "" {
		return u.Hostname()
	}
	return raw
}

// DialError is why the last dial to host through the proxy failed, or "" when it worked or was
// long enough ago not to matter.
func (n *Node) DialError(host string) string {
	return n.dials.For(host)
}

// Ping reports the path to the peer at ip as JSON, in the shape the tailnet package reports:
// {"latencyMs", "endpoint", "peerRelay", "derpRegionId", "derpRegionCode", "relayServer", "error"}.
// Netbird keeps the latency of its own keepalives, so no echo is timed: it waits up to timeoutMillis
// for the peer's connection to come up, then reports the path it settled on. A direct path names
// its endpoint; a relayed one names the relay server, which is never a peer of the network.
//
// Netbird connects to a peer lazily, on the first packet for it, so while the peer is not connected
// Ping sends it a datagram to bring the connection up; otherwise it would wait for nothing.
func (n *Node) Ping(ip string, timeoutMillis int32) (string, error) {
	addr, err := netip.ParseAddr(ip)
	if err != nil {
		return "", err
	}
	deadline := time.Now().Add(time.Duration(timeoutMillis) * time.Millisecond)
	res := waitForPath(func() ping { return n.pathTo(addr) }, func() { n.wake(addr) }, deadline, pingPoll)
	b, err := json.Marshal(res)
	return string(b), err
}

// wakeEvery is how often Ping sends another packet to a peer whose connection is not up yet.
const wakeEvery = time.Second

// waitForPath reads the path until it has no error or the deadline passes. While the peer is not
// connected it calls wake, at most once per wakeEvery, starting at once.
func waitForPath(read func() ping, wake func(), deadline time.Time, poll time.Duration) ping {
	var woke time.Time
	for {
		res := read()
		if res.Error == "" || time.Now().After(deadline) {
			return res
		}
		if res.Error == errNotConnected && time.Since(woke) >= wakeEvery {
			wake()
			woke = time.Now()
		}
		time.Sleep(poll)
	}
}

// wakePort is where wake's datagram goes: the discard port. Nothing needs to answer it; it only
// has to pass through the tunnel for the lazy connection to start.
const wakePort = 9

// wake sends one datagram to the peer at addr through the node, which starts a lazy connection.
func (n *Node) wake(addr netip.Addr) {
	ctx, cancel := context.WithTimeout(context.Background(), time.Second)
	defer cancel()
	conn, err := n.client.DialContext(ctx, "udp", netip.AddrPortFrom(addr, wakePort).String())
	if err != nil {
		return
	}
	conn.Write([]byte{0})
	conn.Close()
}

// errNotConnected is the path's error while the peer's connection is not up.
const errNotConnected = "the peer is not connected yet"

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
			return ping{Error: errNotConnected}
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

// state, selfInfo and peer are the tailnet package's state JSON; the fields Netbird has no
// answer for are left out.
type state struct {
	BackendState string    `json:"backendState"`
	AuthURL      string    `json:"authUrl"`
	Tailnet      string    `json:"tailnet"`
	Self         string    `json:"self"`
	SelfInfo     *selfInfo `json:"selfInfo,omitempty"`
	Health       []string  `json:"health,omitempty"`
	Peers        []peer    `json:"peers"`
}

type selfInfo struct {
	DNSName  string   `json:"dnsName"`
	HostName string   `json:"hostName"`
	IPs      []string `json:"ips"`
}

type peer struct {
	DNSName       string   `json:"dnsName"`
	HostName      string   `json:"hostName"`
	IPs           []string `json:"ips"`
	Link          string   `json:"link,omitempty"`
	LinkDetail    string   `json:"linkDetail,omitempty"`
	LatencyMs     float64  `json:"latencyMs,omitempty"`
	LastHandshake string   `json:"lastHandshake,omitempty"`
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
