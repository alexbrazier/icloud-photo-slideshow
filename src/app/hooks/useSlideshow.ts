import { useCallback, useEffect, useRef, useState } from "react";

const DEFAULT_REFRESH_MINS = 60;

export type Orientation = "landscape" | "portrait";

type Images = { url: string; orientation: Orientation }[];

export function useSlideshow(
  albumId: string,
  transitionTime: number,
  orientationFilter: "all" | Orientation
): {
  currentImage?: Images[number];
  nextImage?: Images[number];
  goNext: () => void;
  goPrev: () => void;
} {
  const [images, setImages] = useState<Images>([]);
  const [shuffledImages, setShuffledImages] = useState<Images>([]);
  const [current, setCurrent] = useState(0);
  const intervalRef = useRef<NodeJS.Timeout | null>(null);

  // Shared function to start/restart the slideshow timer
  const startSlideshowTimer = useCallback(() => {
    if (intervalRef.current) clearInterval(intervalRef.current);
    intervalRef.current = setInterval(() => {
      setCurrent((prev) => {
        const nextIndex = (prev + 1) % shuffledImages.length;
        // If we've completed a loop, shuffle again
        if (nextIndex === 0 && prev === shuffledImages.length - 1) {
          setShuffledImages((currentImages) => shuffleArray(currentImages));
        }
        return nextIndex;
      });
    }, transitionTime * 1000);
  }, [shuffledImages.length, transitionTime]);

  // Navigation function that handles all transitions
  const navigateTo = useCallback(
    (nextIdx: number) => {
      if (nextIdx === current || !shuffledImages.length) return;
      setCurrent(nextIdx);

      // Reset the automatic slideshow timer when manually navigating
      startSlideshowTimer();
    },
    [current, shuffledImages.length, startSlideshowTimer]
  );

  // Fetch images and periodically refresh
  const fetchPhotos = async (albumId?: string) => {
    try {
      const res = await fetch(`/api/images?albumId=${albumId || ""}`);
      const imagesArr = await res.json();
      setImages(imagesArr);
    } catch (err) {
      console.error("Error fetching photos:", err);
    }
  };

  // Shuffle array using Fisher-Yates algorithm
  const shuffleArray = (
    array: Array<{ url: string; orientation: "landscape" | "portrait" }>
  ) => {
    const shuffled = [...array];
    for (let i = shuffled.length - 1; i > 0; i--) {
      const j = Math.floor(Math.random() * (i + 1));
      [shuffled[i], shuffled[j]] = [shuffled[j], shuffled[i]];
    }
    return shuffled;
  };

  useEffect(() => {
    fetchPhotos(albumId);
    const refresh = setInterval(
      () => fetchPhotos(albumId),
      DEFAULT_REFRESH_MINS * 60 * 1000
    );
    return () => clearInterval(refresh);
  }, [albumId]);

  // Shuffle images when they change
  useEffect(() => {
    if (images.length > 0) {
      const filtered = images.filter((img) => {
        if (orientationFilter === "all") return true;
        return img.orientation === orientationFilter;
      });
      setShuffledImages(shuffleArray(filtered));
      setCurrent(0); // Reset to first image of new shuffle
    }
  }, [images, orientationFilter]);

  // Slideshow logic
  useEffect(() => {
    if (!shuffledImages.length) return;
    startSlideshowTimer();
    return () => {
      if (intervalRef.current) clearInterval(intervalRef.current);
    };
  }, [shuffledImages, startSlideshowTimer]);

  // Keyboard navigation
  useEffect(() => {
    const handler = (e: KeyboardEvent) => {
      if (e.key === "ArrowLeft") {
        const prevIdx =
          (current - 1 + shuffledImages.length) % shuffledImages.length;
        navigateTo(prevIdx);
      } else if (e.key === "ArrowRight") {
        const nextIdx = (current + 1) % shuffledImages.length;
        navigateTo(nextIdx);
      }
    };
    window.addEventListener("keydown", handler);
    return () => window.removeEventListener("keydown", handler);
  }, [current, shuffledImages.length, navigateTo]);

  // Helper functions for navigation
  const goNext = () => {
    const nextIdx = (current + 1) % shuffledImages.length;
    navigateTo(nextIdx);
  };

  const goPrev = () => {
    const prevIdx =
      (current - 1 + shuffledImages.length) % shuffledImages.length;
    navigateTo(prevIdx);
  };

  return {
    currentImage: shuffledImages[current],
    nextImage: shuffledImages[(current + 1) % shuffledImages.length],
    goNext,
    goPrev,
  };
}
