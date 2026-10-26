import {
  fetchCloudKitPhotos,
  isCloudKitAlbum,
  signCloudKitPhoto,
} from "./cloudkit.ts";

export type Derivative = {
  checksum: string;
  width: number;
  height: number;
  // The CloudKit record field it's in (iOS 27 albums only)
  field?: string;
};

export type Photo = {
  photoGuid: string;
  derivatives: Derivative[];
  orientation: "landscape" | "portrait";
};

type SignedUrl = { url: string; expiry: number };

type Webstream = {
  photos?: {
    photoGuid: string;
    mediaAssetType?: string;
    derivatives?: Record<
      string,
      { checksum: string; width: string | number; height: string | number }
    >;
  }[];
};
type AssetUrls = {
  items?: Record<
    string,
    { url_location: string; url_path: string; url_expiry: string }
  >;
};

const PHOTO_LIST_TTL = 60 * 60 * 1000; // 1 hour
// Refresh signed URLs a little before Apple says they lapse
const URL_EXPIRY_MARGIN = 2 * 60 * 1000;

// Any partition answers 330 with the album's own one
const DEFAULT_HOST = "p153-sharedstreams.icloud.com";
const HOST_PATTERN = /^p[0-9]{1,4}-sharedstreams\.icloud\.com$/;
// Newer album tokens are longer and can contain _ and -
const ALBUM_ID_PATTERN = /^[A-Za-z0-9_-]{8,200}$/;

const hosts = new Map<string, string>();
const photoLists = new Map<
  string,
  { photos: Photo[]; byChecksum: Map<string, Photo>; timestamp: number }
>();
const photoListsPending = new Map<string, Promise<Photo[]>>();
// Keyed by derivative checksum, which is unique to the image content
const signedUrls = new Map<string, SignedUrl>();
const signingPending = new Map<string, Promise<void>>();

// Accepts a bare album ID or a share link: older albums'
// (https://www.icloud.com/sharedalbum/#B0abc123, sometimes with ";<photo>")
// or iOS 27's (https://photos.icloud.com/shared/album/0AbCdEfGhIjKlMnOpQrStUvWx/)
export function parseAlbumId(raw: string): string | null {
  let id = raw.trim();
  const shared = /\/shared\/album\/([A-Za-z0-9_-]+)/.exec(id);
  if (shared) return ALBUM_ID_PATTERN.test(shared[1]) ? shared[1] : null;
  const hash = id.lastIndexOf("#");
  if (hash >= 0) id = id.slice(hash + 1);
  const semicolon = id.indexOf(";");
  if (semicolon >= 0) id = id.slice(0, semicolon);
  return ALBUM_ID_PATTERN.test(id) ? id : null;
}

async function fetchApple(
  albumId: string,
  endpoint: string,
  body: unknown
): Promise<unknown> {
  for (let attempt = 0; attempt < 2; attempt++) {
    const host = hosts.get(albumId) ?? DEFAULT_HOST;
    const url = `https://${host}/${albumId}/sharedstreams/${endpoint}`;
    const res = await fetch(url, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(body),
    });
    if (res.status === 330) {
      // The album lives on another partition, named in the reply
      const redirect = (await res.json()) as Record<string, unknown>;
      const next = redirect["X-Apple-MMe-Host"];
      if (typeof next !== "string" || !HOST_PATTERN.test(next)) {
        throw new Error("Unexpected iCloud redirect");
      }
      hosts.set(albumId, next);
      continue;
    }
    if (!res.ok) throw new Error(await res.text());
    return res.json();
  }
  throw new Error("Too many iCloud redirects");
}

async function fetchPhotoList(albumId: string): Promise<Photo[]> {
  if (isCloudKitAlbum(albumId)) return fetchCloudKitPhotos(albumId);
  const webstream = (await fetchApple(albumId, "webstream", {
    streamCtag: null,
  })) as Webstream;

  const photos: Photo[] = [];
  for (const p of webstream.photos ?? []) {
    if (p.mediaAssetType === "video") continue;
    const derivatives: Derivative[] = [];
    let largest: Derivative | null = null;
    const raw = p.derivatives ?? {};
    for (const key of Object.keys(raw)) {
      const d = {
        checksum: raw[key].checksum,
        width: Number(raw[key].width) || 0,
        height: Number(raw[key].height) || 0,
      };
      if (!d.checksum || !d.width || !d.height) continue;
      derivatives.push(d);
      if (!largest || d.width * d.height > largest.width * largest.height) {
        largest = d;
      }
    }
    if (!largest) continue;

    photos.push({
      photoGuid: p.photoGuid,
      derivatives,
      orientation: largest.width >= largest.height ? "landscape" : "portrait",
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
          const byChecksum = new Map<string, Photo>();
          for (const p of photos) {
            for (const d of p.derivatives) byChecksum.set(d.checksum, p);
          }
          photoLists.set(albumId, {
            photos,
            byChecksum,
            timestamp: Date.now(),
          });
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

// Picks the smallest derivative that still covers a screen of the given size
// in either orientation, falling back to the largest. Without a screen size,
// the largest.
export function pickDerivative(
  photo: Photo,
  screenWidth: number,
  screenHeight: number
): Derivative {
  const long = Math.max(screenWidth, screenHeight);
  const short = Math.min(screenWidth, screenHeight);
  let best: Derivative | null = null;
  let largest = photo.derivatives[0];
  for (const d of photo.derivatives) {
    const px = d.width * d.height;
    if (px > largest.width * largest.height) largest = d;
    const bigEnough =
      long > 0 &&
      Math.max(d.width, d.height) >= long &&
      Math.min(d.width, d.height) >= short;
    if (bigEnough && (!best || px < best.width * best.height)) best = d;
  }
  return best ?? largest;
}

// Signs every derivative of one photo. Done per photo, just before it's
// needed, rather than for the whole album, so URLs are always fresh.
async function signPhoto(albumId: string, photo: Photo) {
  // Drop lapsed URLs so the cache only holds photos recently shown
  const now = Date.now();
  for (const [checksum, signed] of signedUrls) {
    if (signed.expiry < now) signedUrls.delete(checksum);
  }

  if (isCloudKitAlbum(albumId)) {
    for (const { checksum, url, expiry } of await signCloudKitPhoto(
      albumId,
      photo
    )) {
      signedUrls.set(checksum, { url, expiry });
    }
    return;
  }

  const res = (await fetchApple(albumId, "webasseturls", {
    photoGuids: [photo.photoGuid],
  })) as AssetUrls;

  const items = res.items ?? {};
  for (const checksum of Object.keys(items)) {
    const item = items[checksum];
    // Only fetch from Apple's content servers
    const location = item.url_location;
    if (
      (location !== "icloud-content.com" &&
        !location.endsWith(".icloud-content.com")) ||
      !item.url_path.startsWith("/")
    ) {
      continue;
    }
    signedUrls.set(checksum, {
      url: `https://${location}${item.url_path}`,
      expiry: new Date(item.url_expiry).getTime() || 0,
    });
  }
}

export async function getSignedUrl(
  albumId: string,
  checksum: string,
  forceRefresh = false
): Promise<string | null> {
  const cached = signedUrls.get(checksum);
  if (
    !forceRefresh &&
    cached &&
    cached.expiry - URL_EXPIRY_MARGIN > Date.now()
  ) {
    return cached.url;
  }

  await getPhotos(albumId);
  const photo = photoLists.get(albumId)?.byChecksum.get(checksum);
  if (!photo) return null;

  // Share one in-flight signing between concurrent requests for a photo
  let pending = signingPending.get(photo.photoGuid);
  if (!pending) {
    pending = signPhoto(albumId, photo).finally(() =>
      signingPending.delete(photo.photoGuid)
    );
    signingPending.set(photo.photoGuid, pending);
  }
  await pending;
  return signedUrls.get(checksum)?.url ?? null;
}
