import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import tailwindcss from '@tailwindcss/vite';
import { resolve } from 'node:path';
export default defineConfig({
  plugins: [react(), tailwindcss()],
  server: { proxy: { '/api': process.env.MIRROR_API_TARGET ?? 'http://127.0.0.1:8080' } },
  build: { rollupOptions: { input: { admin: resolve(import.meta.dirname, 'index.html'), dashboard: resolve(import.meta.dirname, 'dashboard.html'), receiver: resolve(import.meta.dirname, 'receiver.html') } } },
});
