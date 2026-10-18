package bridge

import (
	"os"
	"syscall"
	"unsafe"
)

var spoolKernel = syscall.NewLazyDLL("kernel32.dll")
var spoolLock = spoolKernel.NewProc("LockFileEx")
var spoolUnlock = spoolKernel.NewProc("UnlockFileEx")

func trySpoolLock(f *os.File) (bool, error) {
	var overlap syscall.Overlapped
	// Exclusive, fail immediately; all cooperating replicas lock byte zero.
	ok, _, err := spoolLock.Call(f.Fd(), 3, 0, 1, 0, uintptr(unsafe.Pointer(&overlap)))
	if ok != 0 {
		return false, nil
	}
	if err == syscall.Errno(33) {
		return true, nil
	}
	return false, err
}
func releaseSpoolLock(f *os.File) {
	var overlap syscall.Overlapped
	_, _, _ = spoolUnlock.Call(f.Fd(), 0, 1, 0, uintptr(unsafe.Pointer(&overlap)))
}
