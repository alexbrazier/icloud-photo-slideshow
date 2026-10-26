#!/bin/sh
# Runs once on the emulated Pi's first boot (via systemd.run), as root
set -ux
dir=/boot/firmware/frame

# Emulator-only login: pi / raspberry. userconf also turns off the
# first-boot "create a user" wizard, which would otherwise hold tty1.
hash=$(openssl passwd -6 raspberry)
if [ -x /usr/lib/userconf-pi/userconf ]; then
  /usr/lib/userconf-pi/userconf pi "$hash"
else
  useradd -m -G sudo,video pi && usermod -p "$hash" pi
fi

set -a
. "$dir/album.env"
set +a
rm -f "$dir/album.env"

if "$dir/install.sh"; then
  echo "icloud-frame installed" > /dev/ttyAMA0
fi
sync
