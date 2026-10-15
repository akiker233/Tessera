import { defineConfig } from "astro/config";

export default defineConfig({
  // 输出 /index.html /privacy.html /terms.html /404.html，与既有链接保持一致
  build: { format: "file" },
  devToolbar: { enabled: false },
});
