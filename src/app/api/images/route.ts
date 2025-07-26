/* eslint-disable @typescript-eslint/no-explicit-any */
import { NextRequest, NextResponse } from "next/server";

const imagesCache: Record<
  string,
  Array<{ url: string; orientation: string; url_expiry: string }>
> = {};
const cacheTimestamp: Record<string, number> = {};
const CACHE_TTL = 60 * 60 * 1000; // 1 hour

function areUrlsStillValid(images: Array<{ url_expiry: string }>): boolean {
  const now = Date.now();
  const oneHourFromNow = now + CACHE_TTL;

  // Check only the first image as they all have the same expiry
  const expiryTime = new Date(images[0].url_expiry).getTime();
  return expiryTime > oneHourFromNow;
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
  const photoData = webstream.photos.map((p: any) => {
    const derivatives = p.derivatives as Record<
      string,
      {
        checksum: string;
        height: string;
        width: string;
      }
    >;
    const highestRes = Object.keys(derivatives)
      .map(Number)
      .sort((a, b) => a - b);
    const highestResData = derivatives[highestRes[highestRes.length - 1]];

    // Determine orientation from width/height
    const width = parseInt(highestResData.width);
    const height = parseInt(highestResData.height);
    const orientation = width > height ? "landscape" : "portrait";

    return {
      checksum: highestResData.checksum,
      photoGuid: p.photoGuid,
      orientation,
    };
  });

  const webasseturls: {
    items: Record<
      string,
      { url_location: string; url_path: string; url_expiry: string }
    >;
  } = await fetchApple(albumId, "webasseturls", {
    photoGuids: photoData.map((p: any) => p.photoGuid),
  });

  const images = photoData.map((p: any) => ({
    url: `https://${webasseturls.items[p.checksum].url_location}${
      webasseturls.items[p.checksum].url_path
    }`,
    orientation: p.orientation,
    url_expiry: webasseturls.items[p.checksum].url_expiry,
  }));

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
    const cacheValid = cacheTimestampValid && urlsStillValid;

    if (cacheExists && cacheValid) {
      // Cache is valid, return it
      return NextResponse.json(imagesCache[albumId]);
    } else if (cacheExists && cacheTimestampValid && !urlsStillValid) {
      // URLs are expiring soon, fetch fresh data synchronously
      const images = await fetchAndCacheImages(albumId);
      return NextResponse.json(images);
    } else if (cacheExists && !cacheTimestampValid) {
      // Cache timestamp is old, refresh in background but return existing cache
      fetchAndCacheImages(albumId).catch(() => {});
      return NextResponse.json(imagesCache[albumId]);
    } else {
      // No cache exists, fetch fresh data
      const images = await fetchAndCacheImages(albumId);
      return NextResponse.json(images);
    }
  } catch (err: any) {
    return NextResponse.json(
      { error: "Failed to fetch images", details: err.message || err },
      { status: 502 }
    );
  }
}
