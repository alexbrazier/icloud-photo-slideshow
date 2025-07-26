import { useEffect, useState } from "react";

export function useCogVisibility() {
  const [cogVisible, setCogVisible] = useState(false);

  useEffect(() => {
    let timeout: NodeJS.Timeout;
    const showCog = () => {
      setCogVisible(true);
      clearTimeout(timeout);
      timeout = setTimeout(() => setCogVisible(false), 2500);
    };
    window.addEventListener("mousemove", showCog);
    window.addEventListener("touchstart", showCog);
    return () => {
      window.removeEventListener("mousemove", showCog);
      window.removeEventListener("touchstart", showCog);
      clearTimeout(timeout);
    };
  }, []);

  return cogVisible;
}
