# iCloud Shared Album Slideshow

This is an app that displays a fullscreen slideshow of images from an iCloud Shared Album. It includes settings for transition time and image orientation filtering.

## Features

- Fullscreen, animated slideshow
- Settings modal (transition time, orientation filter)
- Keyboard and mouse navigation
- Modern React (hooks, functional components)

## Getting Started

1. Install dependencies:
   ```bash
   npm install
   ```
2. Start the development server:
   ```bash
   npm run dev
   ```
3. Open [http://localhost:8085](http://localhost:8085) in your browser.

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
