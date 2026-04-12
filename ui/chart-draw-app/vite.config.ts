import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

// https://vitejs.dev/config/
export default defineConfig(({ mode }) => ({
  plugins: [react()],
  server: {
    port: 5173,
    host: true,
    allowedHosts: ['tradeapp.dheemantech.in'],
  },
  build: {
    outDir: 'dist',
    sourcemap: mode === 'development'
  }
}));
