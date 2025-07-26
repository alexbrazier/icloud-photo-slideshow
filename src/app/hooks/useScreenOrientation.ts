import { useEffect, useState } from "react";
import { Orientation } from "./useSlideshow";

export function useScreenOrientation(): Orientation {
  const [screenOrientation, setScreenOrientation] =
    useState<Orientation>("landscape");

  useEffect(() => {
    function detectOrientation() {
      if (window.innerWidth > window.innerHeight) {
        setScreenOrientation("landscape");
      } else {
        setScreenOrientation("portrait");
      }
    }

    detectOrientation();
    window.addEventListener("resize", detectOrientation);
    return () => window.removeEventListener("resize", detectOrientation);
  }, []);

  return screenOrientation;
}
