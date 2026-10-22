import react from "@vitejs/plugin-react";
import { defineConfig } from "vite";

const target = process.env.API_URL ?? "http://localhost:3000";

export default defineConfig({
  plugins: [react()],
  server: { proxy: { "/api": target, "/health": target } },
  preview: { proxy: { "/api": target, "/health": target } },
});
