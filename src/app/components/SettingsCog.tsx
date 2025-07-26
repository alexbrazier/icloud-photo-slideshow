import React from "react";

interface SettingsCogProps {
  visible: boolean;
  onClick: () => void;
}

export function SettingsCog({ visible, onClick }: SettingsCogProps) {
  return (
    <div
      style={{
        position: "absolute",
        top: 24,
        right: 24,
        width: 40,
        height: 40,
        background: "rgba(80,80,80,0.6)",
        borderRadius: "50%",
        display: "flex",
        alignItems: "center",
        justifyContent: "center",
        cursor: "pointer",
        zIndex: 20,
        opacity: visible ? 1 : 0,
        transition: "opacity 0.3s",
      }}
      onClick={onClick}
      title="Settings"
    >
      <svg
        viewBox="0 0 24 24"
        width={24}
        height={24}
        fill="rgba(255,255,255,0.8)"
      >
        <path d="M19.14,12.94a7.07,7.07,0,0,0,.05-1,7.07,7.07,0,0,0-.05-1l2.11-1.65a.5.5,0,0,0,.12-.64l-2-3.46a.5.5,0,0,0-.61-.22l-2.49,1a7,7,0,0,0-1.73-1l-.38-2.65A.5.5,0,0,0,13,2h-4a.5.5,0,0,0-.5.42l-.38,2.65a7,7,0,0,0-1.73,1l-2.49-1a.5.5,0,0,0-.61.22l-2,3.46a.5.5,0,0,0,.12.64L4.86,10a7.07,7.07,0,0,0-.05,1,7.07,7.07,0,0,0,.05,1l-2.11,1.65a.5.5,0,0,0-.12.64l2,3.46a.5.5,0,0,0,.61.22l2.49-1a7,7,0,0,0,1.73,1l.38,2.65A.5.5,0,0,0,9,22h4a.5.5,0,0,0,.5-.42l.38-2.65a7,7,0,0,0,1.73-1l2.49,1a.5.5,0,0,0,.61-.22l2-3.46a.5.5,0,0,0-.12-.64ZM12,15.5A3.5,3.5,0,1,1,15.5,12,3.5,3.5,0,0,1,12,15.5Z" />
      </svg>
    </div>
  );
}
