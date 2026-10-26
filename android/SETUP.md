# Setting up a photo frame

A step-by-step guide to putting iCloud Frame on an Android photo frame (Frameo and similar):
checking the frame, installing, getting your album on it, and getting out of trouble. For building
the app and how it works, see [README.md](README.md).

You'll need:

- A computer with [ADB](https://developer.android.com/tools/releases/platform-tools) (`adb`).
- A USB **data** cable for the frame (many cables only carry power). Frameo frames usually take
  USB-C; some need USB-A to USB-C from the computer.
- The built `icloud-frame.apk` (`./build.sh`, or download it from a GitHub release).
- An iPhone (or Mac) with the shared album you want to show.

## 1. Check the frame

Set the frame up as normal first (language, Wi-Fi), so it's on your network and has the right
time zone. Then turn on ADB:

- **Frameo:** Settings → About → **Beta program** on → **ADB access** on. (On some models it's
  under Settings → Developer, or appears after joining the beta.)
- **Other frames:** open Android's own Settings → About → tap **Build number** seven times, then
  Settings → System → Developer options → **USB debugging**.

Plug the frame into the computer and check it's there:

```bash
adb devices                                  # should list one device; accept the prompt on the frame if asked
adb shell getprop ro.build.version.release   # Android version: 6 or newer works
adb shell getprop ro.product.model           # the model, to look up in photo-frame-dashboard if stuck
```

If `adb devices` shows nothing, try another cable or USB port: power-only cables are the usual
cause. [wesbos/photo-frame-dashboard](https://github.com/wesbos/photo-frame-dashboard) lists how to
get ADB on many models.

## 2. Install the app

```bash
adb install -r icloud-frame.apk
```

`-r` also updates an existing install. To check the installed version later:

```bash
adb shell dumpsys package com.alexbrazier.icloudframe | grep versionName
```

or on the frame: long-press the photo → Settings → **Check for updates now** shows the installed
version, and so does the phone setup page.

## 3. Turn off the frame's own app (don't uninstall it)

You don't need to uninstall Frameo, and it's better not to. **Disable** it instead, so it can't
take back the screen or update itself, and can be switched back on later:

```bash
adb shell pm list packages | grep -i -E "frameo|frame|photo|update|ota"   # find the vendor's apps
adb shell pm disable-user --user 0 net.frameo.frame                        # Frameo's app
```

Disable any vendor updater the list turns up too (names vary): an over-the-air update can bring
the stock app back. To undo, `adb shell pm enable net.frameo.frame`.

Then make iCloud Frame the Home screen:

```bash
adb shell cmd package set-home-activity com.alexbrazier.icloudframe/.MainActivity   # Android 10+
```

On Android 6–9, press the frame's Home button (or reboot), choose **iCloud Frame**, then
**Always**.

## 4. Make it the device owner (recommended)

```bash
adb shell dpm set-device-owner com.alexbrazier.icloudframe/.AdminReceiver
```

This lets the app turn the screen fully off at night, remove the lock screen, keep itself as Home
even if the vendor app returns, and install updates without asking. It only works on a frame with
no accounts (Google or otherwise). If it says there are accounts, remove them in Android's
Settings → Accounts, or skip this step and use:

```bash
adb shell dpm set-active-admin com.alexbrazier.icloudframe/.AdminReceiver   # just the night schedule
adb shell locksettings set-disabled true                                    # no lock screen
```

You can give device owner up again any time from the frame: Settings → Device → **Release device
owner**.

## 5. Add your album

The album has to be an iCloud **shared album** with **Public Website** turned on. Anyone with its
link can see the photos, so only share the link with people you'd show the album to.

1. On the frame, tap the photo (or the splash screen's button) → **Phone setup**. A QR code
   appears.
2. Scan it with the iPhone's camera and open the link. The phone must be on the same Wi-Fi.
3. In Photos on the iPhone: **Albums** → the shared album → the **People** button (the person
   icon) → turn on **Public Website** → **Share Link** → **Copy**.
4. Back on the setup page, paste it into the album box. It's added as soon as you paste.

Both kinds of link work: older albums' (`https://www.icloud.com/sharedalbum/#B0…`) and those
shared from iOS 27 or later (`https://photos.icloud.com/shared/album/…`). The newer kind also
shows the album owner's name and email address to anyone with the link, so share it carefully.

No camera, or the code won't scan? The QR screen also shows an address like `192.168.1.23:8080`
and a 6-digit PIN: type the address into the phone's browser and enter the PIN. Five wrong PINs
switch the PIN off until the setup screen is opened again.

The defaults are sensible, so the album is all you need to set: it shuffles, shows each photo for
a minute, fills the screen when a photo's shape matches it, and puts a blurred copy behind photos
that don't. Change anything else on the same page; changes save as you make them.

**Setting it up before handing it over:** you can also pass settings in over adb:

```bash
adb shell am broadcast -n com.alexbrazier.icloudframe/.ConfigReceiver \
  --es album "https://www.icloud.com/sharedalbum/#B0abc123" \
  --ez weather_show true --es weather_place London --es weather_lat 51.507 --es weather_lon -0.128 \
  --ez night_enabled true --es night_off 22:00 --es night_on 07:00
```

It prints what was saved or rejected. The keys are in `src/.../SettingsSchema.java`.

## 6. Wi-Fi and the night schedule

- **Wi-Fi:** Settings → Device → **Wi-Fi** in the app (frames often have no way back to Android's
  own settings). Set the recipient's network before gifting, or add it with "Add by name".
- **Night schedule:** Settings → Screen schedule → **Turn the screen off at night**, off at 22:00
  and on at 07:00 by default. As device owner (or admin) the screen turns fully off; otherwise it
  goes black and dim. A press of the power button at night wakes it for 5 minutes.
- **Dimming:** Settings → Display → **Dim in the evening** turns the brightness down between two
  times instead of switching off.

## 7. Updates

The app checks this repository's GitHub releases once a day.

- **As device owner** it installs a new version silently and carries on.
- **Otherwise** it only notes that an update is available; Settings → Updates → **Check for updates
  now** installs it, and Android asks to confirm.
- Settings → Updates → **Install updates automatically** turns the daily install off.
  **Update source** is the GitHub repository it checks (`owner/repo`), so a fork can ship its own
  updates.

An update only installs if it's signed with the same key as the installed app (and has the same
package name), so someone who took over the GitHub repository still couldn't push a different app
without that key. Keep `~/.android/icloud-frame.keystore` and its `.password` file backed up and
private: without them you can't update frames, and with them anyone could.

To publish an update, see [Releasing updates](README.md#releasing-updates).

## 8. Before you hand it over

- The album shows, the Wi-Fi is the recipient's, and the clock and weather are right.
- It's the device owner (or admin), and the vendor app and updaters are disabled.
- **Turn ADB off** and check it stays off after a reboot (see
  [ADB](#adb-check-it-turn-it-off-get-back-in) below), unless you'll need it again soon.
- Tell them: tap for controls, swipe to change photo, long-press for settings, and **don't factory
  reset it**: that removes the app and brings the stock app back.

## Security

- **ADB is the big one**: see [ADB: check it, turn it off, get back in](#adb-check-it-turn-it-off-get-back-in)
  below.
- **The phone setup page** only exists while its QR screen is showing (at most 15 minutes), and
  every address needs the random key in the QR code. The typed-address route needs the 6-digit
  PIN, which locks after five wrong tries.
- **Shared album links** are public to anyone who has them. The frame keeps the link in its own
  private storage, but a frame thrown away without a reset still has it and its saved photos.
  Remove the album (which deletes its saved photos straight away) or factory reset the frame
  before getting rid of it.
- **Old Android:** these frames rarely get security updates. Keep them on your home network
  rather than public Wi-Fi. The app itself only connects out, to Apple (photos), MET Norway
  (weather), Open-Meteo (the town search) and GitHub (updates), and accepts nothing from outside
  apart from the setup page while it's open.

### ADB: check it, turn it off, get back in

With ADB on, anyone with a cable can control the frame, and with wireless ADB (port 5555) so can
anything on the same network: install apps, read the saved photos and album links, change
settings. Many cheap frames make it worse by turning wireless ADB on at every boot, or by
skipping the "allow this computer?" prompt so any computer gets in. On your own network the risk
is small; on someone else's, or anything shared, turn it off before handing the frame over.

**1. See what your frame does** (swap in the frame's IP address):

```bash
adb shell getprop persist.adb.tcp.port   # 5555 means wireless ADB comes back at every boot
adb shell getprop ro.adb.secure          # 0 means no "allow this computer?" prompt: anyone gets in
nc -vz 192.168.1.50 5555                 # succeeds if wireless ADB is open right now
```

**2. Check you can get back in from the frame's screen _before_ turning anything off.** USB on
these frames is often unreliable (many USB-C ports carry power only, and USB-C to USB-C cables
often don't carry data), so plan on recovering from the touchscreen instead:

- Long-press the photo → Settings → Device → **Android settings** → **Developer options**.
- If Developer options isn't listed: About → tap **Build number** seven times, then go back.
- If Android settings doesn't open at all (some makers strip it), leave ADB on and put the frame on
  a guest network instead (step 5), as the only other ways back are a working USB cable or a
  factory reset.

**3. Turn it off.** Either switch **USB debugging** off in Developer options (or Frameo's ADB
access switch, if Frameo is still enabled), or from the computer:

```bash
adb shell settings put global adb_enabled 0     # turns ADB off completely: USB and wireless
```

On Android 11+, also turn off Developer options → **Wireless debugging** if you used it.

Gentler options, if you'd rather keep USB ADB:

```bash
adb usb                                         # wireless off, USB stays on (until next boot)
adb shell setprop persist.adb.tcp.port ""       # stop wireless coming back at boot (often refused without root)
```

**4. Reboot the frame and check it stayed off:**

```bash
adb connect 192.168.1.50:5555     # should fail
nc -vz 192.168.1.50 5555          # should fail
```

If it's back after a reboot, the maker's firmware turns it on at boot. Turning it off from
Developer options after each restart works, but the frame is open until you do; a guest network
(step 5) is the reliable fix on those frames.

**5. Middle ground:** if you want to keep remote access for fixing things, put the frame on a
guest or IoT network that can't reach your other devices (and they can't reach it), and prefer a
frame where `ro.adb.secure` is `1`, so a new computer has to be approved on the frame's screen.

**Getting back in later:** Developer options → **USB debugging** on, then whichever applies:

- **The frame forces 5555 at boot:** turning USB debugging back on brings wireless ADB back too,
  so `adb connect <ip>:5555` works without a cable.
- **Android 11+:** Developer options → **Wireless debugging** → **Pair device with pairing code**,
  then `adb pair <ip>:<pairing port>` (enter the code) and `adb connect <ip>:<port>`. No cable.
- **Otherwise:** a USB cable once, to run `adb tcpip 5555`. If the computer doesn't see the frame,
  try a USB-A to USB-C cable, another port, or a USB hub; those fix most "not detected" problems.
- **Frameo:** you can also re-enable the Frameo app (Android settings → Apps) and use its ADB
  access switch.
- **Nothing works:** factory reset (below) brings the stock app and its ADB switch back.

## Getting back in if something goes wrong

**If you turned off ADB:** see [Getting back in later](#adb-check-it-turn-it-off-get-back-in) above.

**If the app is stuck or crashing:**

```bash
adb logcat -s iCloudFrame                                     # what it's doing
adb shell am force-stop com.alexbrazier.icloudframe           # restart it
adb shell pm clear com.alexbrazier.icloudframe                # wipe its settings and saved photos
```

**To go back to the stock app:** Settings → Device → **Release device owner** (if it is one),
then:

```bash
adb shell pm enable net.frameo.frame
adb uninstall com.alexbrazier.icloudframe
```

or Settings → Device → **Choose Home app** to pick the stock launcher without uninstalling.

**Factory reset** (last resort): it erases everything and brings the frame back as it came,
including the stock app; you'll need to turn ADB on and start from step 1 again.

- From Android's settings: long-press the photo → Settings → Device → Android settings → System →
  Reset options → **Erase all data**.
- Over ADB: `adb reboot recovery`; if the frame has a recovery menu, choose **Wipe
  data/factory reset** with its buttons.
- If the frame won't start at all: most have a recovery mode reached by holding the power button
  together with a reset pinhole or volume button while switching on; check the model's manual.

## Troubleshooting

- **"Loading photos…" for ages:** check the album still has Public Website on, and look at
  Settings → Status or the setup page for the error.
- **Errors just after switching on:** these frames have no clock battery and start in 1970 until
  they get the time from the network, so downloads fail for the first minute. It retries by
  itself, and the night schedule waits for the real time too.
- **The phone can't open the setup page:** the phone and frame must be on the same Wi-Fi (not a
  guest network, which often keeps devices apart), and the setup screen must still be showing.
- **No Wi-Fi networks listed:** some frames only list networks with Location on; use "Add by name".
- **Photos look too cropped or too small:** Settings → Display, the "same shape" and "other
  shape" options.
