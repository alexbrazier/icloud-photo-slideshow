/* eslint-disable @next/next/no-img-element */
import React, { useEffect } from "react";
import { Orientation } from "../hooks/useSlideshow";
import { useCrossfade, ImageData } from "../hooks/useCrossfade";

interface CrossfadeImagesProps {
  currentImage?: ImageData;
  nextImage?: ImageData;
  screenOrientation: Orientation;
  onTransitionChange?: (isTransitioning: boolean) => void;
}

interface SlideImageProps {
  src: string;
  alt: string;
  screenOrientation: Orientation;
  imageOrientation: Orientation;
  opacity: number;
  zIndex: number;
  key: string | number;
}

function SlideImage({
  src,
  alt,
  screenOrientation,
  imageOrientation,
  opacity,
  zIndex,
  key,
}: SlideImageProps) {
  // Use cover when orientations match, contain when they don't
  const objectFit: "cover" | "contain" =
    screenOrientation === imageOrientation ? "cover" : "contain";

  return (
    <img
      key={key}
      src={src}
      alt={alt}
      style={{
        position: "absolute",
        top: "50%",
        left: "50%",
        transform: "translate(-50%, -50%)",
        ...(objectFit === "cover"
          ? { width: "100vw", height: "100vh" }
          : { maxWidth: "100%", maxHeight: "100%" }),
        objectFit,
        opacity,
        zIndex,
        transition: "opacity 1s ease-in-out",
        pointerEvents: "none",
      }}
    />
  );
}

export function CrossfadeImages({
  currentImage,
  nextImage,
  screenOrientation,
  onTransitionChange,
}: CrossfadeImagesProps) {
  const { isTransitioning, displayedCurrentImage, displayedNextImage } =
    useCrossfade(currentImage, nextImage);

  // Notify parent of transition state changes
  useEffect(() => {
    onTransitionChange?.(isTransitioning);
  }, [isTransitioning, onTransitionChange]);

  if (!displayedCurrentImage) {
    return null;
  }

  return (
    <>
      {/* Current image */}
      <SlideImage
        src={displayedCurrentImage.url}
        alt="Current slide"
        screenOrientation={screenOrientation}
        imageOrientation={displayedCurrentImage.orientation}
        opacity={isTransitioning ? 0 : 1}
        zIndex={isTransitioning ? 1 : 2}
        key={displayedCurrentImage.url}
      />

      {/* Next image - always rendered for preloading */}
      {displayedNextImage && (
        <SlideImage
          src={displayedNextImage.url}
          alt="Next slide"
          screenOrientation={screenOrientation}
          imageOrientation={displayedNextImage.orientation}
          opacity={isTransitioning ? 1 : 0}
          zIndex={isTransitioning ? 2 : 1}
          key={displayedNextImage.url}
        />
      )}
    </>
  );
}
