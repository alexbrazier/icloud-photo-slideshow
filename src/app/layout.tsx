import type { Metadata } from "next";
import "./globals.css";
import { SettingsProvider } from "./contexts/SettingsContext";

export const metadata: Metadata = {
  title: "ICloud Photo Slideshow",
  description: "A beautiful slideshow of your iCloud photos",
};

export default function RootLayout({
  children,
}: Readonly<{
  children: React.ReactNode;
}>) {
  return (
    <html lang="en">
      <body>
        <SettingsProvider>{children}</SettingsProvider>
      </body>
    </html>
  );
}
