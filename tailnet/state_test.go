package tailnet

import (
	"encoding/json"
	"net/netip"
	"strings"
	"testing"
	"time"

	"tailscale.com/ipn/ipnstate"
	"tailscale.com/tailcfg"
	"tailscale.com/types/key"
)

func peerKey() key.NodePublic { return key.NewNode().Public() }

// The state the app reads: the old fields under their old names, and what the home screen shows
// about the node and each peer.
func TestStateReportsTheNodeAndItsPeers(t *testing.T) {
	expiry := time.Date(2027, 3, 1, 12, 0, 0, 0, time.UTC)
	seen := time.Date(2026, 11, 20, 8, 30, 0, 0, time.UTC)
	st := &ipnstate.Status{
		BackendState:   "Running",
		TailscaleIPs:   []netip.Addr{netip.MustParseAddr("100.101.102.103"), netip.MustParseAddr("fd7a:115c:a1e0::1")},
		Health:         []string{"Some peers are advertising routes"},
		CurrentTailnet: &ipnstate.TailnetStatus{Name: "example.com"},
		Self: &ipnstate.PeerStatus{
			DNSName: "pixel.tail1234.ts.net.", HostName: "pixel", OS: "android", Relay: "fra",
			UserID: 7, KeyExpiry: &expiry,
		},
		User: map[tailcfg.UserID]tailcfg.UserProfile{7: {LoginName: "me@example.com"}},
		Peer: map[key.NodePublic]*ipnstate.PeerStatus{
			peerKey(): {
				DNSName: "mac.tail1234.ts.net.", HostName: "mac", OS: "macOS", Online: true, Active: true,
				TailscaleIPs: []netip.Addr{netip.MustParseAddr("100.64.0.2")}, CurAddr: "192.168.1.20:41641", Relay: "fra",
			},
			peerKey(): {
				DNSName: "nas.tail1234.ts.net.", HostName: "nas", OS: "linux", Online: false, LastSeen: seen,
				TailscaleIPs: []netip.Addr{netip.MustParseAddr("100.64.0.3")}, Relay: "nyc",
			},
		},
	}
	b, err := json.Marshal(stateOf(st))
	if err != nil {
		t.Fatal(err)
	}
	var got struct {
		BackendState string `json:"backendState"`
		Tailnet      string `json:"tailnet"`
		Self         string `json:"self"`
		SelfInfo     struct {
			DNSName, HostName, OS, User, Relay, KeyExpiry string
			IPs                                           []string
		} `json:"selfInfo"`
		Health []string `json:"health"`
		Peers  []struct {
			DNSName    string   `json:"dnsName"`
			HostName   string   `json:"hostName"`
			IPs        []string `json:"ips"`
			OS         string   `json:"os"`
			Online     *bool    `json:"online"`
			LastSeen   string   `json:"lastSeen"`
			Link       string   `json:"link"`
			LinkDetail string   `json:"linkDetail"`
		} `json:"peers"`
	}
	if err := json.Unmarshal(b, &got); err != nil {
		t.Fatal(err)
	}
	if got.BackendState != "Running" || got.Tailnet != "example.com" || got.Self != "pixel.tail1234.ts.net" {
		t.Errorf("node = %s", b)
	}
	s := got.SelfInfo
	if s.DNSName != "pixel.tail1234.ts.net" || s.HostName != "pixel" || s.OS != "android" || s.User != "me@example.com" ||
		s.Relay != "fra" || s.KeyExpiry != "2027-03-01T12:00:00Z" || strings.Join(s.IPs, ",") != "100.101.102.103,fd7a:115c:a1e0::1" {
		t.Errorf("selfInfo = %+v", s)
	}
	if len(got.Health) != 1 {
		t.Errorf("health = %v", got.Health)
	}
	if len(got.Peers) != 2 {
		t.Fatalf("peers = %s", b)
	}
	for _, p := range got.Peers {
		switch p.HostName {
		case "mac":
			if p.DNSName != "mac.tail1234.ts.net" || p.OS != "macOS" || p.Online == nil || !*p.Online ||
				p.Link != "direct" || p.LinkDetail != "192.168.1.20:41641" || p.LastSeen != "" {
				t.Errorf("mac = %+v", p)
			}
		case "nas":
			if p.Online == nil || *p.Online || p.LastSeen != "2026-11-20T08:30:00Z" || p.Link != "idle" {
				t.Errorf("nas = %+v", p)
			}
		default:
			t.Errorf("unexpected peer %+v", p)
		}
	}
}

// A node that has not logged in yet has no self and no peers, and says where to log in.
func TestStateBeforeLogin(t *testing.T) {
	b, err := json.Marshal(stateOf(&ipnstate.Status{BackendState: "NeedsLogin", AuthURL: "https://login.tailscale.com/a/x"}))
	if err != nil {
		t.Fatal(err)
	}
	if s := string(b); !strings.Contains(s, `"authUrl":"https://login.tailscale.com/a/x"`) || strings.Contains(s, "selfInfo") {
		t.Errorf("state = %s", s)
	}
}

func TestLinkFollowsTheBestPathTheNodeHas(t *testing.T) {
	for _, c := range []struct {
		peer         ipnstate.PeerStatus
		link, detail string
	}{
		{ipnstate.PeerStatus{CurAddr: "1.2.3.4:41641", Relay: "fra", Active: true}, "direct", "1.2.3.4:41641"},
		{ipnstate.PeerStatus{PeerRelay: "100.64.0.9:7777:3", Relay: "fra", Active: true}, "peerRelay", "100.64.0.9:7777:3"},
		{ipnstate.PeerStatus{Relay: "fra", Active: true}, "derp", "fra"},
		{ipnstate.PeerStatus{Relay: "fra"}, "idle", ""},
	} {
		if link, detail := linkOf(&c.peer); link != c.link || detail != c.detail {
			t.Errorf("linkOf(%+v) = %s %s, want %s %s", c.peer, link, detail, c.link, c.detail)
		}
	}
}
