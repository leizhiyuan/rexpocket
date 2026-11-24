package netbird

type nopPlatform struct{}

func (nopPlatform) Log(string)    {}
func (nopPlatform) StateChanged() {}
