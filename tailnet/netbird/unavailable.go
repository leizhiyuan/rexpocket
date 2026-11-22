//go:build !netbird

package netbird

import "errors"

// errUnavailable is what every call reports in a build without the netbird tag.
var errUnavailable = errors.New("netbird: this build of the app has no Netbird node")

// Available reports whether this build has the Netbird node.
func Available() bool { return false }

// Start always fails in a build without the netbird tag.
func Start(dir, hostname, setupKey, managementURL string, platform Platform) (*Node, error) {
	return nil, errUnavailable
}

// Node is the Netbird node of a build with it; in this build there is never one to talk to.
type Node struct{}

// ProxyPort is 0: there is no proxy.
func (n *Node) ProxyPort() int32 { return 0 }

// State fails: there is no node.
func (n *Node) State() (string, error) { return "", errUnavailable }

// Ping fails: there is no node.
func (n *Node) Ping(ip string, timeoutMillis int32) (string, error) { return "", errUnavailable }

// DialError is empty: there is no node, so nothing was dialed.
func (n *Node) DialError(host string) string { return "" }

// Close does nothing: there is no node.
func (n *Node) Close() {}
