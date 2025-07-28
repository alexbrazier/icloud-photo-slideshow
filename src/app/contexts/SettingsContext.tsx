"use client";
import React, { createContext, useContext, useState, ReactNode } from "react";

const DEFAULT_TRANSITION_SECS = 60;

interface Settings {
  transitionTime: number;
  orientationFilter: "all" | "landscape" | "portrait";
  showTimerBar: boolean;
  showWeather: boolean;
  albumId: string;
  weatherLatitude: string;
  weatherLongitude: string;
}

interface SettingsContextType extends Settings {
  updateSetting: <K extends keyof Settings>(key: K, value: Settings[K]) => void;
}

const SettingsContext = createContext<SettingsContextType | undefined>(
  undefined
);

const loadSettings = (): Settings => {
  const defaultSettings: Settings = {
    transitionTime: DEFAULT_TRANSITION_SECS,
    orientationFilter: "all",
    showTimerBar: false,
    showWeather: false,
    albumId: "",
    weatherLatitude: "",
    weatherLongitude: "",
  };

  if (typeof window !== "undefined") {
    const saved = localStorage.getItem("slideshowSettings");
    if (saved) {
      try {
        const parsed = JSON.parse(saved);
        // Merge saved settings with defaults, ensuring all properties exist
        return {
          ...defaultSettings,
          ...Object.fromEntries(
            Object.entries(parsed).filter(([key]) => key in defaultSettings)
          ),
        };
      } catch {
        // If parsing fails, return defaults
      }
    }
  }
  return defaultSettings;
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
    value: Settings[K]
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
