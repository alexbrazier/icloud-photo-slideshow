import React from "react";
import { useSettings } from "../contexts/SettingsContext";

interface SettingsModalProps {
  open: boolean;
  onClose: () => void;
}

export function SettingsModal({ open, onClose }: SettingsModalProps) {
  const {
    transitionTime,
    orientationFilter,
    showTimerBar,
    albumId,
    handleTransitionChange,
    handleOrientationChange,
    handleShowTimerBarChange,
    handleAlbumIdChange,
  } = useSettings();

  if (!open) return null;
  return (
    <div
      style={{
        position: "fixed",
        top: 0,
        left: 0,
        width: "100vw",
        height: "100vh",
        background: "rgba(60,60,60,0.7)",
        display: "flex",
        alignItems: "center",
        justifyContent: "center",
        zIndex: 100,
        opacity: 1,
        pointerEvents: "auto",
        transition: "opacity 0.3s",
      }}
    >
      <div
        style={{
          background: "rgba(255,255,255,0.95)",
          borderRadius: 16,
          padding: "32px 24px 24px 24px",
          minWidth: 280,
          boxShadow: "0 4px 32px rgba(0,0,0,0.15)",
          display: "flex",
          flexDirection: "column",
          gap: 20,
          alignItems: "stretch",
          position: "relative",
        }}
      >
        <label
          htmlFor="transition-time"
          style={{ fontSize: "1rem", color: "#333", marginBottom: 8 }}
        >
          Transition Time (seconds):
          <input
            type="number"
            id="transition-time"
            min={1}
            max={600}
            step={1}
            value={transitionTime}
            onChange={(e) => handleTransitionChange(Number(e.target.value))}
            style={{
              width: 80,
              padding: "4px 8px",
              fontSize: "1rem",
              borderRadius: 6,
              border: "1px solid #bbb",
              marginLeft: 8,
            }}
          />
        </label>
        <label
          htmlFor="orientation-filter"
          style={{ fontSize: "1rem", color: "#333", marginBottom: 8 }}
        >
          Show:
          <select
            id="orientation-filter"
            value={orientationFilter}
            onChange={(e) =>
              handleOrientationChange(
                e.target.value as "all" | "landscape" | "portrait"
              )
            }
            style={{
              padding: "4px 8px",
              fontSize: "1rem",
              borderRadius: 6,
              border: "1px solid #bbb",
              marginLeft: 8,
            }}
          >
            <option value="all">All Images</option>
            <option value="landscape">Landscape Only</option>
            <option value="portrait">Portrait Only</option>
          </select>
        </label>
        <label
          htmlFor="show-timer-bar"
          style={{ fontSize: "1rem", color: "#333", marginBottom: 8 }}
        >
          <input
            type="checkbox"
            id="show-timer-bar"
            checked={showTimerBar}
            onChange={(e) => handleShowTimerBarChange(e.target.checked)}
            style={{ marginRight: 8 }}
          />
          Show timer bar
        </label>
        <label
          htmlFor="album-id"
          style={{ fontSize: "1rem", color: "#333", marginBottom: 8 }}
        >
          iCloud Album ID:
          <input
            type="text"
            id="album-id"
            value={albumId}
            onChange={(e) => handleAlbumIdChange(e.target.value)}
            style={{
              width: 180,
              padding: "4px 8px",
              fontSize: "1rem",
              borderRadius: 6,
              border: "1px solid #bbb",
              marginLeft: 8,
            }}
          />
        </label>
        <button
          onClick={onClose}
          style={{
            position: "static",
            alignSelf: "end",
            marginTop: 12,
            background: "none",
            border: "none",
            fontSize: "1.5rem",
            color: "#888",
            cursor: "pointer",
          }}
          title="Close"
        >
          Close
        </button>
      </div>
    </div>
  );
}
