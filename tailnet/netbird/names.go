//go:build netbird

package netbird

import (
	"context"
	"net"
	"strings"
)

// peerName is one peer of the network as the node lists it: its address and its DNS name.
type peerName struct {
	ip   string
	fqdn string
}

// addressOf is the address of the peer host names. The node's netstack resolves names through DNS
// servers that a phone does not have, so a name is looked up here, among the peers the node lists,
// before dialing. Matching is case-insensitive, on the full name or its first label, as MagicDNS does.
// It reports false when no peer has that name, and then the name goes on to be resolved as it is.
func addressOf(host string, peers []peerName) (string, bool) {
	want := strings.ToLower(strings.TrimSuffix(host, "."))
	if want == "" {
		return "", false
	}
	short := strings.SplitN(want, ".", 2)[0]
	for _, p := range peers {
		name := strings.ToLower(strings.TrimSuffix(p.fqdn, "."))
		if name == "" || p.ip == "" {
			continue
		}
		if name == want || strings.SplitN(name, ".", 2)[0] == short && !strings.Contains(want, ".") {
			return p.ip, true
		}
	}
	return "", false
}

// peersOf reads the node's peers for [addressOf].
func (n *Node) peersOf() []peerName {
	st, err := n.client.Status()
	if err != nil {
		return nil
	}
	out := make([]peerName, 0, len(st.Peers))
	for _, p := range st.Peers {
		out = append(out, peerName{ip: p.IP, fqdn: p.FQDN})
	}
	return out
}

// dial dials through the node, with a name resolved from its peers first.
func (n *Node) dial(ctx context.Context, network, address string) (net.Conn, error) {
	host, port, err := net.SplitHostPort(address)
	if err == nil && net.ParseIP(host) == nil {
		if ip, ok := addressOf(host, n.peersOf()); ok {
			address = net.JoinHostPort(ip, port)
		}
	}
	return n.client.DialContext(ctx, network, address)
}
