"use client";
import React, { useState } from "react";
import { useCogVisibility } from "./hooks/useCogVisibility";
import { Slideshow } from "./components/Slideshow";
import { SettingsCog } from "./components/SettingsCog";
import { SettingsModal } from "./components/SettingsModal";
import { WeatherWidget } from "./components/WeatherWidget";
import { ClockWidget } from "./components/ClockWidget";
import { useSettings } from "./contexts/SettingsContext";

export default function Home() {
  const { albumId, showWeather, showClock } = useSettings();
  const controlsVisible = useCogVisibility();
  const [settingsOpen, setSettingsOpen] = useState(false);

  return (
    <>
      <Slideshow albumId={albumId} controlsVisible={controlsVisible} />
      {showWeather && <WeatherWidget />}
      {showClock && <ClockWidget />}
      <SettingsCog
        visible={controlsVisible}
        onClick={() => setSettingsOpen(true)}
      />
      <SettingsModal
        open={settingsOpen}
        onClose={() => setSettingsOpen(false)}
      />
    </>
  );
}
