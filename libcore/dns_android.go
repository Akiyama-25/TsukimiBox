//go:build android && cgo

package libcore

/*
#include <stdint.h>
#include <stddef.h>
#include <stdlib.h>
#include <dlfcn.h>

typedef int (*android_res_nsend_t)(uint64_t network, const uint8_t* msg, size_t msglen, int flags);
typedef int (*android_res_nresult_t)(int fd, int* rcode, uint8_t* resp, size_t resp_len);

static int call_android_res_nsend(void* sym, uint64_t network, const uint8_t* msg, size_t msglen, int flags) {
    android_res_nsend_t f = (android_res_nsend_t)sym;
    if (!f) return -1;
    return f(network, msg, msglen, flags);
}

static int call_android_res_nresult(void* sym, int fd, int* rcode, uint8_t* resp, size_t resp_len) {
    android_res_nresult_t f = (android_res_nresult_t)sym;
    if (!f) return -1;
    return f(fd, rcode, resp, resp_len);
}
*/
import "C"

import (
	"context"
	"errors"
	"os"
	"time"
	"unsafe"

	"golang.org/x/sys/unix"
)

func init() {
	libname := C.CString("libandroid.so")
	defer C.free(unsafe.Pointer(libname))

	libHandle := C.dlopen(libname, C.int(C.RTLD_NOW))
	if libHandle == nil {
		return
	}

	symNameSend := C.CString("android_res_nsend")
	defer C.free(unsafe.Pointer(symNameSend))
	androidResNSendSym := C.dlsym(libHandle, symNameSend)
	if androidResNSendSym == nil {
		return
	}

	symNameResult := C.CString("android_res_nresult")
	defer C.free(unsafe.Pointer(symNameResult))
	androidResNResultSym := C.dlsym(libHandle, symNameResult)
	if androidResNResultSym == nil {
		return
	}

	callAndroidResNSend := func(network uint64, msg []byte) (int, error) {
		if len(msg) == 0 {
			return 0, errors.New("empty payload")
		}
		msgPtr := (*C.uint8_t)(unsafe.Pointer(&msg[0]))
		msgLen := C.size_t(len(msg))
		ret := C.call_android_res_nsend(androidResNSendSym, C.uint64_t(network), msgPtr, msgLen, C.int(0))
		return int(ret), nil
	}

	callAndroidResNResult := func(fd int, resp []byte) (int, int) {
		if len(resp) == 0 {
			return 0, 0
		}
		respPtr := (*C.uint8_t)(unsafe.Pointer(&resp[0]))
		respLen := C.size_t(len(resp))
		var rcode C.int
		n := C.call_android_res_nresult(androidResNResultSym, C.int(fd), &rcode, respPtr, respLen)
		return int(rcode), int(n)
	}

	// set rawQueryFunc
	rawQueryFunc = func(ctx context.Context, networkHandle int64, request []byte) ([]byte, error) {
		fd, err := callAndroidResNSend(uint64(networkHandle), request)
		if err != nil {
			return nil, err
		}
		if fd < 0 {
			return nil, unix.Errno(-fd)
		}

		// wait for response (timeout 5000 ms) while honoring ctx cancellation and EINTR
		pfds := []unix.PollFd{{Fd: int32(fd), Events: unix.POLLIN | unix.POLLERR}}
		deadline := time.Now().Add(5 * time.Second)
		for {
			if err := ctx.Err(); err != nil {
				unix.Close(fd)
				return nil, err
			}
			rem := time.Until(deadline)
			if rem <= 0 {
				unix.Close(fd)
				return nil, context.DeadlineExceeded
			}
			waitMs := int(rem.Milliseconds())
			if waitMs > 250 {
				waitMs = 250
			} else if waitMs < 1 {
				waitMs = 1
			}
			nReady, err := unix.Poll(pfds, waitMs)
			if err != nil {
				if errors.Is(err, unix.EINTR) {
					continue
				}
				unix.Close(fd)
				return nil, err
			}
			if nReady > 0 {
				break
			}
		}

		// read response into buffer
		response := make([]byte, 8192)
		_, n := callAndroidResNResult(fd, response)
		if n < 0 {
			return nil, unix.Errno(-n)
		}
		if n == 0 {
			return nil, os.ErrInvalid
		}
		return response[:n], nil
	}
}
