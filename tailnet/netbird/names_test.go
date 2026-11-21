//go:build netbird

package netbird

import "testing"

func TestAPeerNameResolvesToItsAddress(t *testing.T) {
	peers := []peerName{
		{ip: "100.103.22.205", fqdn: "davidb.netbird.cloud."},
		{ip: "100.103.22.9", fqdn: "vm-build.netbird.cloud"},
	}
	cases := map[string]string{
		"davidb.netbird.cloud":   "100.103.22.205",
		"DavidB.Netbird.Cloud.":  "100.103.22.205",
		"vm-build":               "100.103.22.9",
		"vm-build.netbird.cloud": "100.103.22.9",
	}
	for host, want := range cases {
		got, ok := addressOf(host, peers)
		if !ok || got != want {
			t.Errorf("addressOf(%q) = %q, %v; want %q", host, got, ok, want)
		}
	}
}

func TestANameNoPeerHasIsLeftAlone(t *testing.T) {
	peers := []peerName{{ip: "100.103.22.205", fqdn: "davidb.netbird.cloud"}}
	for _, host := range []string{"example.com", "", "davidb.other.example"} {
		if _, ok := addressOf(host, peers); ok {
			t.Errorf("addressOf(%q) resolved a name no peer has", host)
		}
	}
}

func TestAPeerWithoutAnAddressIsNotResolved(t *testing.T) {
	peers := []peerName{{ip: "", fqdn: "davidb.netbird.cloud"}}
	if _, ok := addressOf("davidb.netbird.cloud", peers); ok {
		t.Fatal("a peer with no address must not resolve")
	}
}
