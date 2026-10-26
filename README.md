# iCloud Shared Album Slideshow

This is an app that displays a fullscreen slideshow of images from an iCloud Shared Album. It includes settings for transition time and image orientation filtering.

## Features

- Fullscreen, animated slideshow
- Settings modal (transition time, orientation filter)
- Keyboard and mouse navigation
- Optional clock and weather widgets

## How it works

- **UI** (`src/`): React, built with Vite into static files.
- **Server** (`server/`): a small `node:http` server that fetches the album from
  iCloud, re-signs Apple's image URLs (they expire after ~20 minutes), proxies
  images with long-lived cache headers, provides weather, and serves the UI.
  In production it's compiled to a static native binary with
  [scriptc](https://github.com/vercel-labs/scriptc), so the Docker image has no
  Node.js and is ~2.5MB. In development it runs on Node directly.

scriptc compiles a subset of TypeScript and Node APIs, so check new server
code still compiles fully with `npm run coverage:server`.

## Getting Started

1. Install dependencies:
   ```bash
   npm install
   ```
2. Optionally set a default album in `.env`:
   ```bash
   ICLOUD_ALBUM_ID=B0abc123
   ```

   This can be the album's ID or its whole link. Albums shared from iOS 27 and
   later have links like `https://photos.icloud.com/shared/album/0AbCdEfG…/`;
   those work too (`server/cloudkit.ts`).
3. Start the dev servers (Vite UI plus the API server on Node, with reload):
   ```bash
   npm run dev
   ```
4. Open [http://localhost:8085](http://localhost:8085) in your browser.

| Script | Does |
| --- | --- |
| `npm run dev` | Vite UI on :8085, API server on :8086 |
| `npm run build` | Builds the UI to `dist/ui` and the native server to `dist/server` |
| `npm start` | Runs the built native server, serving `dist/ui` (set `ICLOUD_ALBUM_ID` in the environment) |
| `npm run lint` / `npm run typecheck` | ESLint / TypeScript checks |
| `npm run coverage:server` | Checks the server compiles fully with scriptc |

## Docker

```bash
docker run -p 8085:3000 -e ICLOUD_ALBUM_ID=B0abc123 ghcr.io/alexbrazier/icloud-photo-slideshow
```

The image is built `FROM scratch` and contains only the server binary, the UI
files and CA certificates, for `linux/amd64` and `linux/arm64`.

## Raspberry Pi frame

[`pi/`](pi/README.md) is a standalone Go binary that shows the album straight
on a Raspberry Pi's screen, with no browser or server, for picture frames
built on a Pi Zero 2 W.

## Android photo frame

[`android/`](android/README.md) is an app for cheap Android photo frames
(Frameo and similar) that reads the album directly, with no server, and keeps
working offline. [`android/SETUP.md`](android/SETUP.md) walks through setting
up a frame.

## Configuration via query params

Every setting can be set through the URL query string. Query params take
precedence over previously saved settings, so they're handy for kiosk setups
or sharing a preconfigured link.

| Param | Values | Example |
| --- | --- | --- |
| `transition` | seconds (> 0) | `?transition=30` |
| `orientation` | `all`, `landscape`, `portrait` | `?orientation=landscape` |
| `timer` | `true`/`false` (`1`/`0`, `on`/`off`, `yes`/`no`, or bare) | `?timer` |
| `weather` | `true`/`false` | `?weather=1` |
| `clock` | `true`/`false` | `?clock=on` |
| `album` | iCloud shared album ID or link (both the older `icloud.com/sharedalbum/#B0…` and iOS 27's `photos.icloud.com/shared/album/…`) | `?album=B0abc123` |
| `lat` | decimal degrees | `?lat=37.7749` |
| `lng` | decimal degrees | `?lng=-122.4194` |

Example combining several:

```
http://localhost:8085/?album=B0abc123&transition=20&clock=true&orientation=landscape
```
