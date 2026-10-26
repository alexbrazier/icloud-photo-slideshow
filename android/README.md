# iCloud Frame for Android photo frames

A slideshow app for cheap Android photo frames (Frameo and similar) that reads an iCloud shared
album directly, with no server. It replaces the frame's own app as the Home screen, so the frame
boots straight into your photos.

**Setting up a frame?** Follow [SETUP.md](SETUP.md): checking the frame, installing, adding your
album from an iPhone, updates, security, and recovering if something goes wrong.

- **Offline:** photos are saved as screen-sized copies, so the frame keeps working (with the
  photos it has) when Wi-Fi or iCloud is down, including after a reboot.
- **Albums:** save several iCloud shared albums (Public Website on), older ones and iOS 27's
  (`photos.icloud.com/shared/album/…` links, see `CloudKit.java`), each shown by its iCloud name
  and newest photo. Tick one to switch to it, or several to mix them. Albums that are off keep
  their saved photos, so switching back is instant; removing one deletes its photos straight away
  (except any that are also in another album).
- **Fit:** separate choices for photos the same shape as the screen (fill, fill unless it would
  crop a lot, or show whole) and the other shape (show whole or fill), with a blurred copy of the
  photo, black, grey, white or any colour behind photos that don't fill the screen.
- **Videos:** shown with their poster first, then played once (muted unless Play video sound is
  on) before moving on. Saved for offline use too, at up to 720p. Turn off Include videos to
  skip them.
- **Local photos:** optionally, photos and videos from SD cards, USB sticks and the frame's own
  Pictures, DCIM and Movies folders.
- **On screen:** a clock, weather (MET Norway) and optionally each photo's date and album in any
  corner, crossfades, and a splash screen until the first photo is ready.
- **Brightness:** a fixed level or Android's own, and optional dimming in the evening.
- **Screen schedule:** turns the panel off at night and back on in the morning (needs device
  admin, see below; without it the screen goes black and dim instead). Pressing the power button
  at night wakes it for 5 minutes.
- **Always in front:** it's the Home app, starts on boot, shows over the lock screen, and as
  device owner turns the lock screen off entirely.
- **Touch:** tap for Back / Pause / Next / Settings / Phone setup, swipe to change photo,
  long-press for Settings.
- **Settings:** albums, videos, timing, order, orientation filter, fit and background, captions,
  brightness, storage limit, clock, weather (type a town and it fills in the coordinates), screen
  schedule, Wi-Fi (in-app, since frames often have no navigation bar) and updates.
  When the storage limit is reached, photos only in switched-off albums go first, then the
  oldest, so new photos keep arriving.
- **Phone setup:** Phone setup shows a QR code; scanning it opens a settings page in the phone's
  browser to add, switch and remove albums and change every setting, including a town search
  for the weather location. Changes save as they're made. The page is served on the local
  network only while the QR code is on screen (it closes after 15 minutes), and every address
  includes a random 128-bit key from the QR code, so other devices on the network can't reach it.
  Phones that can't scan can type the address shown and a 6-digit PIN instead, which locks after
  five wrong tries. **Back to photos** on the page closes the setup screen. Optionally, **Keep
  phone setup available** keeps the page up with the same link, so it can be bookmarked.
- **Auto-update** from this repository's GitHub releases (see below).
- Android 6 and newer (API 23+). About 120 KB, no Google Play services needed.

It talks to Apple the same way as the web app's server (`server/icloud.ts`): the album list from
`webstream` (hourly), then a fresh download address per photo from `webasseturls`, so Apple's ~20
minute link expiry never matters. HTTPS trusts the frame's own roots **plus** roots bundled in
`res/raw/roots.pem` (Apple, DigiCert, GitHub's and MET Norway's), so frames with an old root list
still connect.

## Build

Needs a JDK (17+) and the Android SDK (build-tools 34+ and platform 34). No Gradle.

```bash
./build.sh   # → build/icloud-frame.apk
```

The first build creates a signing key at `~/.android/icloud-frame.keystore` (password in
`~/.android/icloud-frame.keystore.password`). **Back both up.** Android only installs updates
signed with the same key, so without them, frames already set up can't be updated.

## Set up a frame

See [SETUP.md](SETUP.md). In short:

```bash
adb install -r build/icloud-frame.apk
adb shell pm disable-user --user 0 net.frameo.frame                                    # the stock app
adb shell cmd package set-home-activity com.alexbrazier.icloudframe/.MainActivity      # Android 10+
adb shell dpm set-device-owner com.alexbrazier.icloudframe/.AdminReceiver              # recommended
```

then Phone setup on the frame, and paste the album link on your phone.

## Releasing updates

GitHub Actions builds, signs and publishes the app (`.github/workflows/android-release.yml`).
Start it by hand: on GitHub, **Actions → Android release → Run workflow**, pick the branch
(normally `main`) and enter the version number. Or from a terminal:

```bash
gh workflow run android-release.yml -f version=2
```

The version is a whole number and must be higher than the last release, as frames only install
a higher version than they have; the workflow checks this before building. Local builds are
version 1, so start at 2. It creates the `android-v<version>` tag on the commit it built, and a
release with `icloud-frame-<version>.apk` and its SHA-256.

**One-time setup:** add two repository secrets (Settings → Secrets and variables → Actions → New
repository secret), from the key the first local build made:

```bash
base64 -i ~/.android/icloud-frame.keystore | pbcopy        # paste as ANDROID_KEYSTORE_BASE64
pbcopy < ~/.android/icloud-frame.keystore.password         # paste as ANDROID_KEYSTORE_PASSWORD
```

The build refuses to run in CI without them (rather than make a new key, whose updates no frame
would accept), and refuses to publish anything signed by a key other than the one in
`signing-cert.sha256`, the certificate fingerprint frames already trust. If you ever change key
on purpose, update that file, but existing frames will still only accept the old key: they'd
need the app uninstalled and reinstalled over adb.

**Keeping the key safe:** anyone with the key, or with write access to this repository (who
could edit the workflow to use the secrets), can sign updates frames will install. Keep write
access to yourself, use two-factor sign-in on GitHub, and keep an offline backup of the
keystore and its password; without them, frames can never be updated again.

To build a release without Actions: `VERSION_CODE=2 VERSION_NAME=2 ./build.sh`, then create a
GitHub release tagged `android-v2` with `build/icloud-frame.apk` attached.

Frames check daily. As device owner they install updates silently; otherwise the daily check
only notes that an update is available, and Settings → Check for updates now installs it (Android
asks to confirm). An update installs only if its package name and signing key match the
installed app.

## Security

Most of the risk is ADB, not the app: with it on, anyone with a cable (or, with wireless ADB on
port 5555, anyone on the network) can control the frame, and many frames turn wireless ADB on at
every boot. [SETUP.md](SETUP.md#adb-check-it-turn-it-off-get-back-in) has the commands to check
it, turn it off, confirm it stays off after a reboot, and get back in from the frame's own screen
afterwards (no USB needed).

The app itself only connects out (Apple, MET Norway, Open-Meteo, GitHub). The phone setup page
is only served while its QR screen is open (or always, with the opt-in Keep phone setup
available setting, using a kept key) and needs the QR code's random key or a 6-digit PIN that
only works while shown and locks after five wrong tries; settings over adb need the shell's `DUMP` permission; and
updates only install if signed with the same key.

## Troubleshooting

```bash
adb logcat -s iCloudFrame        # sync, download, weather and update logs
```

- **"Loading photos…" forever:** check the album link, and that the album still has Public
  Website on. Settings → Status shows the last error.
- **Errors right after boot:** these frames have no clock battery and start in 1970 until they
  sync the time, so HTTPS fails for the first minute or so. The app retries by itself.
- **No Wi-Fi networks listed:** some frames only list networks with Location on. Use "Add by
  name" instead.
