import { useEffect, useState } from "react";

export interface ImageData {
  url: string;
  orientation: "landscape" | "portrait";
}

export function useCrossfade(currentImage?: ImageData, nextImage?: ImageData) {
  const [isTransitioning, setIsTransitioning] = useState(false);
  const [displayedCurrentImage, setDisplayedCurrentImage] =
    useState(currentImage);
  const [displayedNextImage, setDisplayedNextImage] = useState(nextImage);

  // Handle image changes with crossfade transition
  useEffect(() => {
    if (currentImage?.url && currentImage.url !== displayedCurrentImage?.url) {
      setIsTransitioning(true);
      setDisplayedNextImage(currentImage);

      // After 1 second, complete the transition
      const timeout = setTimeout(() => {
        setDisplayedCurrentImage(currentImage);
        setDisplayedNextImage(nextImage);
        setIsTransitioning(false);
      }, 1000);

      return () => clearTimeout(timeout);
    } else if (!isTransitioning && nextImage) {
      // Update next image for preloading when not transitioning
      setDisplayedNextImage(nextImage);
    }
  }, [currentImage, nextImage, displayedCurrentImage?.url, isTransitioning]);

  return {
    isTransitioning,
    displayedCurrentImage,
    displayedNextImage,
  };
}
