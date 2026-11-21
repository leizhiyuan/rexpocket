//go:build netbird

package netbird

import (
	"testing"
	"time"
)

// A lazily connected peer comes up only on traffic for it: waitForPath must send some while the
// peer is not connected, and report the path once it is.
func TestWaitForPathWakesALazyPeer(t *testing.T) {
	woken := 0
	read := func() ping {
		if woken == 0 {
			return ping{Error: errNotConnected}
		}
		return ping{Endpoint: "172.29.83.20:51820"}
	}
	res := waitForPath(read, func() { woken++ }, time.Now().Add(5*time.Second), time.Millisecond)
	if woken != 1 || res.Error != "" || res.Endpoint == "" {
		t.Fatalf("woken %d times, result %+v; want one wake and the path", woken, res)
	}
}

func TestWaitForPathDoesNotWakeAConnectedPeer(t *testing.T) {
	res := waitForPath(func() ping { return ping{RelayServer: "rel://relay"} },
		func() { t.Fatal("woke a connected peer") }, time.Now().Add(time.Second), time.Millisecond)
	if res.RelayServer == "" {
		t.Fatalf("result %+v; want the relayed path", res)
	}
}

func TestWaitForPathWakesAtMostOncePerInterval(t *testing.T) {
	woken := 0
	res := waitForPath(func() ping { return ping{Error: errNotConnected} }, func() { woken++ },
		time.Now().Add(300*time.Millisecond), time.Millisecond)
	if woken != 1 || res.Error != errNotConnected {
		t.Fatalf("woken %d times in 300ms, result %+v; want once and the error", woken, res)
	}
}

func TestWaitForPathDoesNotWakeAnUnknownPeer(t *testing.T) {
	res := waitForPath(func() ping { return ping{Error: "not a peer of this network"} },
		func() { t.Fatal("woke an address that is not a peer") }, time.Now().Add(50*time.Millisecond), time.Millisecond)
	if res.Error == "" {
		t.Fatalf("result %+v; want the error", res)
	}
}
