import { useEffect, useEffectEvent, useState } from "react";

export interface ImageData {
  url: string;
  orientation: "landscape" | "portrait";
}

// Delay before skipping a broken image, so a server outage doesn't cause a
// rapid loop through every image
const ERROR_SKIP_DELAY_MS = 2000;

export function useCrossfade(
  currentImage?: ImageData,
  nextImage?: ImageData,
  onImageError?: () => void
) {
  const [displayedCurrentImage, setDisplayedCurrentImage] =
    useState<ImageData>();
  // Image currently fading in over displayedCurrentImage, if any
  const [incomingImage, setIncomingImage] = useState<ImageData>();
  const handleImageError = useEffectEvent(() => onImageError?.());

  // Handle image changes with crossfade transition, once the image has loaded
  useEffect(() => {
    if (!currentImage?.url || currentImage.url === displayedCurrentImage?.url)
      return;

    let cancelled = false;
    let timeout: NodeJS.Timeout | undefined;
    const img = new Image();
    img.src = currentImage.url;
    img.decode().then(
      () => {
        if (cancelled) return;
        setIncomingImage(currentImage);

        // After 1 second, complete the transition
        timeout = setTimeout(() => {
          setDisplayedCurrentImage(currentImage);
          setIncomingImage(undefined);
        }, 1000);
      },
      () => {
        if (cancelled) return;
        console.error("Failed to load image, skipping:", currentImage.url);
        timeout = setTimeout(handleImageError, ERROR_SKIP_DELAY_MS);
      }
    );

    return () => {
      cancelled = true;
      clearTimeout(timeout);
    };
  }, [currentImage, displayedCurrentImage?.url]);

  return {
    isTransitioning: incomingImage !== undefined,
    displayedCurrentImage,
    // Otherwise render the upcoming image hidden, so it preloads
    displayedNextImage: incomingImage ?? nextImage,
  };
}
