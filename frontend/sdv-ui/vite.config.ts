import { defineConfig, loadEnv } from 'vite'
import react from '@vitejs/plugin-react'

// Local development. The defaults are the usual local stack; Test-SDV-DemoSeed.ps1 (task 0023) runs a second copy
// against an app started on a scratch database, with SDV_UI_PORT and SDV_API_TARGET set.
export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, '.', 'SDV_')
  const api = env.SDV_API_TARGET || 'http://127.0.0.1:8280'
  return {
    plugins: [react()],
    server: {
      host: '127.0.0.1',
      port: Number(env.SDV_UI_PORT || 5183),
      strictPort: true,
      proxy: {
        '/api': api,
        '/actuator': api
      }
    }
  }
})
