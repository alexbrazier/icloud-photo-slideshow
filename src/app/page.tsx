"use client";
import React, { useState } from "react";
import { useCogVisibility } from "./hooks/useCogVisibility";
import { Slideshow } from "./components/Slideshow";
import { SettingsCog } from "./components/SettingsCog";
import { SettingsModal } from "./components/SettingsModal";
import { useSettings } from "./contexts/SettingsContext";

export default function Home() {
  const { albumId } = useSettings();
  const cogVisible = useCogVisibility();
  const [settingsOpen, setSettingsOpen] = useState(false);

  return (
    <>
      <Slideshow albumId={albumId} />
      <SettingsCog visible={cogVisible} onClick={() => setSettingsOpen(true)} />
      <SettingsModal
        open={settingsOpen}
        onClose={() => setSettingsOpen(false)}
      />
    </>
  );
}
