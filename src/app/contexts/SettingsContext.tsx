"use client";
import React, { createContext, useContext, useState, ReactNode } from "react";

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
  if (["true", "1", "yes", "on"].includes(value)) return true;
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

const loadSettings = (): Settings => {
  let settings: Settings = { ...defaultSettings };

  if (typeof window !== "undefined") {
    const saved = localStorage.getItem("slideshowSettings");
    if (saved) {
      try {
        const parsed = JSON.parse(saved);
        // Merge saved settings with defaults, ensuring all properties exist
        settings = {
          ...settings,
          ...Object.fromEntries(
            Object.entries(parsed).filter(([key]) => key in defaultSettings),
          ),
        };
      } catch {
        // If parsing fails, keep defaults
      }
    }

    // Query params take precedence over stored/default settings
    settings = { ...settings, ...parseQuerySettings(window.location.search) };
  }

  return settings;
};

const saveSettings = (settings: Settings) => {
  if (typeof window !== "undefined") {
    localStorage.setItem("slideshowSettings", JSON.stringify(settings));
  }
};

export function SettingsProvider({ children }: { children: ReactNode }) {
  const [settings, setSettings] = useState<Settings>(loadSettings);

  // Generic handler to update any setting
  const updateSetting = <K extends keyof Settings>(
    key: K,
    value: Settings[K],
  ) => {
    const newSettings = { ...settings, [key]: value };
    setSettings(newSettings);
    saveSettings(newSettings);
  };

  return (
    <SettingsContext.Provider
      value={{
        ...settings,
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
