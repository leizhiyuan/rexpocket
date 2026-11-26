// Package tailnet is the app's Tailscale node: the official tailscale.com tsnet library, with
// the few hooks Android needs, behind an API gomobile can bind.
//
// The node runs in userspace (gVisor netstack), so it needs neither VpnService nor the
// Tailscale app. The app reaches the tailnet through a SOCKS5 proxy on loopback, which dials
// through the node; tailnet names are MagicDNS names the node resolves itself.
package tailnet

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
	"sync"
	"sync/atomic"
	"time"

	"golang.org/x/sys/unix"
	"me.leaver.rexpocket/tailnet/internal/dials"
	"tailscale.com/envknob"
	"tailscale.com/ipn"
	"tailscale.com/ipn/ipnstate"
	"tailscale.com/logtail"
	"tailscale.com/net/netmon"
	"tailscale.com/net/socks5"
	"tailscale.com/tailcfg"
	"tailscale.com/tsnet"
)

// Platform is what the app provides: what Go cannot find out on Android by itself, and where the
// node's news goes. Implemented in Kotlin.
type Platform interface {
	// Interfaces describes the phone's network interfaces as a JSON array of
	// {"name", "index", "mtu", "up", "loopback", "pointToPoint", "multicast", "addrs": ["ip/len"]}.
	// Go cannot list them itself: Android keeps netlink from apps.
	Interfaces() string

	// BindUDP binds a UDP socket the node is about to use to the network under any VPN on the
	// phone, so its holes are punched from the phone's own address. An error leaves the socket on
	// the default route.
	BindUDP(fd int32) error

	// Log receives one line of the node's own log.
	Log(line string)

	// StateChanged is told the node's state changed; it then asks [Node.State].
	StateChanged()
}

// The platform hooks are process-wide in tailscale.com, so they read whichever node is current.
var current atomic.Pointer[Node]

var installOnce sync.Once

func install() {
	installOnce.Do(func() {
		// No log uploads to Tailscale's log service (logtail): the app reads none of it, and each
		// upload is a radio wakeup and data in the background, with the tailnet's peer names and
		// endpoints in it. Opting out (TS_NO_LOGS_NO_SUPPORT) makes logpolicy's transport a no-op
		// and tells the control plane; Disable stops logtail buffering lines to disk at all.
		envknob.SetNoLogsNoSupport()
		logtail.Disable()
		netmon.RegisterInterfaceGetter(interfaces)
		installSocketHook()
	})
}

// Node is one running Tailscale node and its local SOCKS5 proxy.
type Node struct {
	platform Platform
	server   *tsnet.Server
	proxy    net.Listener
	cancel   context.CancelFunc
	closed   atomic.Bool
	dials    dials.Memory
}

// Start brings the node up with its state in dir - the login lives there, so it survives a
// restart - and opens the proxy. authKey may be empty: the node then asks for an interactive
// login, whose URL [Node.State] reports.
func Start(dir, hostname, authKey string, platform Platform) (*Node, error) {
	if platform == nil {
		return nil, errors.New("tailnet: no platform")
	}
	install()
	if err := useAppDirs(dir); err != nil {
		return nil, err
	}
	n := &Node{platform: platform}
	n.server = &tsnet.Server{
		Dir:      dir,
		Hostname: hostname,
		AuthKey:  authKey,
		Logf:     func(format string, args ...any) { platform.Log(fmt.Sprintf(format, args...)) },
		UserLogf: func(format string, args ...any) { platform.Log(fmt.Sprintf(format, args...)) },
	}
	current.Store(n)
	if err := n.server.Start(); err != nil {
		current.CompareAndSwap(n, nil)
		n.server.Close()
		return nil, err
	}
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		n.Close()
		return nil, err
	}
	n.proxy = ln
	proxy := &socks5.Server{
		Logf:   func(format string, args ...any) { platform.Log("socks: " + fmt.Sprintf(format, args...)) },
		Dialer: n.dial,
	}
	go proxy.Serve(ln)

	ctx, cancel := context.WithCancel(context.Background())
	n.cancel = cancel
	go n.watch(ctx)
	return n, nil
}

// useAppDirs gives the node somewhere to write besides its state file. An Android app process has
// no $HOME or $TMPDIR and runs in "/", so tailscale.com's own fallbacks for its log state -
// the user cache, the working directory, a temp directory - all fail, and it panics. Everything
// goes under the app's private state directory instead.
func useAppDirs(dir string) error {
	for env, sub := range map[string]string{"TS_LOGS_DIR": "logs", "TMPDIR": "tmp", "HOME": "home"} {
		if os.Getenv(env) != "" {
			continue
		}
		path := filepath.Join(dir, sub)
		if err := os.MkdirAll(path, 0o700); err != nil {
			return fmt.Errorf("tailnet: %s: %w", env, err)
		}
		os.Setenv(env, path)
	}
	return nil
}

// ProxyPort is the loopback port of the SOCKS5 proxy into the tailnet.
func (n *Node) ProxyPort() int32 {
	if n.proxy == nil {
		return 0
	}
	return int32(n.proxy.Addr().(*net.TCPAddr).Port)
}

// State is the node's state as JSON: {"backendState", "authUrl", "tailnet", "self", "selfInfo",
// "health", "peers": [...]}; see [state] for every field. The fields the app read first - the
// backend state, the login URL, the tailnet's name, the node's own MagicDNS name and each peer's
// names and addresses - keep their names, and every newer field may be missing.
func (n *Node) State() (string, error) {
	lc, err := n.server.LocalClient()
	if err != nil {
		return "", err
	}
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	st, err := lc.Status(ctx)
	if err != nil {
		return "", err
	}
	b, err := json.Marshal(stateOf(st))
	return string(b), err
}

// stateOf is what [Node.State] reports for st.
func stateOf(st *ipnstate.Status) state {
	out := state{BackendState: st.BackendState, AuthURL: st.AuthURL, Health: st.Health}
	if st.CurrentTailnet != nil {
		out.Tailnet = st.CurrentTailnet.Name
	}
	if st.Self != nil {
		out.Self = strings.TrimSuffix(st.Self.DNSName, ".")
		self := &selfInfo{
			DNSName:  out.Self,
			HostName: st.Self.HostName,
			IPs:      addrs(st.TailscaleIPs),
			OS:       st.Self.OS,
			Relay:    st.Self.Relay,
		}
		if u, ok := st.User[st.Self.UserID]; ok {
			self.User = u.LoginName
		}
		if st.Self.KeyExpiry != nil && !st.Self.KeyExpiry.IsZero() {
			self.KeyExpiry = st.Self.KeyExpiry.UTC().Format(time.RFC3339)
		}
		out.SelfInfo = self
	}
	for _, p := range st.Peer {
		online := p.Online
		link, detail := linkOf(p)
		item := peer{
			DNSName:    strings.TrimSuffix(p.DNSName, "."),
			HostName:   p.HostName,
			IPs:        addrs(p.TailscaleIPs),
			OS:         p.OS,
			Online:     &online,
			Link:       link,
			LinkDetail: detail,
		}
		if !p.LastSeen.IsZero() {
			item.LastSeen = p.LastSeen.UTC().Format(time.RFC3339)
		}
		out.Peers = append(out.Peers, item)
	}
	return out
}

// linkOf is how traffic to p goes right now, as far as the node knows without a ping: straight to
// an endpoint, through a peer relay, through its DERP region, or nowhere yet because nothing was sent.
func linkOf(p *ipnstate.PeerStatus) (string, string) {
	switch {
	case p.CurAddr != "":
		return "direct", p.CurAddr
	case p.PeerRelay != "":
		return "peerRelay", p.PeerRelay
	case p.Active && p.Relay != "":
		return "derp", p.Relay
	}
	return "idle", ""
}

// DialError is why the last dial to host through the proxy failed, or "" when it worked or was
// long enough ago not to matter. A SOCKS5 reply only says that a dial failed, and Tailscale's proxy
// gives every failure the same code; this says why: a port nobody listens on, a name the tailnet
// does not know, a peer that does not answer.
func (n *Node) DialError(host string) string {
	return n.dials.For(host)
}

// dial dials through the node for the proxy, and remembers how it went.
func (n *Node) dial(ctx context.Context, network, address string) (net.Conn, error) {
	conn, err := n.server.Dial(ctx, network, address)
	n.dials.Record(address, err)
	return conn, err
}

// Ping sends one disco ping to ip and reports how it went as JSON: {"latencyMs", "endpoint",
// "peerRelay", "derpRegionId", "derpRegionCode", "error"}. The path the ping took - direct, a peer
// relay, or a DERP server - is the path traffic takes.
func (n *Node) Ping(ip string, timeoutMillis int32) (string, error) {
	addr, err := netip.ParseAddr(ip)
	if err != nil {
		return "", err
	}
	lc, err := n.server.LocalClient()
	if err != nil {
		return "", err
	}
	ctx, cancel := context.WithTimeout(context.Background(), time.Duration(timeoutMillis)*time.Millisecond)
	defer cancel()
	res, err := lc.Ping(ctx, addr, tailcfg.PingDisco)
	if err != nil {
		return "", err
	}
	b, err := json.Marshal(pingResult(res))
	return string(b), err
}

// NetworkChanged tells the node the phone's network changed under it. Android gives apps no
// netlink, so the node's own monitor only polls every few minutes; without this a switch from
// Wi-Fi to mobile data would leave it on dead sockets until then.
func (n *Node) NetworkChanged() {
	if nm, ok := n.server.Sys().NetMon.GetOK(); ok && nm != nil {
		nm.InjectEvent()
	}
	lc, err := n.server.LocalClient()
	if err != nil {
		return
	}
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	// Rebind gives magicsock fresh sockets on the new network; restun finds the new endpoints.
	_ = lc.DebugAction(ctx, "rebind")
	_ = lc.DebugAction(ctx, "restun")
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
	n.server.Close()
	current.CompareAndSwap(n, nil)
}

// watch tells the platform whenever the IPN bus says something changed: the state, a login URL,
// a new netmap.
func (n *Node) watch(ctx context.Context) {
	for ctx.Err() == nil {
		lc, err := n.server.LocalClient()
		if err != nil {
			return
		}
		w, err := lc.WatchIPNBus(ctx, ipn.NotifyInitialState|ipn.NotifyPeerChanges)
		if err != nil {
			select {
			case <-ctx.Done():
				return
			case <-time.After(time.Second):
				continue
			}
		}
		for {
			note, err := w.Next()
			if err != nil {
				break
			}
			// The state, a login URL, the node itself, or the peers it can reach: each is a reason
			// to read the status again. Engine counters are left out; they tick constantly.
			if note.State != nil || note.BrowseToURL != nil || note.LoginFinished != nil ||
				note.SelfChange != nil || len(note.PeersChanged) > 0 || len(note.PeersRemoved) > 0 {
				n.platform.StateChanged()
			}
		}
		w.Close()
	}
}

type state struct {
	BackendState string    `json:"backendState"`
	AuthURL      string    `json:"authUrl"`
	Tailnet      string    `json:"tailnet"`
	Self         string    `json:"self"`
	SelfInfo     *selfInfo `json:"selfInfo,omitempty"`
	Health       []string  `json:"health,omitempty"`
	Peers        []peer    `json:"peers"`
}

// selfInfo is the node itself: its names and addresses, the account it is signed in as, its home
// DERP region, and when its key expires.
type selfInfo struct {
	DNSName   string   `json:"dnsName"`
	HostName  string   `json:"hostName"`
	IPs       []string `json:"ips"`
	OS        string   `json:"os,omitempty"`
	User      string   `json:"user,omitempty"`
	Relay     string   `json:"relay,omitempty"`
	KeyExpiry string   `json:"keyExpiry,omitempty"`
}

// peer is one peer. Online is whether the control plane sees it, and LastSeen when it went away.
// Link is "direct", "peerRelay", "derp" or "idle", with its endpoint, relay or region as detail.
type peer struct {
	DNSName    string   `json:"dnsName"`
	HostName   string   `json:"hostName"`
	IPs        []string `json:"ips"`
	OS         string   `json:"os,omitempty"`
	Online     *bool    `json:"online,omitempty"`
	LastSeen   string   `json:"lastSeen,omitempty"`
	Link       string   `json:"link,omitempty"`
	LinkDetail string   `json:"linkDetail,omitempty"`
}

type ping struct {
	LatencyMs      float64 `json:"latencyMs"`
	Endpoint       string  `json:"endpoint"`
	PeerRelay      string  `json:"peerRelay"`
	DERPRegionID   int64   `json:"derpRegionId"`
	DERPRegionCode string  `json:"derpRegionCode"`
	Error          string  `json:"error"`
}

func pingResult(r *ipnstate.PingResult) ping {
	return ping{
		LatencyMs:      r.LatencySeconds * 1000,
		Endpoint:       r.Endpoint,
		PeerRelay:      r.PeerRelay,
		DERPRegionID:   int64(r.DERPRegionID),
		DERPRegionCode: r.DERPRegionCode,
		Error:          r.Err,
	}
}

func addrs(list []netip.Addr) []string {
	out := make([]string, 0, len(list))
	for _, a := range list {
		out = append(out, a.String())
	}
	return out
}

// interfaces is netmon's interface getter: the platform's description, turned into what Go's
// net package would have returned had Android let it look.
func interfaces() ([]netmon.Interface, error) {
	n := current.Load()
	if n == nil {
		return nil, errors.New("tailnet: no node")
	}
	var described []struct {
		Name         string   `json:"name"`
		Index        int      `json:"index"`
		MTU          int      `json:"mtu"`
		Up           bool     `json:"up"`
		Loopback     bool     `json:"loopback"`
		PointToPoint bool     `json:"pointToPoint"`
		Multicast    bool     `json:"multicast"`
		Addrs        []string `json:"addrs"`
	}
	if err := json.Unmarshal([]byte(n.platform.Interfaces()), &described); err != nil {
		return nil, err
	}
	out := make([]netmon.Interface, 0, len(described))
	for _, d := range described {
		var flags net.Flags
		if d.Up {
			flags |= net.FlagUp | net.FlagRunning
		}
		if d.Loopback {
			flags |= net.FlagLoopback
		}
		if d.PointToPoint {
			flags |= net.FlagPointToPoint
		}
		if d.Multicast {
			flags |= net.FlagMulticast
		}
		var alt []net.Addr
		for _, s := range d.Addrs {
			p, err := netip.ParsePrefix(s)
			if err != nil {
				continue
			}
			alt = append(alt, &net.IPNet{IP: p.Addr().AsSlice(), Mask: net.CIDRMask(p.Bits(), p.Addr().BitLen())})
		}
		out = append(out, netmon.Interface{
			Interface: &net.Interface{Name: d.Name, Index: d.Index, MTU: d.MTU, Flags: flags},
			AltAddrs:  alt,
		})
	}
	return out, nil
}

// bindUDP is netns's Android hook, called for every socket the node opens. Only UDP is bound to
// the network under a VPN: a hole punched through a proxy VPN opens on the proxy's address and
// every packet falls back to DERP. TCP - the control plane, DERP itself - keeps the default
// route, because where tailscale.com is blocked, the VPN is what reaches it at all.
func bindUDP(fd int) error {
	n := current.Load()
	if n == nil {
		return nil
	}
	kind, err := unix.GetsockoptInt(fd, unix.SOL_SOCKET, unix.SO_TYPE)
	if err != nil || kind != unix.SOCK_DGRAM {
		return nil
	}
	if err := n.platform.BindUDP(int32(fd)); err != nil {
		// Not fatal: the socket keeps the default route, which still works through DERP.
		n.platform.Log("a UDP socket stays on the default route: " + err.Error())
	}
	return nil
}
