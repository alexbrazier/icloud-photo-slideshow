// Shared albums made with iOS/macOS 27 and later, whose links look like
// https://photos.icloud.com/shared/album/0AbCdEfGhIjKlMnOpQrStUvWx/
//
// They're CloudKit shares rather than the older "sharedstreams" albums. The
// link's ID resolves (anonymously) to the album's zone plus a short-lived
// access token, the photos are listed from that zone, and each photo's
// download URL comes from looking its record up again, as those URLs expire
// after ~20 minutes like the older albums' do.

import type { Derivative, Photo } from "./icloud.ts";

const RESOLVE_URL =
  "https://ckdatabasews.icloud.com/database/1/com.apple.photos.cloud/production/public/records/resolve";
const PARTITION_PATTERN =
  /^https:\/\/p[0-9]{1,4}-ckdatabasews\.icloud\.com(:443)?$/;
// Renew the access token a little before it lapses
const TOKEN_MARGIN = 2 * 60 * 1000;
// Items per page; each item is two records (asset and master)
const PAGE_ITEMS = 100;
// Shared albums hold at most a few thousand items
const MAX_PAGES = 100;

// These links' IDs start with 0, the older albums' with a letter (B0…, B2…)
export function isCloudKitAlbum(albumId: string): boolean {
  return albumId.startsWith("0");
}

type Field = { value?: unknown };
type CKRecord = {
  recordName: string;
  recordType: string;
  fields?: Record<string, Field>;
};
type Asset = { fileChecksum?: string; downloadURL?: string };
type Session = {
  partition: string;
  token: string;
  zoneID: unknown;
  name: string;
  expires: number;
};

const sessions = new Map<string, Session>();
const sessionsPending = new Map<string, Promise<Session>>();

async function postJson(url: string, body: unknown): Promise<unknown> {
  const res = await fetch(url, {
    method: "POST",
    headers: { "Content-Type": "text/plain" },
    body: JSON.stringify(body),
  });
  if (!res.ok) throw new Error(`iCloud returned HTTP ${res.status}`);
  return res.json();
}

async function resolve(albumId: string): Promise<Session> {
  const url = `${RESOLVE_URL}?remapEnums=true&sharing_url_key=${encodeURIComponent(albumId)}`;
  const json = (await postJson(url, { shortGUIDs: [{ value: albumId }] })) as {
    results?: Record<string, unknown>[];
  };
  const result = json.results?.[0] ?? {};
  if (result.serverErrorCode === "NOT_FOUND") {
    throw new Error(
      "Album not found. Check the link, and that it's still shared."
    );
  }
  const access = result.anonymousPublicAccess as
    | { token?: string; tokenTTL?: number; databasePartition?: string }
    | undefined;
  if (result.requireAppleLogin || !access?.token) {
    throw new Error(
      "This album needs an Apple Account to view. Turn on Public Website for it."
    );
  }
  const partition = access.databasePartition ?? "";
  if (!PARTITION_PATTERN.test(partition)) {
    throw new Error("Unexpected iCloud partition");
  }
  const share = result.share as CKRecord | undefined;
  const title = share?.fields?.["cloudkit.title"]?.value;
  return {
    partition: partition.replace(/:443$/, ""),
    token: access.token,
    zoneID: result.zoneID,
    name: typeof title === "string" ? title : "",
    expires: Date.now() + (access.tokenTTL ?? 20 * 60 * 1000) - TOKEN_MARGIN,
  };
}

function session(albumId: string, renew = false): Promise<Session> {
  const cached = sessions.get(albumId);
  if (!renew && cached && cached.expires > Date.now())
    return Promise.resolve(cached);
  let pending = sessionsPending.get(albumId);
  if (!pending) {
    pending = resolve(albumId)
      .then((s) => {
        sessions.set(albumId, s);
        return s;
      })
      .finally(() => sessionsPending.delete(albumId));
    sessionsPending.set(albumId, pending);
  }
  return pending;
}

// A request to the album's zone, renewing the access token once if it's refused
async function zoneRequest(
  albumId: string,
  path: string,
  body: (zoneID: unknown) => unknown
): Promise<{ records?: CKRecord[] }> {
  for (let attempt = 0; attempt < 2; attempt++) {
    const s = await session(albumId, attempt > 0);
    // Built by hand, as the native build (scriptc) can't compile URLSearchParams
    const params =
      `remapEnums=true&sharing_url_key=${encodeURIComponent(albumId)}` +
      `&publicAccessAuthToken=${encodeURIComponent(s.token)}`;
    const res = await fetch(
      `${s.partition}/database/1/com.apple.photos.cloud/production/shared/${path}?${params}`,
      {
        method: "POST",
        headers: { "Content-Type": "text/plain" },
        body: JSON.stringify(body(s.zoneID)),
      }
    );
    if ((res.status === 401 || res.status === 403) && attempt === 0) continue;
    if (!res.ok) throw new Error(`iCloud returned HTTP ${res.status}`);
    return (await res.json()) as { records?: CKRecord[] };
  }
  throw new Error("iCloud refused the album's access token");
}

function num(fields: Record<string, Field>, key: string): number {
  return Number(fields[key]?.value) || 0;
}

const VIDEO_TYPES = new Set(["com.apple.quicktime-movie", "public.mpeg-4"]);

// Edited photos have their rendered version on the asset record; otherwise
// the master's ready-made JPEGs (already rotated upright) are used
function toPhoto(asset: CKRecord, master: CKRecord): Photo | null {
  const af = asset.fields ?? {};
  const mf = master.fields ?? {};
  if (VIDEO_TYPES.has(String(mf.itemType?.value))) return null;
  const sources: [CKRecord, string][] = (
    af.resJPEGFullRes?.value as Asset | undefined
  )?.fileChecksum
    ? [[asset, "resJPEGFull"]]
    : [
        [master, "resJPEGMed"],
        [master, "resJPEGThumb"],
      ];
  const derivatives: Derivative[] = [];
  let record = master;
  for (const [rec, prefix] of sources) {
    const fields = rec.fields ?? {};
    const res = fields[`${prefix}Res`]?.value as Asset | undefined;
    const width = num(fields, `${prefix}Width`);
    const height = num(fields, `${prefix}Height`);
    if (!res?.fileChecksum || !width || !height) continue;
    derivatives.push({
      checksum: res.fileChecksum,
      width,
      height,
      field: `${prefix}Res`,
    });
    record = rec;
  }
  if (!derivatives.length) return null;
  const largest = derivatives[0];
  return {
    photoGuid: record.recordName,
    derivatives,
    orientation: largest.width >= largest.height ? "landscape" : "portrait",
  };
}

export async function fetchCloudKitPhotos(albumId: string): Promise<Photo[]> {
  const photos: Photo[] = [];
  for (let page = 0, rank = 0; page < MAX_PAGES; page++) {
    const json = await zoneRequest(albumId, "records/query", (zoneID) => ({
      query: {
        recordType: "CPLAssetAndMasterByAssetDateWithoutHiddenOrDeleted",
        filterBy: [
          {
            fieldName: "direction",
            comparator: "EQUALS",
            fieldValue: { value: "ASCENDING", type: "STRING" },
          },
          {
            fieldName: "startRank",
            comparator: "EQUALS",
            fieldValue: { value: rank, type: "INT64" },
          },
        ],
      },
      zoneID,
      resultsLimit: PAGE_ITEMS * 2,
    }));
    const records = json.records ?? [];
    const masters = new Map<string, CKRecord>();
    for (const r of records)
      if (r.recordType === "CPLMaster") masters.set(r.recordName, r);
    let assets = 0;
    for (const r of records) {
      if (r.recordType !== "CPLAsset") continue;
      assets++;
      const ref = r.fields?.masterRef?.value as
        | { recordName?: string }
        | undefined;
      const master = ref?.recordName ? masters.get(ref.recordName) : undefined;
      const photo = master && toPhoto(r, master);
      if (photo) photos.push(photo);
    }
    if (assets < PAGE_ITEMS) break;
    rank += assets;
  }
  return photos;
}

// Fresh download URLs for one photo, keyed by derivative checksum
export async function signCloudKitPhoto(
  albumId: string,
  photo: Photo
): Promise<{ checksum: string; url: string; expiry: number }[]> {
  const fields = photo.derivatives.map((d) => d.field ?? "");
  const json = await zoneRequest(albumId, "records/lookup", (zoneID) => ({
    records: [{ recordName: photo.photoGuid, desiredKeys: fields }],
    zoneID,
  }));
  const record = json.records?.[0];
  const signed = [];
  for (const field of fields) {
    const res = record?.fields?.[field]?.value as Asset | undefined;
    if (!res?.fileChecksum || !res.downloadURL) continue;
    let url: URL;
    try {
      url = new URL(res.downloadURL.replace("${f}", "photo.jpg"));
    } catch {
      continue;
    }
    // Only fetch from Apple's content servers
    if (
      url.protocol !== "https:" ||
      (url.hostname !== "icloud-content.com" &&
        !url.hostname.endsWith(".icloud-content.com"))
    ) {
      continue;
    }
    // e= is when the URL lapses, in seconds
    const expires = Number(url.searchParams.get("e")) * 1000;
    signed.push({
      checksum: res.fileChecksum,
      url: url.toString(),
      expiry: expires > Date.now() ? expires : Date.now() + 15 * 60 * 1000,
    });
  }
  return signed;
}
