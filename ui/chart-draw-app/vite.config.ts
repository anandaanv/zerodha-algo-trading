import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

// https://vitejs.dev/config/
export default defineConfig(({ mode }) => ({
  plugins: [react()],
  server: {
    port: 5173,
    host: true,
    allowedHosts: ['tradeapp.dheemantech.in'],
    proxy: mode === 'development' ? {
      // "/api": "https://tradeapi.dheemantech.in/kitecon"
      "/api": "http://localhost:8080",
      "/kite-callback": "http://localhost:8080",
      "/ws": { target: "http://localhost:8080", ws: true }
    } : undefined
  },
  build: {
    outDir: 'dist',
    sourcemap: mode === 'development'
  }
}));
