import React, { useState } from "react";
import { useSlideshow } from "../hooks/useSlideshow";
import { useSettings } from "../contexts/SettingsContext";
import { useScreenOrientation } from "../hooks/useScreenOrientation";
import { CrossfadeImages } from "./CrossfadeImages";
import { TimerBar } from "./TimerBar";
import { NavigationZone } from "./NavigationZone";

interface SlideshowProps {
  albumId: string;
  controlsVisible?: boolean; // Optional prop for controlling visibility of navigation zones
}

export function Slideshow({ albumId, controlsVisible }: SlideshowProps) {
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
      <NavigationZone
        side="left"
        onClick={goPrev}
        controlsVisible={controlsVisible}
      />
      <NavigationZone
        side="right"
        onClick={goNext}
        controlsVisible={controlsVisible}
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
