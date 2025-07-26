import React, { useEffect, useState, useRef } from "react";

interface TimerBarProps {
  transitionTime: number; // in seconds
  currentImageUrl?: string; // Add this to detect image changes
}

export function TimerBar({ transitionTime, currentImageUrl }: TimerBarProps) {
  const [progress, setProgress] = useState(0);
  const animationFrameRef = useRef<number | null>(null);

  useEffect(() => {
    // Cancel any existing animation frame first
    if (animationFrameRef.current) {
      cancelAnimationFrame(animationFrameRef.current);
      animationFrameRef.current = null;
    }

    // Reset progress and start timer whenever image changes
    setProgress(0);
    const startTime = Date.now();
    const duration = transitionTime * 1000; // Convert to milliseconds

    const updateProgress = () => {
      const elapsed = Date.now() - startTime;
      const newProgress = Math.min((elapsed / duration) * 100, 100);
      setProgress(newProgress);

      if (newProgress < 100) {
        animationFrameRef.current = requestAnimationFrame(updateProgress);
      } else {
        animationFrameRef.current = null;
      }
    };

    // Start immediately, don't wait for first animation frame
    updateProgress();

    return () => {
      if (animationFrameRef.current) {
        cancelAnimationFrame(animationFrameRef.current);
        animationFrameRef.current = null;
      }
    };
  }, [transitionTime, currentImageUrl]);

  return (
    <div
      style={{
        position: "absolute",
        bottom: 0,
        left: 0,
        width: "100%",
        height: "4px",
        backgroundColor: "rgba(255, 255, 255, 0.2)",
        zIndex: 5,
      }}
    >
      <div
        style={{
          height: "100%",
          width: `${progress}%`,
          backgroundColor: "rgba(255, 255, 255, 0.8)",
        }}
      />
    </div>
  );
}
