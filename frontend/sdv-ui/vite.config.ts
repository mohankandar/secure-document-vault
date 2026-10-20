import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

export default defineConfig({
  plugins: [react()],
  server: {
    host: '127.0.0.1',
    port: 5183,
    strictPort: true,
    allowedHosts: true,
    proxy: {
      '/api': 'http://localhost:8280',
      '/actuator': 'http://localhost:8280'
    }
  }
})
