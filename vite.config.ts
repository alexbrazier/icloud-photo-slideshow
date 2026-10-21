import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

export default defineConfig({
  plugins: [react()],
  build: { outDir: "dist/ui", emptyOutDir: true },
  server: {
    port: 8085,
    // API server run by `npm run dev:server`
    proxy: { "/api": "http://localhost:8086" },
  },
});
