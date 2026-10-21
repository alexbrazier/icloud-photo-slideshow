import { useEffect } from "react";

// Keep the screen awake while the slideshow is visible. Only available in
// secure contexts (HTTPS or localhost); silently does nothing otherwise.
export function useWakeLock() {
  useEffect(() => {
    if (!("wakeLock" in navigator)) return;
    let lock: WakeLockSentinel | null = null;
    let cancelled = false;

    // The lock is released whenever the page is hidden, so re-acquire it
    const request = async () => {
      if (document.visibilityState !== "visible" || (lock && !lock.released))
        return;
      try {
        lock = await navigator.wakeLock.request("screen");
        if (cancelled) lock.release();
      } catch (err) {
        console.warn("Wake lock unavailable:", err);
      }
    };

    request();
    document.addEventListener("visibilitychange", request);
    return () => {
      cancelled = true;
      document.removeEventListener("visibilitychange", request);
      lock?.release();
    };
  }, []);
}
