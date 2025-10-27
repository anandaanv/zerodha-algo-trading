import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

// https://vitejs.dev/config/
export default defineConfig(({ mode }) => ({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: mode === 'development' ? {
      "/api": "http://localhost:8080"
    } : undefined
  },
  build: {
    outDir: 'dist',
    sourcemap: mode === 'development'
  }
}));
