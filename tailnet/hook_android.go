//go:build android

package tailnet

import "tailscale.com/net/netns"

// installSocketHook hands every socket the node opens to bindUDP before it connects.
func installSocketHook() { netns.SetAndroidBindToNetworkFunc(bindUDP) }
