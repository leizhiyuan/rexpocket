//go:build !netbird

package netbird

import "testing"

func TestAStockBuildHasNoNetbird(t *testing.T) {
	if Available() {
		t.Fatal("a build without the netbird tag must not claim a Netbird node")
	}
	if _, err := Start(t.TempDir(), "phone", "key", "", nopPlatform{}); err == nil {
		t.Fatal("Start must fail in a build without the netbird tag")
	}
}
