// Package dials remembers why the last dial to each host through a node's proxy failed.
//
// The app reaches Rex through the node's SOCKS5 proxy, and a SOCKS5 reply has room for little more
// than "it failed": Tailscale's proxy answers every failure with a general failure, so the app could
// not tell a peer that is offline from a port nobody listens on. The node knows; it keeps the reason
// here, and the app asks for it by host name once its connection has failed.
package dials

import (
	"net"
	"strings"
	"sync"
	"time"
)

// keep is how long a failure is worth reporting: the app asks right after its own dial failed.
const keep = 2 * time.Minute

// most bounds the memory: a phone dials a handful of hosts.
const most = 32

type failure struct {
	err string
	at  time.Time
}

// Memory is the last failure per host. The zero value is ready to use.
type Memory struct {
	mu   sync.Mutex
	last map[string]failure
	now  func() time.Time
}

func (m *Memory) clock() time.Time {
	if m.now != nil {
		return m.now()
	}
	return time.Now()
}

// key is the host of address, lowercased and without a trailing dot or a port.
func key(address string) string {
	host := address
	if h, _, err := net.SplitHostPort(address); err == nil {
		host = h
	}
	return strings.ToLower(strings.TrimSuffix(strings.Trim(host, "[]"), "."))
}

// Record notes how a dial to address ended: a failure is kept, a success forgets the last one.
func (m *Memory) Record(address string, err error) {
	k := key(address)
	if k == "" {
		return
	}
	m.mu.Lock()
	defer m.mu.Unlock()
	if err == nil {
		delete(m.last, k)
		return
	}
	if m.last == nil {
		m.last = make(map[string]failure)
	}
	if _, ok := m.last[k]; !ok && len(m.last) >= most {
		// Full: the oldest entry makes room.
		var oldest string
		for name, f := range m.last {
			if oldest == "" || f.at.Before(m.last[oldest].at) {
				oldest = name
			}
		}
		delete(m.last, oldest)
	}
	m.last[k] = failure{err: err.Error(), at: m.clock()}
}

// For is the last failure dialing host, or "" when the last dial worked or it is too old to matter.
func (m *Memory) For(host string) string {
	k := key(host)
	m.mu.Lock()
	defer m.mu.Unlock()
	f, ok := m.last[k]
	if !ok || m.clock().Sub(f.at) > keep {
		return ""
	}
	return f.err
}
