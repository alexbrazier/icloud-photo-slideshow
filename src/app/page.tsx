"use client";
import React, { useState } from "react";
import { useCogVisibility } from "./hooks/useCogVisibility";
import { Slideshow } from "./components/Slideshow";
import { SettingsCog } from "./components/SettingsCog";
import { SettingsModal } from "./components/SettingsModal";

export default function Home() {
  const albumId = process.env.NEXT_PUBLIC_ICLOUD_ALBUM_ID as string | undefined;
  const cogVisible = useCogVisibility();
  const [settingsOpen, setSettingsOpen] = useState(false);

  if (!albumId) {
    return (
      <div>
        Error: NEXT_PUBLIC_ICLOUD_ALBUM_ID environment variable is not set
      </div>
    );
  }

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
