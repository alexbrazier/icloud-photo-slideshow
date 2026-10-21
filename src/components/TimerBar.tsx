import React from "react";

interface TimerBarProps {
  transitionTime: number; // in seconds
  currentImageUrl?: string; // Add this to detect image changes
}

export function TimerBar({ transitionTime, currentImageUrl }: TimerBarProps) {
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
      {/* Keyed on image and duration so the CSS animation restarts on change */}
      <div
        key={`${currentImageUrl}-${transitionTime}`}
        style={{
          height: "100%",
          width: "100%",
          transformOrigin: "left",
          backgroundColor: "rgba(255, 255, 255, 0.8)",
          animation: `timer-bar-progress ${transitionTime}s linear forwards`,
        }}
      />
    </div>
  );
}
