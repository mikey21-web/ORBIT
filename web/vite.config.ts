import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

const ORBIT = process.env.ORBIT_URL || "http://127.0.0.1:8765";

export default defineConfig({
  base: "./",
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      "/api": {
        target: ORBIT,
        changeOrigin: true,
        rewrite: (path) => path.replace(/^\/api/, ""),
      },
    },
  },
});
