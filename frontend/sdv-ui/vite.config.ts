import { defineConfig, loadEnv } from 'vite'
import react from '@vitejs/plugin-react'

// Task 0065: the built page carries a Content-Security-Policy as a floor; the serving proxy sends the stricter one (install
// runbook). Not in development, where Vite adds inline scripts. connect-src: the login system's token address is https.
const CSP = "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline' https://fonts.googleapis.com; img-src 'self' blob: data:; font-src 'self' https://fonts.gstatic.com; connect-src 'self' https:; frame-src 'self' blob:; object-src 'none'; base-uri 'none'; form-action 'self'"
const csp = () => ({
  name: 'vero-csp', apply: 'build' as const,
  transformIndexHtml: (html: string) => html.replace('<meta charset="UTF-8" />', `<meta charset="UTF-8" />\n    <meta http-equiv="Content-Security-Policy" content="${CSP}" />\n    <meta name="referrer" content="no-referrer" />`),
})

// Local development. The defaults are the usual local stack; Test-SDV-DemoSeed.ps1 (task 0023) runs a second copy
// against an app started on a scratch database, with SDV_UI_PORT and SDV_API_TARGET set.
export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, '.', 'SDV_')
  const api = env.SDV_API_TARGET || 'http://127.0.0.1:8280'
  return {
    plugins: [react(), csp()],
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
