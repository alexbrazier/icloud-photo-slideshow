/* eslint-disable @typescript-eslint/no-explicit-any */
import { NextRequest, NextResponse } from "next/server";
import { getSignedUrl, resolveAlbumId } from "../../../lib/icloud";

export async function GET(
  req: NextRequest,
  { params }: { params: Promise<{ checksum: string }> }
) {
  try {
    const { checksum } = await params;
    const albumId = resolveAlbumId(req.nextUrl.searchParams.get("albumId"));
    if (!albumId) {
      return NextResponse.json(
        { error: "albumId is required (query or env)" },
        { status: 400 }
      );
    }

    let url = await getSignedUrl(albumId, checksum);
    let upstream = url ? await fetch(url) : null;
    // URL may have been revoked early; re-sign once and retry
    if (!upstream?.ok) {
      url = await getSignedUrl(albumId, checksum, true);
      upstream = url ? await fetch(url) : null;
    }
    if (!upstream?.ok || !upstream.body) {
      return NextResponse.json(
        { error: "Image not found" },
        { status: upstream?.status === 404 || !url ? 404 : 502 }
      );
    }

    // The checksum identifies the image content, so it can be cached forever
    return new Response(upstream.body, {
      headers: {
        "Content-Type": upstream.headers.get("content-type") ?? "image/jpeg",
        ...(upstream.headers.get("content-length") && {
          "Content-Length": upstream.headers.get("content-length")!,
        }),
        "Cache-Control": "public, max-age=31536000, immutable",
      },
    });
  } catch (err: any) {
    return NextResponse.json(
      { error: "Failed to fetch image", details: err.message || err },
      { status: 502 }
    );
  }
}
