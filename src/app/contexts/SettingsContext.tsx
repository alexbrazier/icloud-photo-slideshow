"use client";
import React, {
  createContext,
  useContext,
  useEffect,
  useState,
  ReactNode,
} from "react";

const DEFAULT_TRANSITION_SECS = 60;

interface Settings {
  transitionTime: number;
  orientationFilter: "all" | "landscape" | "portrait";
  showTimerBar: boolean;
  showWeather: boolean;
  showClock: boolean;
  albumId: string;
  weatherLatitude: string;
  weatherLongitude: string;
}

interface SettingsContextType extends Settings {
  updateSetting: <K extends keyof Settings>(key: K, value: Settings[K]) => void;
}

const SettingsContext = createContext<SettingsContextType | undefined>(
  undefined,
);

const defaultSettings: Settings = {
  transitionTime: DEFAULT_TRANSITION_SECS,
  orientationFilter: "all",
  showTimerBar: false,
  showWeather: false,
  showClock: false,
  albumId: "",
  weatherLatitude: "",
  weatherLongitude: "",
};

type SettingParser = (raw: string) => Partial<Settings> | undefined;

const parseBoolean = (raw: string): boolean | undefined => {
  const value = raw.trim().toLowerCase();
  // A bare param (e.g. `?clock`) counts as true
  if (["", "true", "1", "yes", "on"].includes(value)) return true;
  if (["false", "0", "no", "off"].includes(value)) return false;
  return undefined;
};

// Maps query param names to a parser producing a partial Settings object.
const QUERY_PARAM_PARSERS: Record<string, SettingParser> = {
  transition: (raw) => {
    const n = Number(raw);
    return Number.isFinite(n) && n > 0 ? { transitionTime: n } : undefined;
  },
  orientation: (raw) => {
    const value = raw.trim().toLowerCase();
    return value === "all" || value === "landscape" || value === "portrait"
      ? { orientationFilter: value }
      : undefined;
  },
  timer: (raw) => {
    const b = parseBoolean(raw);
    return b === undefined ? undefined : { showTimerBar: b };
  },
  weather: (raw) => {
    const b = parseBoolean(raw);
    return b === undefined ? undefined : { showWeather: b };
  },
  clock: (raw) => {
    const b = parseBoolean(raw);
    return b === undefined ? undefined : { showClock: b };
  },
  album: (raw) => ({ albumId: raw.trim() }),
  lat: (raw) => ({ weatherLatitude: raw.trim() }),
  lng: (raw) => ({ weatherLongitude: raw.trim() }),
};

const parseQuerySettings = (search: string): Partial<Settings> => {
  const params = new URLSearchParams(search);
  const result: Partial<Settings> = {};
  for (const [key, value] of params.entries()) {
    const parser = QUERY_PARAM_PARSERS[key];
    if (!parser) continue;
    const parsed = parser(value);
    if (parsed) Object.assign(result, parsed);
  }
  return result;
};

// Settings persisted in localStorage (query params are a separate override layer)
const loadStoredSettings = (): Settings => {
  if (typeof window === "undefined") return { ...defaultSettings };

  const saved = localStorage.getItem("slideshowSettings");
  if (!saved) return { ...defaultSettings };

  try {
    const parsed = JSON.parse(saved);
    // Merge saved settings with defaults, ensuring all properties exist
    return {
      ...defaultSettings,
      ...Object.fromEntries(
        Object.entries(parsed).filter(([key]) => key in defaultSettings),
      ),
    };
  } catch {
    return { ...defaultSettings };
  }
};

const saveSettings = (settings: Settings) => {
  if (typeof window !== "undefined") {
    localStorage.setItem("slideshowSettings", JSON.stringify(settings));
  }
};

export function SettingsProvider({ children }: { children: ReactNode }) {
  // Start from defaults so server and first client render match, then hydrate
  // from localStorage / query params in an effect to avoid hydration mismatch.
  const [stored, setStored] = useState<Settings>(defaultSettings);
  const [overrides, setOverrides] = useState<Partial<Settings>>({});

  useEffect(() => {
    setStored(loadStoredSettings());
    setOverrides(parseQuerySettings(window.location.search));
  }, []);

  // Generic handler to update any setting. A manual change also clears any
  // query-param override for that key so the modal stays authoritative, and
  // only the persisted layer is written to localStorage.
  const updateSetting = <K extends keyof Settings>(
    key: K,
    value: Settings[K],
  ) => {
    setOverrides((prev) => {
      if (!(key in prev)) return prev;
      const next = { ...prev };
      delete next[key];
      return next;
    });
    setStored((prev) => {
      const next = { ...prev, [key]: value };
      saveSettings(next);
      return next;
    });
  };

  return (
    <SettingsContext.Provider
      value={{
        ...stored,
        ...overrides,
        updateSetting,
      }}
    >
      {children}
    </SettingsContext.Provider>
  );
}

export function useSettings() {
  const ctx = useContext(SettingsContext);
  if (!ctx) throw new Error("useSettings must be used within SettingsProvider");
  return ctx;
}
