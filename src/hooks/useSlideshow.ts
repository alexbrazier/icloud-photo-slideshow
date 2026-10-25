import { useCallback, useEffect, useRef, useState } from "react";

const DEFAULT_REFRESH_MINS = 60;
const MIN_RETRY_MS = 30 * 1000;
const MAX_RETRY_MS = 10 * 60 * 1000;

export type Orientation = "landscape" | "portrait";

type Images = { url: string; orientation: Orientation }[];

// Shuffle array using Fisher-Yates algorithm
const shuffleArray = (array: Images) => {
  const shuffled = [...array];
  for (let i = shuffled.length - 1; i > 0; i--) {
    const j = Math.floor(Math.random() * (i + 1));
    [shuffled[i], shuffled[j]] = [shuffled[j], shuffled[i]];
  }
  return shuffled;
};

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
  const intervalRef = useRef<ReturnType<typeof setInterval> | null>(null);

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

  // Fetch images, returning whether it succeeded
  const fetchPhotos = async (
    albumId: string,
    isCancelled: () => boolean
  ): Promise<boolean> => {
    try {
      // Screen size in device pixels, so the server can pick photos that
      // fill it without downloading full size originals
      const scale = window.devicePixelRatio || 1;
      const params = new URLSearchParams({
        w: String(Math.round(window.screen.width * scale)),
        h: String(Math.round(window.screen.height * scale)),
      });
      // Blank means the server's default album
      if (albumId) params.set("albumId", albumId);
      const res = await fetch(`/api/images?${params}`);
      const data = await res.json();
      // Ignore responses for an album we've since switched away from
      if (isCancelled()) return true;
      if (!res.ok || !Array.isArray(data)) {
        console.error("Error fetching photos:", data?.error || res.statusText);
        return false;
      }
      // URLs are stable, so skip the reshuffle if the album hasn't changed
      setImages((prev) =>
        JSON.stringify(prev) === JSON.stringify(data) ? prev : data
      );
      return true;
    } catch (err) {
      console.error("Error fetching photos:", err);
      return false;
    }
  };

  useEffect(() => {
    let cancelled = false;
    let timer: ReturnType<typeof setTimeout>;
    let retryDelay = MIN_RETRY_MS;
    // Refresh periodically, retrying failures with exponential backoff so a
    // failed first load doesn't leave the screen blank until the next refresh
    const load = async () => {
      const ok = await fetchPhotos(albumId, () => cancelled);
      if (cancelled) return;
      if (ok) {
        retryDelay = MIN_RETRY_MS;
        timer = setTimeout(load, DEFAULT_REFRESH_MINS * 60 * 1000);
      } else {
        timer = setTimeout(load, retryDelay);
        retryDelay = Math.min(retryDelay * 2, MAX_RETRY_MS);
      }
    };
    load();
    return () => {
      cancelled = true;
      clearTimeout(timer);
    };
  }, [albumId]);

  // Shuffle images when they change (adjusting state during render, rather
  // than in an effect, avoids rendering the stale order first)
  const [shuffledFrom, setShuffledFrom] = useState({
    images,
    orientationFilter,
  });
  if (
    shuffledFrom.images !== images ||
    shuffledFrom.orientationFilter !== orientationFilter
  ) {
    setShuffledFrom({ images, orientationFilter });
    if (images.length > 0) {
      const filtered = images.filter((img) => {
        if (orientationFilter === "all") return true;
        return img.orientation === orientationFilter;
      });
      setShuffledImages(shuffleArray(filtered));
      setCurrent(0); // Reset to first image of new shuffle
    }
  }

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
