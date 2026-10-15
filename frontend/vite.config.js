import react from '@vitejs/plugin-react'
import { defineConfig } from 'vite'

// https://vite.dev/config/
export default defineConfig({
  plugins: [react()],
  test: {
    environment: 'jsdom',
    globals: true,
    setupFiles: './src/test-setup.ts',
  },
  server: {
    proxy: {
      '/api': {
        target: 'http://localhost:1213',
        changeOrigin: true,
      },
    },
  },
})
