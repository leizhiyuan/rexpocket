//go:build !android

package tailnet

// installSocketHook does nothing off Android, where the package only runs its tests.
func installSocketHook() {}
