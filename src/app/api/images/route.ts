/* eslint-disable @typescript-eslint/no-explicit-any */
import { NextRequest, NextResponse } from "next/server";
import { getPhotos, resolveAlbumId } from "../../lib/icloud";

export async function GET(req: NextRequest) {
  try {
    const queryAlbumId = req.nextUrl.searchParams.get("albumId") ?? "";
    const albumId = resolveAlbumId(queryAlbumId);
    if (!albumId) {
      return NextResponse.json(
        { error: "albumId is required (query or env)" },
        { status: 400 }
      );
    }

    // Return stable proxy URLs; Apple's signed URLs expire after ~20 minutes,
    // so they are resolved server side on each image request instead
    const photos = await getPhotos(albumId);
    return NextResponse.json(
      photos.map((p) => ({
        url: `/api/images/${encodeURIComponent(
          p.checksum
        )}?albumId=${encodeURIComponent(queryAlbumId)}`,
        orientation: p.orientation,
      }))
    );
  } catch (err: any) {
    return NextResponse.json(
      { error: "Failed to fetch images", details: err.message || err },
      { status: 502 }
    );
  }
}
