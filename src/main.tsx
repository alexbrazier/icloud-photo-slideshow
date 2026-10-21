import React from "react";
import { createRoot } from "react-dom/client";
import "@fontsource-variable/inter";
import "./globals.css";
import App from "./App";
import { SettingsProvider } from "./contexts/SettingsContext";

createRoot(document.getElementById("root")!).render(
  <React.StrictMode>
    <SettingsProvider>
      <App />
    </SettingsProvider>
  </React.StrictMode>
);
