"use client";
import React, { createContext, useContext, useState, ReactNode } from "react";

const DEFAULT_TRANSITION_SECS = 5;

interface Settings {
  transitionTime: number;
  orientationFilter: "all" | "landscape" | "portrait";
  showTimerBar: boolean;
  albumId: string;
}

interface SettingsContextType {
  transitionTime: number;
  orientationFilter: "all" | "landscape" | "portrait";
  showTimerBar: boolean;
  albumId: string;
  handleTransitionChange: (val: number) => void;
  handleOrientationChange: (val: "all" | "landscape" | "portrait") => void;
  handleShowTimerBarChange: (val: boolean) => void;
  handleAlbumIdChange: (val: string) => void;
}

const SettingsContext = createContext<SettingsContextType | undefined>(
  undefined
);

const loadSettings = (): Settings => {
  if (typeof window !== "undefined") {
    const saved = localStorage.getItem("slideshowSettings");
    if (saved) {
      try {
        const parsed = JSON.parse(saved);
        return {
          transitionTime: parsed.transitionTime || DEFAULT_TRANSITION_SECS,
          orientationFilter: parsed.orientationFilter || "all",
          showTimerBar: !!parsed.showTimerBar,
          albumId: parsed.albumId || "",
        };
      } catch {}
    }
  }
  return {
    transitionTime: DEFAULT_TRANSITION_SECS,
    orientationFilter: "all",
    showTimerBar: false,
    albumId: "",
  };
};

const saveSettings = (settings: Settings) => {
  if (typeof window !== "undefined") {
    localStorage.setItem("slideshowSettings", JSON.stringify(settings));
  }
};

export function SettingsProvider({ children }: { children: ReactNode }) {
  const [settings, setSettings] = useState<Settings>(loadSettings);

  const handleTransitionChange = (val: number) => {
    if (isNaN(val) || val < 1) val = 1;
    if (val > 600) val = 600;
    const newSettings = { ...settings, transitionTime: val };
    setSettings(newSettings);
    saveSettings(newSettings);
  };

  const handleOrientationChange = (val: "all" | "landscape" | "portrait") => {
    const newSettings = { ...settings, orientationFilter: val };
    setSettings(newSettings);
    saveSettings(newSettings);
  };

  const handleShowTimerBarChange = (val: boolean) => {
    const newSettings = { ...settings, showTimerBar: val };
    setSettings(newSettings);
    saveSettings(newSettings);
  };

  const handleAlbumIdChange = (val: string) => {
    const newSettings = { ...settings, albumId: val };
    setSettings(newSettings);
    saveSettings(newSettings);
  };

  return (
    <SettingsContext.Provider
      value={{
        transitionTime: settings.transitionTime,
        orientationFilter: settings.orientationFilter,
        showTimerBar: settings.showTimerBar,
        albumId: settings.albumId,
        handleTransitionChange,
        handleOrientationChange,
        handleShowTimerBarChange,
        handleAlbumIdChange,
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
