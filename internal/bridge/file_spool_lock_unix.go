//go:build !windows

package bridge

import (
	"os"
	"syscall"
)

func trySpoolLock(f *os.File) (bool, error) {
	err := syscall.Flock(int(f.Fd()), syscall.LOCK_EX|syscall.LOCK_NB)
	if err == syscall.EWOULDBLOCK || err == syscall.EAGAIN {
		return true, nil
	}
	return false, err
}
func releaseSpoolLock(f *os.File) { _ = syscall.Flock(int(f.Fd()), syscall.LOCK_UN) }
