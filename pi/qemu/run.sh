#!/bin/sh
# Boots Raspberry Pi OS Lite in QEMU's Pi 3A+ (the Zero 2 W's chip and 512MB
# of RAM), installs dist/ with install.sh, then shows the frame in a window.
#
# This checks the Pi side works (boot service, /dev/fb0 drawing, TLS to
# Apple), not speed: emulation timing is nothing like a real Zero 2 W.
#
#   make qemu                      first run downloads Pi OS (~500MB) to cache/
#   FRAME_SIZE=800x480 make qemu   panel resolution
#   FRAME_BPP=16 make qemu         framebuffer depth (32 or 16)
#
# Login on the serial log/console is pi / raspberry (emulator only).
set -eu
here=$(cd "$(dirname "$0")" && pwd)
pi=$(dirname "$here")
cache="$here/cache"
mkdir -p "$cache"

IMAGE_URL=${IMAGE_URL:-https://downloads.raspberrypi.com/raspios_lite_arm64_latest}
SIZE=${FRAME_SIZE:-1280x800}
BPP=${FRAME_BPP:-32}
W=${SIZE%x*}
H=${SIZE#*x}

if [ -z "${ICLOUD_ALBUM_ID:-}" ] && [ -f "$pi/../.env" ]; then
  set -a; . "$pi/../.env"; set +a
fi
if [ -z "${ICLOUD_ALBUM_ID:-}" ]; then
  echo "Set ICLOUD_ALBUM_ID (or put it in the repo's .env)" >&2
  exit 1
fi
[ -f "$pi/dist/install.sh" ] || { echo "Run make dist first" >&2; exit 1; }

base="$cache/raspios-lite-arm64.img"
if [ ! -f "$base" ]; then
  echo "Downloading Raspberry Pi OS Lite..."
  curl -fL --progress-bar -o "$base.xz" "$IMAGE_URL"
  xz -d "$base.xz"
fi

# Fresh copy each run (an instant clone on APFS), padded to a power of two
# as QEMU's SD card requires
img="$cache/frame.img"
rm -f "$img"
cp -c "$base" "$img" 2>/dev/null || cp "$base" "$img"
bytes=$(stat -f %z "$img" 2>/dev/null || stat -c %s "$img")
pow=1073741824
while [ "$pow" -lt "$bytes" ]; do pow=$((pow * 2)); done
qemu-img resize -q -f raw "$img" "$pow"

# The boot partition is FAT, so mtools can edit it without mounting.
# Its start sector is the first MBR entry's LBA field.
lba=$(od -An -tu4 -j454 -N4 "$img" | tr -d ' ')
boot="$img@@$((lba * 512))"
for f in kernel8.img bcm2710-rpi-zero-2-w.dtb; do
  mcopy -o -i "$boot" "::$f" "$cache/$f"
done
mmd -i "$boot" ::frame 2>/dev/null || true
mcopy -o -i "$boot" "$pi"/dist/* "$here/firstrun.sh" ::frame/
printf 'ICLOUD_ALBUM_ID=%s\n' "$ICLOUD_ALBUM_ID" > "$cache/album.env"
mcopy -o -i "$boot" "$cache/album.env" ::frame/album.env
rm "$cache/album.env"

append="root=/dev/mmcblk0p2 rootfstype=ext4 rootwait rw dwc_otg.lpm_enable=0 \
console=ttyAMA0,115200 consoleblank=0 \
bcm2708_fb.fbwidth=$W bcm2708_fb.fbheight=$H bcm2708_fb.fbdepth=$BPP"

qemu() {
  qemu-system-aarch64 -M raspi3ap \
    -kernel "$cache/kernel8.img" -dtb "$cache/bcm2710-rpi-zero-2-w.dtb" \
    -drive "file=$img,format=raw,if=sd" \
    -global "bcm2835-fb.xres=$W" -global "bcm2835-fb.yres=$H" \
    -device usb-net,netdev=net0 -netdev user,id=net0 \
    -no-reboot "$@"
}

# First boot runs firstrun.sh (user + install.sh) headless, then powers off
echo "Setting up the image (a few minutes)... serial log: $cache/setup.log"
qemu -display none -serial "file:$cache/setup.log" \
  -append "$append systemd.run=/boot/firmware/frame/firstrun.sh systemd.run_success_action=poweroff systemd.run_failure_action=poweroff systemd.unit=kernel-command-line.target"
grep -q "icloud-frame installed" "$cache/setup.log" || {
  echo "Setup failed, see $cache/setup.log" >&2
  exit 1
}

# Second boot is a normal boot: the service should start and draw.
# The monitor socket allows screenshots: echo "screendump x.ppm" | nc -U cache/monitor.sock
echo "Booting the frame... serial log: $cache/boot.log"
rm -f "$cache/monitor.sock"
qemu -serial "file:$cache/boot.log" \
  -monitor "unix:$cache/monitor.sock,server,nowait" \
  -append "$append console=tty1"
