export type Photo = {
  checksum: string;
  photoGuid: string;
  orientation: "landscape" | "portrait";
};

type SignedUrl = { url: string; expiry: number };

type Derivative = {
  checksum: string;
  width: string | number;
  height: string | number;
};
type Webstream = {
  photos?: { photoGuid: string; derivatives?: Record<string, Derivative> }[];
};
type AssetUrls = {
  items?: Record<
    string,
    { url_location: string; url_path: string; url_expiry: string }
  >;
};

const PHOTO_LIST_TTL = 60 * 60 * 1000; // 1 hour
// Apple currently signs asset URLs for ~20 minutes; refresh before they lapse
const URL_EXPIRY_MARGIN = 2 * 60 * 1000;

const photoLists = new Map<string, { photos: Photo[]; timestamp: number }>();
const photoListsPending = new Map<string, Promise<Photo[]>>();
const signedUrls = new Map<string, Map<string, SignedUrl>>();
const signedUrlsPending = new Map<string, Promise<void>>();

async function fetchApple(
  albumId: string,
  endpoint: string,
  body: unknown
): Promise<unknown> {
  const url = `https://p153-sharedstreams.icloud.com/${albumId}/sharedstreams/${endpoint}`;
  const res = await fetch(url, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(body),
  });
  if (!res.ok) throw new Error(await res.text());
  return res.json();
}

async function fetchPhotoList(albumId: string): Promise<Photo[]> {
  const webstream = (await fetchApple(albumId, "webstream", {
    streamCtag: null,
  })) as Webstream;

  const photos: Photo[] = [];
  for (const p of webstream.photos ?? []) {
    // Pick the largest derivative by pixel count (width * height)
    let best: Derivative | null = null;
    let maxPixels = -1;
    const derivatives = p.derivatives ?? {};
    for (const key of Object.keys(derivatives)) {
      const d = derivatives[key];
      const px = (Number(d.width) || 0) * (Number(d.height) || 0);
      if (px > maxPixels) {
        maxPixels = px;
        best = d;
      }
    }
    if (!best) continue;

    photos.push({
      checksum: best.checksum,
      photoGuid: p.photoGuid,
      orientation:
        Number(best.width) >= Number(best.height) ? "landscape" : "portrait",
    });
  }
  return photos;
}

// Returns the cached photo list, refreshing in the background once stale.
// The webstream call is slow (~10s+), so only block when nothing is cached.
export function getPhotos(albumId: string): Promise<Photo[]> {
  const refresh = () => {
    let pending = photoListsPending.get(albumId);
    if (!pending) {
      pending = fetchPhotoList(albumId)
        .then((photos) => {
          photoLists.set(albumId, { photos, timestamp: Date.now() });
          return photos;
        })
        .finally(() => photoListsPending.delete(albumId));
      photoListsPending.set(albumId, pending);
    }
    return pending;
  };

  const entry = photoLists.get(albumId);
  if (!entry) return refresh();
  if (Date.now() - entry.timestamp > PHOTO_LIST_TTL) refresh().catch(() => {});
  return Promise.resolve(entry.photos);
}

async function refreshSignedUrls(albumId: string) {
  const photos = await getPhotos(albumId);
  const res = (await fetchApple(albumId, "webasseturls", {
    photoGuids: photos.map((p) => p.photoGuid),
  })) as AssetUrls;

  const urls = new Map<string, SignedUrl>();
  const items = res.items ?? {};
  for (const checksum of Object.keys(items)) {
    const item = items[checksum];
    urls.set(checksum, {
      url: `https://${item.url_location}${item.url_path}`,
      expiry: new Date(item.url_expiry).getTime() || 0,
    });
  }
  signedUrls.set(albumId, urls);
}

export async function getSignedUrl(
  albumId: string,
  checksum: string,
  forceRefresh = false
): Promise<string | null> {
  const cached = signedUrls.get(albumId)?.get(checksum);
  if (
    !forceRefresh &&
    cached &&
    cached.expiry - URL_EXPIRY_MARGIN > Date.now()
  ) {
    return cached.url;
  }

  // Share a single in-flight refresh between concurrent image requests
  let pending = signedUrlsPending.get(albumId);
  if (!pending) {
    pending = refreshSignedUrls(albumId).finally(() =>
      signedUrlsPending.delete(albumId)
    );
    signedUrlsPending.set(albumId, pending);
  }
  await pending;
  return signedUrls.get(albumId)?.get(checksum)?.url ?? null;
}
