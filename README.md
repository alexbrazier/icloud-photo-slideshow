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
| `album` | iCloud shared album ID | `?album=B0abc123` |
| `lat` | decimal degrees | `?lat=37.7749` |
| `lng` | decimal degrees | `?lng=-122.4194` |

Example combining several:

```
http://localhost:8085/?album=B0abc123&transition=20&clock=true&orientation=landscape
```
