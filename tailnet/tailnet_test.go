package tailnet

import (
	"net"
	"os"
	"path/filepath"
	"testing"
)

type fakePlatform struct{ interfaces string }

func (f fakePlatform) Interfaces() string     { return f.interfaces }
func (f fakePlatform) BindUDP(fd int32) error { return nil }
func (f fakePlatform) Log(line string)        {}
func (f fakePlatform) StateChanged()          {}

// What Kotlin describes is what netmon sees: names, flags and addresses, IPv4 and IPv6.
func TestInterfacesComeFromThePlatform(t *testing.T) {
	n := &Node{platform: fakePlatform{interfaces: `[
		{"name":"wlan0","index":30,"mtu":1500,"up":true,"multicast":true,"addrs":["10.38.164.14/24","fe80::1/64"]},
		{"name":"tun0","index":40,"mtu":9000,"up":true,"pointToPoint":true,"addrs":["198.18.0.1/30","not an address"]},
		{"name":"rmnet_data2","index":12,"mtu":1500,"up":false,"addrs":[]}
	]`}}
	current.Store(n)
	defer current.Store(nil)

	got, err := interfaces()
	if err != nil {
		t.Fatal(err)
	}
	if len(got) != 3 {
		t.Fatalf("got %d interfaces, want 3", len(got))
	}
	wlan := got[0]
	if wlan.Name != "wlan0" || wlan.Index != 30 || wlan.MTU != 1500 {
		t.Errorf("wlan0 = %+v", wlan.Interface)
	}
	if wlan.Flags&net.FlagUp == 0 || wlan.Flags&net.FlagMulticast == 0 || wlan.Flags&net.FlagLoopback != 0 {
		t.Errorf("wlan0 flags = %v", wlan.Flags)
	}
	if len(wlan.AltAddrs) != 2 || wlan.AltAddrs[0].String() != "10.38.164.14/24" || wlan.AltAddrs[1].String() != "fe80::1/64" {
		t.Errorf("wlan0 addrs = %v", wlan.AltAddrs)
	}
	if tun := got[1]; tun.Flags&net.FlagPointToPoint == 0 || len(tun.AltAddrs) != 1 {
		t.Errorf("tun0 = %v %v, want point-to-point with the one valid address", tun.Flags, tun.AltAddrs)
	}
	if down := got[2]; down.Flags&net.FlagUp != 0 {
		t.Errorf("rmnet_data2 should be down, flags = %v", down.Flags)
	}
}

func TestInterfacesWithoutANodeFail(t *testing.T) {
	current.Store(nil)
	if _, err := interfaces(); err == nil {
		t.Fatal("want an error with no node")
	}
}

// An Android process has no $HOME or $TMPDIR; the node's directories go under its state dir,
// and a value the environment already has is left alone.
func TestAppDirsFillWhatAndroidLeavesEmpty(t *testing.T) {
	dir := t.TempDir()
	t.Setenv("TS_LOGS_DIR", "")
	t.Setenv("TMPDIR", "")
	t.Setenv("HOME", "/kept")

	if err := useAppDirs(dir); err != nil {
		t.Fatal(err)
	}
	for env, want := range map[string]string{
		"TS_LOGS_DIR": filepath.Join(dir, "logs"),
		"TMPDIR":      filepath.Join(dir, "tmp"),
		"HOME":        "/kept",
	} {
		if got := os.Getenv(env); got != want {
			t.Errorf("%s = %q, want %q", env, got, want)
		}
	}
	if fi, err := os.Stat(filepath.Join(dir, "logs")); err != nil || !fi.IsDir() {
		t.Errorf("logs directory not made: %v", err)
	}
}
