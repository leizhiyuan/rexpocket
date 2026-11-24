//go:build netbird

package netbird

import (
	"encoding/json"
	"strings"
	"testing"
	"time"
)

// The state is the tailnet package's JSON: the app's parser reads both nodes the same way.
func TestStateReportsTheNodeAndItsPeers(t *testing.T) {
	handshake := time.Date(2026, 11, 22, 9, 0, 0, 0, time.UTC)
	st := stateOf(status{
		managementURL:       "https://api.netbird.io:443",
		managementConnected: true,
		signalConnected:     true,
		selfFQDN:            "phone.netbird.cloud.",
		selfIPs:             []string{"100.90.1.2/16", ""},
		peers: []peerStatus{
			{fqdn: "mac.netbird.cloud", ip: "100.90.1.3", conn: "Connected", endpoint: "192.168.1.20:51820", latency: 12 * time.Millisecond, lastHandshake: handshake},
			{fqdn: "nas.netbird.cloud", ip: "100.90.1.4", conn: "Connected", relayed: true, relayServer: "rels://relay.netbird.io:443"},
			{fqdn: "pi.netbird.cloud", ip: "100.90.1.5", conn: "Idle"},
			{fqdn: "vm.netbird.cloud", ip: "100.90.1.6", conn: "Connecting"},
			{fqdn: "", ip: "100.90.1.7", conn: "Idle"},
		},
	})
	if st.BackendState != "Running" || st.Tailnet != "api.netbird.io" || st.Self != "phone.netbird.cloud" {
		t.Errorf("node = %+v", st)
	}
	if st.SelfInfo == nil || st.SelfInfo.HostName != "phone" || strings.Join(st.SelfInfo.IPs, ",") != "100.90.1.2" {
		t.Errorf("selfInfo = %+v", st.SelfInfo)
	}
	if len(st.Peers) != 4 {
		t.Fatalf("peers = %+v, want the four with a name", st.Peers)
	}
	want := []struct{ host, link, detail string }{
		{"mac", "direct", "192.168.1.20:51820"},
		{"nas", "relay", "rels://relay.netbird.io:443"},
		{"pi", "idle", ""},
		{"vm", "connecting", ""},
	}
	for i, w := range want {
		p := st.Peers[i]
		if p.HostName != w.host || p.Link != w.link || p.LinkDetail != w.detail {
			t.Errorf("peer %d = %+v, want %+v", i, p, w)
		}
	}
	if st.Peers[0].LatencyMs != 12 || st.Peers[0].LastHandshake != "2026-11-22T09:00:00Z" {
		t.Errorf("mac = %+v", st.Peers[0])
	}
	b, _ := json.Marshal(st)
	if strings.Contains(string(b), `"online"`) {
		t.Errorf("Netbird cannot tell whether a peer is online, so it must not say: %s", b)
	}
}

// A management server that cannot be reached is what the person needs to see while the node waits.
func TestABrokenControlPlaneIsAHealthLine(t *testing.T) {
	st := stateOf(status{
		managementURL:   "https://netbird.example.com",
		managementError: "connection refused",
		signalConnected: true,
	})
	if st.BackendState != "Starting" {
		t.Errorf("backend state = %s, want Starting", st.BackendState)
	}
	if len(st.Health) != 1 || st.Health[0] != "management: connection refused" {
		t.Errorf("health = %v", st.Health)
	}
	if st.SelfInfo != nil {
		t.Errorf("a node that has not logged in has no self: %+v", st.SelfInfo)
	}
}
