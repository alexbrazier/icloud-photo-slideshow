import { createServer, IncomingMessage, ServerResponse } from "node:http";
import { readFile } from "node:fs/promises";
import { extname, join, normalize } from "node:path";
import {
  getPhotos,
  getSignedUrl,
  parseAlbumId,
  pickDerivative,
} from "./icloud.ts";
import { getWeather } from "./weather.ts";

const PORT = Number(process.env.PORT || 3000);
// Built UI to serve; unset in development, where Vite serves it instead
const STATIC_DIR = process.env.STATIC_DIR || "";

const MIME_TYPES: Record<string, string> = {
  ".html": "text/html; charset=utf-8",
  ".js": "text/javascript; charset=utf-8",
  ".css": "text/css; charset=utf-8",
  ".json": "application/json",
  ".svg": "image/svg+xml",
  ".ico": "image/x-icon",
  ".png": "image/png",
  ".woff2": "font/woff2",
};

// Returns null when the album ID is missing or malformed
function resolveAlbumId(albumId: string): string | null {
  return parseAlbumId(albumId || process.env.ICLOUD_ALBUM_ID || "");
}

// Screen size in device pixels, or 0 when not given (full size photos)
function parseDimension(raw: string | null): number {
  const n = Math.round(Number(raw));
  return Number.isFinite(n) && n > 0 ? Math.min(n, 16384) : 0;
}

function sendJson(res: ServerResponse, status: number, data: unknown) {
  res.writeHead(status, { "Content-Type": "application/json" });
  res.end(JSON.stringify(data));
}

async function handleImageList(
  res: ServerResponse,
  queryAlbumId: string,
  width: number,
  height: number
) {
  const albumId = resolveAlbumId(queryAlbumId);
  if (!albumId) {
    return sendJson(res, 400, {
      error: "A valid albumId is required (query or env)",
    });
  }

  // Return stable proxy URLs, each for the smallest size that fills the
  // screen. Apple's signed URLs expire, so they're resolved server side when
  // an image is requested instead.
  const photos = await getPhotos(albumId);
  // Blank means the server's default album, so leave it out
  const query = queryAlbumId
    ? `?albumId=${encodeURIComponent(queryAlbumId)}`
    : "";
  sendJson(
    res,
    200,
    photos.map((p) => ({
      url: `/api/images/${encodeURIComponent(
        pickDerivative(p, width, height).checksum
      )}${query}`,
      orientation: p.orientation,
    }))
  );
}

async function handleImage(
  res: ServerResponse,
  queryAlbumId: string,
  checksum: string
) {
  const albumId = resolveAlbumId(queryAlbumId);
  if (!albumId) {
    return sendJson(res, 400, {
      error: "A valid albumId is required (query or env)",
    });
  }

  let url = await getSignedUrl(albumId, checksum);
  let upstream = url ? await fetch(url) : null;
  // URL may have been revoked early; re-sign once and retry
  if (!upstream || !upstream.ok) {
    url = await getSignedUrl(albumId, checksum, true);
    upstream = url ? await fetch(url) : null;
  }
  if (!upstream || !upstream.ok) {
    const status = !url || upstream?.status === 404 ? 404 : 502;
    return sendJson(res, status, { error: "Image not found" });
  }

  const body = Buffer.from(await upstream.arrayBuffer());
  const contentType: string =
    upstream.headers.get("content-type") ?? "image/jpeg";
  // The checksum identifies the image content, so it can be cached forever
  res.writeHead(200, {
    "Content-Type": contentType,
    "Content-Length": String(body.length),
    "Cache-Control": "public, max-age=31536000, immutable",
  });
  res.end(body);
}

async function handleWeather(res: ServerResponse, lat: string, lon: string) {
  if (!lat || !lon) {
    return sendJson(res, 400, {
      error: "Latitude and longitude are required",
    });
  }
  try {
    sendJson(res, 200, await getWeather(lat, lon));
  } catch (err) {
    console.error("Weather API error:", err);
    sendJson(res, 500, { error: "Failed to fetch weather data" });
  }
}

async function handleStatic(res: ServerResponse, pathname: string) {
  const path = normalize(pathname === "/" ? "/index.html" : pathname);
  if (!STATIC_DIR || path.includes("..")) {
    return sendJson(res, 404, { error: "Not found" });
  }

  let body: Buffer;
  try {
    body = await readFile(join(STATIC_DIR, path));
  } catch {
    return sendJson(res, 404, { error: "Not found" });
  }
  res.writeHead(200, {
    "Content-Type": MIME_TYPES[extname(path)] ?? "application/octet-stream",
    // Vite content-hashes everything under /assets; recheck everything else
    "Cache-Control": path.startsWith("/assets/")
      ? "public, max-age=31536000, immutable"
      : "no-cache",
  });
  res.end(body);
}

async function handleRequest(req: IncomingMessage, res: ServerResponse) {
  const url = new URL(req.url ?? "/", "http://localhost");
  const params = url.searchParams;
  const parts = url.pathname.split("/");
  try {
    if (url.pathname === "/api/images") {
      return await handleImageList(
        res,
        params.get("albumId") ?? "",
        parseDimension(params.get("w")),
        parseDimension(params.get("h"))
      );
    }
    if (parts.length === 4 && parts[1] === "api" && parts[2] === "images") {
      return await handleImage(
        res,
        params.get("albumId") ?? "",
        decodeURIComponent(parts[3])
      );
    }
    if (url.pathname === "/api/weather") {
      return await handleWeather(
        res,
        params.get("lat") ?? "",
        params.get("lon") ?? ""
      );
    }
    return await handleStatic(res, url.pathname);
  } catch (err) {
    sendJson(res, 502, { error: "Request failed", details: String(err) });
  }
}

createServer((req, res) => {
  handleRequest(req, res);
}).listen(PORT, () => console.log(`Listening on port ${PORT}`));

// Warm the photo list cache on start, as Apple's webstream is slow
const defaultAlbumId = resolveAlbumId("");
if (defaultAlbumId) {
  getPhotos(defaultAlbumId)
    .then((photos) => console.log(`Cached ${photos.length} photos`))
    .catch((err) => console.error("Failed to warm photo cache:", err));
}
