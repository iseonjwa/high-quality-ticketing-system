/// <reference types="vitest/config" />
import { fileURLToPath, URL } from 'node:url'
import tailwindcss from '@tailwindcss/vite'
import react from '@vitejs/plugin-react'
import { defineConfig } from 'vite'

// Note: 브라우저는 개발 서버만 보고, /api 요청은 개발 서버가 Gateway로 넘긴다.
// 배포의 nginx 중계와 같은 같은 출처 구조라 Gateway에 CORS 설정이 필요 없다(ADR 0040 4절).
const apiProxyTarget = process.env.API_PROXY_TARGET ?? 'http://localhost:8080'

export default defineConfig({
  plugins: [react(), tailwindcss()],
  resolve: {
    alias: { '@': fileURLToPath(new URL('./src', import.meta.url)) },
  },
  server: {
    proxy: { '/api': { target: apiProxyTarget } },
  },
  test: {
    environment: 'jsdom',
    setupFiles: ['./src/test/setup.ts'],
  },
})
