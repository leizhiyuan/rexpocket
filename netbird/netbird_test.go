package netbird

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
)

type nopPlatform struct{}

func (nopPlatform) Log(string)    {}
func (nopPlatform) StateChanged() {}

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
