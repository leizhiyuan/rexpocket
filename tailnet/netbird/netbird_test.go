//go:build netbird

package netbird

import (
	"encoding/json"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestReadPrivateKeyMissingFile(t *testing.T) {
	got, err := readPrivateKey(filepath.Join(t.TempDir(), "config.json"))
	if err != nil || got != "" {
		t.Fatalf("missing config: got %q, %v; want \"\", nil", got, err)
	}
}

func TestReadPrivateKeyReadsSavedKey(t *testing.T) {
	path := filepath.Join(t.TempDir(), "config.json")
	if err := os.WriteFile(path, []byte(`{"PrivateKey":"abc123","ManagementURL":"https://example.invalid"}`), 0o600); err != nil {
		t.Fatal(err)
	}
	got, err := readPrivateKey(path)
	if err != nil || got != "abc123" {
		t.Fatalf("got %q, %v; want abc123, nil", got, err)
	}
}

func TestReadPrivateKeyRejectsBrokenFile(t *testing.T) {
	path := filepath.Join(t.TempDir(), "config.json")
	if err := os.WriteFile(path, []byte(`{not json`), 0o600); err != nil {
		t.Fatal(err)
	}
	if _, err := readPrivateKey(path); err == nil {
		t.Fatal("a broken config must be an error, not an empty key")
	}
}

func TestStartNeedsPlatform(t *testing.T) {
	if _, err := Start(t.TempDir(), "phone", "key", "", nil); err == nil {
		t.Fatal("Start without a platform must fail")
	}
}

func TestStartWithoutSetupKeyOrSavedNodeAsksForKey(t *testing.T) {
	_, err := Start(t.TempDir(), "phone", "", "", nopPlatform{})
	if err == nil || !strings.Contains(err.Error(), "setup key") {
		t.Fatalf("got %v; want a message that a setup key is needed", err)
	}
}

func TestABuildWithNetbirdHasTheNode(t *testing.T) {
	if !Available() {
		t.Fatal("a build with the netbird tag must have the Netbird node")
	}
}

// The ping answer is read by the app's TailnetPath parser under these names, shared with the
// Tailscale node; a renamed field would silently turn every path into "no path".
func TestPingAnswerUsesTheFieldsTheAppReads(t *testing.T) {
	b, err := json.Marshal(ping{LatencyMs: 12.5, RelayServer: "rels://relay.example.com:443"})
	if err != nil {
		t.Fatal(err)
	}
	var got map[string]any
	if err := json.Unmarshal(b, &got); err != nil {
		t.Fatal(err)
	}
	for _, key := range []string{"latencyMs", "endpoint", "peerRelay", "derpRegionId", "derpRegionCode", "relayServer", "error"} {
		if _, ok := got[key]; !ok {
			t.Errorf("ping answer lacks %q: %s", key, b)
		}
	}
	if got["relayServer"] != "rels://relay.example.com:443" || got["latencyMs"] != 12.5 {
		t.Errorf("ping answer lost its values: %s", b)
	}
}
