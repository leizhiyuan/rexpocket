// Package netbird is the app's Netbird node: the embeddable client of github.com/netbirdio/netbird
// (client/embed), behind the same API the tailnet package offers, so the app can join a Netbird
// network instead of a Tailscale one.
//
// The Netbird client is large, about 30 MB in an Android library, and most builds do not want it.
// So it is built only with the netbird tag (tools/build-tailnet.sh with REXPOCKET_NETBIRD=1); without
// the tag this package has the same API and reports that Netbird is not in the build, see
// unavailable.go. Either way the app's Java classes are the same, so it compiles the same way.
package netbird

// Platform is what the app wants told: the node's log and its state changes. Implemented in Kotlin.
type Platform interface {
	// Log receives one line of the node's own log.
	Log(line string)

	// StateChanged is told the node's state changed; it then asks [Node.State].
	StateChanged()
}
