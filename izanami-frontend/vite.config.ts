import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";
import { visualizer } from "rollup-plugin-visualizer";

// https://vitejs.dev/config/
export default defineConfig({
  server: {
    port: 3000,
    proxy: {
      // string shorthand
      "/api": "http://localhost:9000",
      "/swagger.json": "http://localhost:9000",
    },
  },
  plugins: [react()],
  build: {
    rollupOptions: {
      plugins: [visualizer()],
    },
    minify: "terser",
    terserOptions: {
      format: {
        keep_quoted_props: true,
      },
    },
  },
  define: (process.env.NODE_ENV === "development" ? { global: "window" } : {}),
});
