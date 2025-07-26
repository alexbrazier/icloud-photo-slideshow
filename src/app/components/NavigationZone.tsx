import React from "react";

interface NavigationZoneProps {
  side: "left" | "right";
  onClick: () => void;
  controlsVisible?: boolean;
}

function ChevronIcon({ side }: { side: "left" | "right" }) {
  return (
    <svg
      width="64"
      height="64"
      viewBox="0 0 24 24"
      fill="none"
      stroke="white"
      strokeWidth="2"
      strokeLinecap="round"
      strokeLinejoin="round"
      style={{
        filter: "drop-shadow(0 2px 4px rgba(0,0,0,0.3))",
      }}
    >
      {side === "left" ? (
        <polyline points="15,18 9,12 15,6" />
      ) : (
        <polyline points="9,18 15,12 9,6" />
      )}
    </svg>
  );
}

export function NavigationZone({
  side,
  onClick,
  controlsVisible,
}: NavigationZoneProps) {
  return (
    <div
      style={{
        position: "absolute",
        [side]: 0,
        top: 0,
        width: "25vw",
        height: "100vh",
        zIndex: 10,
        cursor: "pointer",
        display: "flex",
        alignItems: "center",
        justifyContent: side === "left" ? "flex-start" : "flex-end",
        paddingLeft: side === "left" ? "20px" : "0",
        paddingRight: side === "right" ? "20px" : "0",
      }}
      onClick={() => onClick()}
    >
      <div
        style={{
          opacity: controlsVisible ? 0.8 : 0,
          transition: "opacity 0.3s ease-in-out",
          pointerEvents: "none",
        }}
      >
        <ChevronIcon side={side} />
      </div>
    </div>
  );
}
