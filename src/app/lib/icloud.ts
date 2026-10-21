/* eslint-disable @typescript-eslint/no-explicit-any */

export type Photo = {
  checksum: string;
  photoGuid: string;
  orientation: "landscape" | "portrait";
};

type SignedUrl = { url: string; expiry: number };

const PHOTO_LIST_TTL = 60 * 60 * 1000; // 1 hour
// Apple currently signs asset URLs for ~20 minutes; refresh before they lapse
const URL_EXPIRY_MARGIN = 2 * 60 * 1000;

const photoLists: Record<
  string,
  { photos: Photo[]; timestamp: number; pending?: Promise<Photo[]> }
> = {};
const signedUrls: Record<
  string,
  { urls: Record<string, SignedUrl>; pending?: Promise<void> }
> = {};

export function resolveAlbumId(albumId: string | null): string | null {
  return albumId || process.env.ICLOUD_ALBUM_ID || null;
}

async function fetchApple(albumId: string, endpoint: string, data: any) {
  const url = `https://p153-sharedstreams.icloud.com/${albumId}/sharedstreams/${endpoint}`;
  const res = await fetch(url, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(data),
  });
  if (!res.ok) throw new Error(await res.text());
  return res.json();
}

async function fetchPhotoList(albumId: string): Promise<Photo[]> {
  const webstream = await fetchApple(albumId, "webstream", {
    streamCtag: null,
  });

  const photos = Array.isArray(webstream?.photos) ? webstream.photos : [];
  return photos
    .map((p: any): Photo | null => {
      const derivatives = p?.derivatives as
        | Record<
            string,
            { checksum: string; height: string | number; width: string | number }
          >
        | undefined;
      if (!derivatives) return null;

      // Pick the largest derivative by pixel count (width * height)
      let best: (typeof derivatives)[string] | null = null;
      let maxPixels = -1;
      for (const d of Object.values(derivatives)) {
        const px = (Number(d.width) || 0) * (Number(d.height) || 0);
        if (px > maxPixels) {
          maxPixels = px;
          best = d;
        }
      }
      if (!best) return null;

      const w = Number(best.width) || 0;
      const h = Number(best.height) || 0;
      return {
        checksum: best.checksum,
        photoGuid: p.photoGuid,
        orientation: w >= h ? "landscape" : "portrait",
      };
    })
    .filter(Boolean) as Photo[];
}

// Returns the cached photo list, refreshing in the background once stale.
// The webstream call is slow (~10s+), so only block when nothing is cached.
export async function getPhotos(albumId: string): Promise<Photo[]> {
  const entry = photoLists[albumId];
  const refresh = () => {
    if (!photoLists[albumId]?.pending) {
      const pending = fetchPhotoList(albumId)
        .then((photos) => {
          photoLists[albumId] = { photos, timestamp: Date.now() };
          return photos;
        })
        .finally(() => {
          if (photoLists[albumId]?.pending === pending)
            delete photoLists[albumId].pending;
        });
      photoLists[albumId] = {
        photos: entry?.photos ?? [],
        timestamp: entry?.timestamp ?? 0,
        pending,
      };
    }
    return photoLists[albumId].pending!;
  };

  if (!entry?.timestamp) return refresh();
  if (Date.now() - entry.timestamp > PHOTO_LIST_TTL) refresh().catch(() => {});
  return entry.photos;
}

async function refreshSignedUrls(albumId: string) {
  const photos = await getPhotos(albumId);
  const res: {
    items?: Record<
      string,
      { url_location: string; url_path: string; url_expiry: string }
    >;
  } = await fetchApple(albumId, "webasseturls", {
    photoGuids: photos.map((p) => p.photoGuid),
  });

  const urls: Record<string, SignedUrl> = {};
  for (const [checksum, item] of Object.entries(res?.items ?? {})) {
    urls[checksum] = {
      url: `https://${item.url_location}${item.url_path}`,
      expiry: new Date(item.url_expiry).getTime() || 0,
    };
  }
  signedUrls[albumId] = { urls };
}

export async function getSignedUrl(
  albumId: string,
  checksum: string,
  forceRefresh = false
): Promise<string | null> {
  const cached = signedUrls[albumId]?.urls[checksum];
  if (
    !forceRefresh &&
    cached &&
    cached.expiry - URL_EXPIRY_MARGIN > Date.now()
  ) {
    return cached.url;
  }

  // Share a single in-flight refresh between concurrent image requests
  const entry = (signedUrls[albumId] ??= { urls: {} });
  if (!entry.pending) {
    entry.pending = refreshSignedUrls(albumId).finally(() => {
      delete entry.pending;
    });
  }
  await entry.pending;
  return signedUrls[albumId]?.urls[checksum]?.url ?? null;
}
