# icloud-frame

A single static binary that shows an iCloud shared album as a slideshow on a
Raspberry Pi, drawing straight to the screen through `/dev/fb0`. It needs no
desktop, browser or slideshow server, and runs comfortably on a Pi Zero 2 W
(about 50MB of RAM).

It talks to Apple's shared album endpoints itself, the same way the web app's
server does (`server/icloud.ts`), and picks the smallest photo size that still
fills the screen rather than the full-resolution original.

Behaviour matches the web app: photos are shuffled, reshuffled after each full
pass, and the album is reloaded hourly. Photos fill the screen when their
orientation matches it and are letterboxed when it doesn't, with a 1 second
crossfade. There's no clock or weather yet.

## Preview on a Mac

```bash
make preview                                  # 1280x800 window, album from ../.env
make preview ARGS="-size 800x480 -interval 10s"
make test
```

## Install on a Pi

1. Flash **Raspberry Pi OS Lite** with Raspberry Pi Imager, setting up Wi-Fi and SSH.
2. Build and copy the bundle over:
   ```bash
   make dist
   scp -r dist pi@raspberrypi.local:icloud-frame
   ```
3. On the Pi:
   ```bash
   sudo ICLOUD_ALBUM_ID=B0abc123 ./icloud-frame/install.sh
   ```

`install.sh` installs the binary and a systemd service that starts at boot,
writes `/etc/icloud-frame.conf`, and turns off the login prompt and console
blanking on the screen. Logs: `journalctl -u icloud-frame -f`.

## Releases

GitHub Actions (`.github/workflows/pi-release.yml`) runs the tests and publishes `dist/` (both
binaries and the install files) as a release tagged `pi-v<version>`. Start it from **Actions → Pi
release → Run workflow** with a version number higher than the last, or:

```bash
gh workflow run pi-release.yml -f version=1
```

## Settings

Set in `/etc/icloud-frame.conf` on the Pi (then `sudo systemctl restart
icloud-frame`), or as flags when running by hand (`icloud-frame -help`).

| Variable | Flag | Default | |
| --- | --- | --- | --- |
| `ICLOUD_ALBUM_ID` | `-album` | | Album ID or full share link (older `#B0…` links or iOS 27's `photos.icloud.com/shared/album/…`) |
| `FRAME_INTERVAL` | `-interval` | `60` | Seconds per photo, or a duration like `5m` |
| `FRAME_FADE` | `-fade` | `1` | Crossfade seconds, `0` for a hard cut |
| `FRAME_ORIENTATION` | `-orientation` | `all` | `all`, `landscape` or `portrait` |
| `FRAME_ROTATE` | `-rotate` | `0` | Turn the picture clockwise for a rotated panel: `90`, `180`, `270` |
| `FRAME_OUTPUT` | `-output` | `fb` (`window` on Mac) | `fb`, `window` or `png` |
| `FRAME_SIZE` | `-size` | `1280x800` | Size for `window` and `png` output |

`-output png -out frames -count 5` writes frames to files instead, for
checking headless (e.g. in Docker).

## Emulating a Pi Zero 2 W

```bash
brew install qemu mtools
make qemu
```

This boots the latest Raspberry Pi OS Lite in QEMU's Pi 3A+, which has the
Zero 2 W's chip and 512MB of RAM. It installs `dist/` with `install.sh` on a
first, headless boot, then boots again in a window where the service should
start by itself and show the album. `FRAME_SIZE=800x480 make qemu` sets the
panel size. The serial logs are in `qemu/cache/`.

**Unfinished:** in testing so far the image didn't boot (no serial output), so
treat this as a starting point rather than a working check.

It's meant to check the Pi side works: the boot service, framebuffer drawing and
fetching from Apple. It doesn't show real speed, so check timings in the logs
on an actual Pi.
