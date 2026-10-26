#!/bin/sh
# Installs icloud-frame as a boot service on Raspberry Pi OS Lite.
# Run on the Pi from a folder holding this script, icloud-frame.service,
# icloud-frame.conf.example and the icloud-frame-linux-* binaries:
#   sudo ./install.sh
set -eu
cd "$(dirname "$0")"

if [ "$(id -u)" -ne 0 ]; then
  echo "Run with sudo" >&2
  exit 1
fi

case "$(uname -m)" in
  aarch64) arch=arm64 ;;
  armv7l | armv8l) arch=armv7 ;;
  *) echo "Unsupported architecture $(uname -m)" >&2; exit 1 ;;
esac

install -m 755 "icloud-frame-linux-$arch" /usr/local/bin/icloud-frame
install -m 644 icloud-frame.service /etc/systemd/system/icloud-frame.service
if [ ! -f /etc/icloud-frame.conf ]; then
  install -m 600 icloud-frame.conf.example /etc/icloud-frame.conf
  [ -n "${ICLOUD_ALBUM_ID:-}" ] &&
    sed -i "s|^ICLOUD_ALBUM_ID=.*|ICLOUD_ALBUM_ID=$ICLOUD_ALBUM_ID|" /etc/icloud-frame.conf
fi

# The frame owns the screen: no login prompt on it, and no console blanking
systemctl disable --now getty@tty1.service 2>/dev/null || true
cmdline=/boot/firmware/cmdline.txt
[ -f "$cmdline" ] || cmdline=/boot/cmdline.txt
if [ -f "$cmdline" ] && ! grep -q consoleblank= "$cmdline"; then
  sed -i '1 s/$/ consoleblank=0/' "$cmdline"
fi

systemctl daemon-reload
systemctl enable icloud-frame.service
if grep -q '^ICLOUD_ALBUM_ID=.' /etc/icloud-frame.conf; then
  systemctl restart icloud-frame.service
  echo "Running. Logs: journalctl -u icloud-frame -f"
else
  echo "Set ICLOUD_ALBUM_ID in /etc/icloud-frame.conf, then: sudo systemctl restart icloud-frame"
fi
