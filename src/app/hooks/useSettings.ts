import { useState } from "react";
import { Orientation } from "./useSlideshow";

const DEFAULT_TRANSITION_SECS = 60;

export function useSettings() {
  const [transitionTime, setTransitionTime] = useState<number>(() => {
    if (typeof window !== "undefined") {
      const saved = localStorage.getItem("slideshowSettings");
      if (saved) {
        try {
          const parsed = JSON.parse(saved);
          return parsed.transitionTime || DEFAULT_TRANSITION_SECS;
        } catch {}
      }
    }
    return DEFAULT_TRANSITION_SECS;
  });

  const [orientationFilter, setOrientationFilter] = useState<
    "all" | Orientation
  >(() => {
    if (typeof window !== "undefined") {
      const saved = localStorage.getItem("slideshowSettings");
      if (saved) {
        try {
          const parsed = JSON.parse(saved);
          return parsed.orientationFilter || "all";
        } catch {}
      }
    }
    return "all";
  });

  const [showTimerBar, setShowTimerBar] = useState<boolean>(() => {
    if (typeof window !== "undefined") {
      const saved = localStorage.getItem("slideshowSettings");
      if (saved) {
        try {
          const parsed = JSON.parse(saved);
          return !!parsed.showTimerBar;
        } catch {}
      }
    }
    return false;
  });

  const handleTransitionChange = (val: number) => {
    if (isNaN(val) || val < 1) val = 1;
    if (val > 600) val = 600;
    setTransitionTime(val);
    localStorage.setItem(
      "slideshowSettings",
      JSON.stringify({ transitionTime: val, orientationFilter, showTimerBar })
    );
  };

  const handleOrientationChange = (val: "all" | Orientation) => {
    setOrientationFilter(val);
    localStorage.setItem(
      "slideshowSettings",
      JSON.stringify({ transitionTime, orientationFilter: val, showTimerBar })
    );
  };

  const handleShowTimerBarChange = (val: boolean) => {
    setShowTimerBar(val);
    localStorage.setItem(
      "slideshowSettings",
      JSON.stringify({ transitionTime, orientationFilter, showTimerBar: val })
    );
  };

  return {
    transitionTime,
    orientationFilter,
    showTimerBar,
    handleTransitionChange,
    handleOrientationChange,
    handleShowTimerBarChange,
  };
}
