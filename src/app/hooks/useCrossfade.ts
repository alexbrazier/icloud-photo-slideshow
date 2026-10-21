import { useEffect, useRef, useState } from "react";

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
  const [isTransitioning, setIsTransitioning] = useState(false);
  const [displayedCurrentImage, setDisplayedCurrentImage] =
    useState<ImageData>();
  const [displayedNextImage, setDisplayedNextImage] = useState(nextImage);
  const onImageErrorRef = useRef(onImageError);
  onImageErrorRef.current = onImageError;

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
        setIsTransitioning(true);
        setDisplayedNextImage(currentImage);

        // After 1 second, complete the transition
        timeout = setTimeout(() => {
          setDisplayedCurrentImage(currentImage);
          setIsTransitioning(false);
        }, 1000);
      },
      () => {
        if (cancelled) return;
        console.error("Failed to load image, skipping:", currentImage.url);
        timeout = setTimeout(
          () => onImageErrorRef.current?.(),
          ERROR_SKIP_DELAY_MS
        );
      }
    );

    return () => {
      cancelled = true;
      clearTimeout(timeout);
    };
  }, [currentImage, displayedCurrentImage?.url]);

  // Update next image for preloading when not transitioning
  useEffect(() => {
    if (!isTransitioning && nextImage) setDisplayedNextImage(nextImage);
  }, [nextImage, isTransitioning]);

  return {
    isTransitioning,
    displayedCurrentImage,
    displayedNextImage,
  };
}
