import { defineConfig, loadEnv } from 'vite'

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, '.', '')
  const host = env.CAMPUSSHARE_FRONTEND_HOST || 'localhost'
  if (host !== 'localhost' && host !== '127.0.0.1') throw new Error('The frontend must bind to loopback')
  const proxy = {
    '/api': { target: 'http://127.0.0.1:' + (env.CAMPUSSHARE_AGENT_PORT || '8085'), changeOrigin: true },
  }
  return {
    server: { host, port: Number(env.CAMPUSSHARE_FRONTEND_PORT || 5173), strictPort: true, proxy },
    preview: { host, port: Number(env.CAMPUSSHARE_FRONTEND_PORT || 5173), strictPort: true, proxy },
  }
})
