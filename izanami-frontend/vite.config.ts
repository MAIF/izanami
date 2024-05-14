import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";
import { visualizer } from "rollup-plugin-visualizer";
import * as path from "path";

// https://vitejs.dev/config/
export default defineConfig({
  server: {
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
  define: {
    // See https://stackoverflow.com/questions/72114775/vite-global-is-not-defined
    global: "window",
  },
  resolve: {
    alias: [
      {
        find: "#swagger-ui",
        replacement: path.resolve(
          __dirname,
          "node_modules/swagger-ui-react/swagger-ui-bundle.js"
        ),
      },
    ],
  },
});
