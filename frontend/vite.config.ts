import react from '@vitejs/plugin-react'
import { defineConfig } from 'vite'

// The dev server proxies the two backend surfaces the console needs. Keeping the
// browser on one origin makes the XSRF cookie a first-party cookie, so the
// double-submit header works without CORS configuration or credentials mode
// surprises.
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
      '/actuator': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
    },
  },
})
