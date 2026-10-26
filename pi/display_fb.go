//go:build linux

package main

import (
	"fmt"
	"image"
	"log"
	"os"
	"syscall"
	"unsafe"
)

// Linux framebuffer ioctls and console modes (linux/fb.h, linux/kd.h)
const (
	fbioGetVScreenInfo = 0x4600
	fbioGetFScreenInfo = 0x4602
	kdSetMode          = 0x4b3a
	kdText             = 0
	kdGraphics         = 1
)

type fbBitfield struct{ Offset, Length, MSBRight uint32 }

type fbVarScreenInfo struct {
	XRes, YRes, XResVirtual, YResVirtual, XOffset, YOffset uint32
	BitsPerPixel, Grayscale                                uint32
	Red, Green, Blue, Transp                               fbBitfield
	_                                                      [20]uint32
}

type fbFixScreenInfo struct {
	ID         [16]byte
	SmemStart  uintptr
	SmemLen    uint32
	Type       uint32
	TypeAux    uint32
	Visual     uint32
	XPanStep   uint16
	YPanStep   uint16
	YWrapStep  uint16
	LineLength uint32
	MMIOStart  uintptr
	MMIOLen    uint32
	Accel      uint32
	Caps       uint16
	_          [2]uint16
}

// fbDisplay draws straight into /dev/fb0, with no X, Wayland or browser
type fbDisplay struct {
	mem    []byte
	w, h   int
	stride int
	bpp    int
	vinfo  fbVarScreenInfo
}

func init() {
	backends["fb"] = runFramebuffer
}

// ioctl takes arg as a pointer so the GC keeps it valid for the call
func ioctl(fd, req uintptr, arg unsafe.Pointer) error {
	if _, _, errno := syscall.Syscall(syscall.SYS_IOCTL, fd, req, uintptr(arg)); errno != 0 {
		return errno
	}
	return nil
}

func ioctlInt(fd, req, arg uintptr) error {
	if _, _, errno := syscall.Syscall(syscall.SYS_IOCTL, fd, req, arg); errno != 0 {
		return errno
	}
	return nil
}

func runFramebuffer(cfg Config, run func(Display) error) error {
	f, err := os.OpenFile(cfg.Framebuffer, os.O_RDWR, 0)
	if err != nil {
		return err
	}
	defer f.Close()

	var v fbVarScreenInfo
	var fix fbFixScreenInfo
	if err := ioctl(f.Fd(), fbioGetVScreenInfo, unsafe.Pointer(&v)); err != nil {
		return fmt.Errorf("read screen info: %w", err)
	}
	if err := ioctl(f.Fd(), fbioGetFScreenInfo, unsafe.Pointer(&fix)); err != nil {
		return fmt.Errorf("read screen info: %w", err)
	}
	if v.BitsPerPixel != 16 && v.BitsPerPixel != 32 {
		return fmt.Errorf("unsupported framebuffer depth %d bpp", v.BitsPerPixel)
	}
	size := int(fix.LineLength) * int(v.YRes)
	mem, err := syscall.Mmap(int(f.Fd()), 0, size, syscall.PROT_READ|syscall.PROT_WRITE, syscall.MAP_SHARED)
	if err != nil {
		return fmt.Errorf("map framebuffer: %w", err)
	}
	defer syscall.Munmap(mem)
	log.Printf("framebuffer %dx%d, %d bpp", v.XRes, v.YRes, v.BitsPerPixel)

	// Stop the console's text and cursor drawing over the photos
	if tty, err := os.OpenFile(cfg.Console, os.O_RDWR, 0); err == nil {
		defer tty.Close()
		// Restored on exit; SIGTERM cancels the slideshow so this still runs
		if ioctlInt(tty.Fd(), kdSetMode, kdGraphics) == nil {
			defer ioctlInt(tty.Fd(), kdSetMode, kdText)
		}
	}

	return run(&fbDisplay{
		mem:    mem,
		w:      int(v.XRes),
		h:      int(v.YRes),
		stride: int(fix.LineLength),
		bpp:    int(v.BitsPerPixel),
		vinfo:  v,
	})
}

func (d *fbDisplay) Size() (int, int) { return d.w, d.h }

func (d *fbDisplay) Show(frame *image.RGBA) error {
	v := d.vinfo
	for y := 0; y < d.h; y++ {
		src := frame.Pix[y*frame.Stride : y*frame.Stride+d.w*4]
		row := d.mem[y*d.stride:]
		switch {
		case d.bpp == 32 && v.Red.Offset == 16 && v.Green.Offset == 8 && v.Blue.Offset == 0:
			// XRGB8888, the usual Pi format: bytes are B, G, R, X
			row = row[:len(src)]
			for i := 0; i+3 < len(src); i += 4 {
				row[i], row[i+1], row[i+2], row[i+3] = src[i+2], src[i+1], src[i], 0xff
			}
		case d.bpp == 32:
			for x := 0; x < d.w; x++ {
				s := src[x*4 : x*4+4]
				px := pack(s, v)
				o := row[x*4 : x*4+4]
				o[0], o[1], o[2], o[3] = byte(px), byte(px>>8), byte(px>>16), byte(px>>24)
			}
		default: // 16 bpp, usually RGB565
			for x := 0; x < d.w; x++ {
				px := pack(src[x*4:x*4+4], v)
				row[x*2], row[x*2+1] = byte(px), byte(px>>8)
			}
		}
	}
	return nil
}

// pack converts an RGBA pixel to the framebuffer's channel layout
func pack(s []byte, v fbVarScreenInfo) uint32 {
	ch := func(c byte, f fbBitfield) uint32 {
		return uint32(c) >> (8 - f.Length) << f.Offset
	}
	return ch(s[0], v.Red) | ch(s[1], v.Green) | ch(s[2], v.Blue)
}
