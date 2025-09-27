/* eslint-disable @typescript-eslint/no-explicit-any */
import { NextRequest, NextResponse } from "next/server";

const imagesCache: Record<
  string,
  Array<{ url: string; orientation: string; url_expiry: string }>
> = {};
const cacheTimestamp: Record<string, number> = {};
const CACHE_TTL = 60 * 60 * 1000; // 1 hour

function areUrlsStillValid(images: Array<{ url_expiry: string }>): boolean {
  if (!images || images.length === 0) return false;
  const oneHourFromNow = Date.now() + CACHE_TTL;

  // Check only the first image as they all have the same expiry
  const expiryTime = new Date(images[0].url_expiry).getTime();
  return Number.isFinite(expiryTime) && expiryTime > oneHourFromNow;
}

async function fetchApple(albumId: string, endpoint: string, data: any) {
  if (!albumId) throw new Error("No album ID configured");
  const url = `https://p153-sharedstreams.icloud.com/${albumId}/sharedstreams/${endpoint}`;
  const res = await fetch(url, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(data),
  });
  if (!res.ok) throw new Error(await res.text());
  return res.json();
}

async function fetchAndCacheImages(albumId: string) {
  const webstream = await fetchApple(albumId, "webstream", {
    streamCtag: null,
  });

  const photos = Array.isArray(webstream?.photos) ? webstream.photos : [];
  const photoData = photos
    .map((p: any) => {
      const derivatives = p?.derivatives as
        | Record<
            string,
            {
              checksum: string;
              height: string | number;
              width: string | number;
            }
          >
        | undefined as
        | Record<
            string,
            {
              checksum: string;
              height: string | number;
              width: string | number;
            }
          >
        | undefined;

      if (!derivatives || Object.keys(derivatives).length === 0) return null;

      // Pick the largest derivative by pixel count (width * height)
      let best: {
        checksum: string;
        height: string | number;
        width: string | number;
      } | null = null;
      let maxPixels = -1;
      for (const d of Object.values(derivatives)) {
        const w = Number((d as any).width) || 0;
        const h = Number((d as any).height) || 0;
        const px = w * h;
        if (px > maxPixels) {
          maxPixels = px;
          best = d;
        }
      }

      if (!best) return null;

      const w = Number(best.width) || 0;
      const h = Number(best.height) || 0;
      const orientation = w >= h ? "landscape" : "portrait";

      return {
        checksum: best.checksum,
        photoGuid: p.photoGuid,
        orientation,
      };
    })
    .filter(Boolean) as Array<{
    checksum: string;
    photoGuid: string;
    orientation: string;
  }>;

  if (photoData.length === 0) {
    imagesCache[albumId] = [];
    cacheTimestamp[albumId] = Date.now();
    return [];
  }

  const webasseturls: {
    items: Record<
      string,
      { url_location: string; url_path: string; url_expiry: string }
    >;
  } = await fetchApple(albumId, "webasseturls", {
    photoGuids: photoData.map((p: any) => p.photoGuid),
  });

  const items = webasseturls?.items ?? {};
  const images = photoData
    .map((p) => {
      const item = items[p.checksum];
      if (!item) return null;
      return {
        url: `https://${item.url_location}${item.url_path}`,
        orientation: p.orientation,
        url_expiry: item.url_expiry,
      };
    })
    .filter(Boolean) as Array<{
    url: string;
    orientation: string;
    url_expiry: string;
  }>;

  imagesCache[albumId] = images;
  cacheTimestamp[albumId] = Date.now();
  return images;
}

export async function GET(req: NextRequest) {
  try {
    const now = Date.now();
    let albumId = req.nextUrl.searchParams.get("albumId");
    if (!albumId) {
      albumId = process.env.ICLOUD_ALBUM_ID ?? null;
    }
    if (!albumId) {
      return NextResponse.json(
        { error: "albumId is required (query or env)" },
        { status: 400 }
      );
    }

    const cacheExists = imagesCache[albumId];
    const cacheTimestampValid =
      cacheExists && now - cacheTimestamp[albumId] < CACHE_TTL;
    const urlsStillValid =
      cacheExists && areUrlsStillValid(imagesCache[albumId]);

    if (cacheExists && cacheTimestampValid && urlsStillValid) {
      // Cache is completely valid, return it
      return NextResponse.json(imagesCache[albumId]);
    } else if (cacheExists && !cacheTimestampValid && urlsStillValid) {
      // Cache timestamp is old but URLs are still valid, refresh in background but return existing cache
      fetchAndCacheImages(albumId).catch(() => {});
      return NextResponse.json(imagesCache[albumId]);
    } else {
      // Either no cache exists, URLs are expiring soon, or both are invalid - fetch fresh data
      return NextResponse.json(await fetchAndCacheImages(albumId));
    }
  } catch (err: any) {
    return NextResponse.json(
      { error: "Failed to fetch images", details: err.message || err },
      { status: 502 }
    );
  }
}
