package dials

import (
	"errors"
	"fmt"
	"testing"
	"time"
)

func TestAFailureIsKeptByHost(t *testing.T) {
	var m Memory
	m.Record("Mac.tail1234.ts.net.:7297", errors.New("connection was refused"))
	if got := m.For("mac.tail1234.ts.net"); got != "connection was refused" {
		t.Fatalf("For = %q, want the failure", got)
	}
	if got := m.For("other.tail1234.ts.net"); got != "" {
		t.Fatalf("another host: %q, want none", got)
	}
}

func TestASuccessForgetsTheFailure(t *testing.T) {
	var m Memory
	m.Record("100.64.0.2:7297", errors.New("i/o timeout"))
	m.Record("100.64.0.2:443", nil)
	if got := m.For("100.64.0.2"); got != "" {
		t.Fatalf("For = %q after a success, want none", got)
	}
}

func TestAnOldFailureIsNotReported(t *testing.T) {
	now := time.Unix(1_000, 0)
	m := Memory{now: func() time.Time { return now }}
	m.Record("[fd7a:115c:a1e0::1]:7297", errors.New("no route"))
	if got := m.For("fd7a:115c:a1e0::1"); got != "no route" {
		t.Fatalf("For = %q, want the failure", got)
	}
	now = now.Add(keep + time.Second)
	if got := m.For("fd7a:115c:a1e0::1"); got != "" {
		t.Fatalf("For = %q after %s, want none", got, keep)
	}
}

func TestTheMemoryIsBounded(t *testing.T) {
	now := time.Unix(1_000, 0)
	m := Memory{now: func() time.Time { now = now.Add(time.Millisecond); return now }}
	for i := 0; i < most+5; i++ {
		m.Record(fmt.Sprintf("host%d:1", i), errors.New("x"))
	}
	if len(m.last) != most {
		t.Fatalf("kept %d, want %d", len(m.last), most)
	}
	if m.For("host0") != "" {
		t.Fatal("the oldest failure should have made room")
	}
	if m.For(fmt.Sprintf("host%d", most+4)) == "" {
		t.Fatal("the newest failure should be kept")
	}
}
