import React, { useState } from "react";
import { useSlideshow } from "../hooks/useSlideshow";
import { useSettings } from "../contexts/SettingsContext";
import { useScreenOrientation } from "../hooks/useScreenOrientation";
import { CrossfadeImages } from "./CrossfadeImages";
import { TimerBar } from "./TimerBar";

interface SlideshowProps {
  albumId: string;
}

interface NavigationZoneProps {
  side: "left" | "right";
  onClick: () => void;
  disabled: boolean;
}

function NavigationZone({ side, onClick, disabled }: NavigationZoneProps) {
  return (
    <div
      style={{
        position: "absolute",
        [side]: 0,
        top: 0,
        width: "25vw",
        height: "100vh",
        zIndex: 10,
        cursor: disabled ? "default" : "pointer",
      }}
      onClick={() => !disabled && onClick()}
    />
  );
}

export function Slideshow({ albumId }: SlideshowProps) {
  const { transitionTime, orientationFilter, showTimerBar } = useSettings();
  const { currentImage, nextImage, goNext, goPrev } = useSlideshow(
    albumId,
    transitionTime,
    orientationFilter
  );
  const screenOrientation = useScreenOrientation();
  const [isTransitioning, setIsTransitioning] = useState(false);

  return (
    <div
      style={{
        width: "100vw",
        height: "100vh",
        background: "black",
        position: "relative",
        overflow: "hidden",
      }}
    >
      {/* Navigation zones */}
      <NavigationZone side="left" onClick={goPrev} disabled={isTransitioning} />
      <NavigationZone
        side="right"
        onClick={goNext}
        disabled={isTransitioning}
      />
      {/* Slideshow images */}
      <CrossfadeImages
        currentImage={currentImage}
        nextImage={nextImage}
        screenOrientation={screenOrientation}
        onTransitionChange={setIsTransitioning}
      />
      {/* Timer bar */}
      {showTimerBar && (
        <TimerBar
          transitionTime={transitionTime}
          currentImageUrl={currentImage?.url}
        />
      )}
    </div>
  );
}
