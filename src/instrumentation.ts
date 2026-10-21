// Warm the photo list cache on server start, as Apple's webstream is slow
export async function register() {
  if (process.env.NEXT_RUNTIME !== "nodejs") return;
  const albumId = process.env.ICLOUD_ALBUM_ID;
  if (!albumId) return;

  const { getPhotos } = await import("./app/lib/icloud");
  // Don't block server startup on it
  getPhotos(albumId)
    .then((photos) => console.log(`Cached ${photos.length} photos`))
    .catch((err) => console.error("Failed to warm photo cache:", err));
}
