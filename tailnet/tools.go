//go:build tools

// Keeps golang.org/x/mobile in go.mod: gomobile bind compiles this package against its bind
// runtime, and tools/build-tailnet.sh installs the gomobile of the same version.
package tailnet

import _ "golang.org/x/mobile/bind"
