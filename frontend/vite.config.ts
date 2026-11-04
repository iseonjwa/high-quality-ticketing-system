/// <reference types="vitest/config" />
import { fileURLToPath, URL } from 'node:url'
import tailwindcss from '@tailwindcss/vite'
import react from '@vitejs/plugin-react'
import { msw } from 'msw/vite'
import { defineConfig } from 'vite'

// Note: 브라우저는 개발 서버만 보고, /api 요청은 개발 서버가 Gateway로 넘긴다.
// 배포의 nginx 중계와 같은 같은 출처 구조라 Gateway에 CORS 설정이 필요 없다(ADR 0040 4절).
const apiProxyTarget = process.env.API_PROXY_TARGET ?? 'http://localhost:8080'

export default defineConfig({
  // msw(): 개발 서버가 목 워커 스크립트(/mockServiceWorker.js)를 내준다. public/에 두지 않아
  // 운영 빌드 결과에 워커 파일이 들어가지 않는다.
  plugins: [react(), tailwindcss(), msw()],
  resolve: {
    alias: { '@': fileURLToPath(new URL('./src', import.meta.url)) },
  },
  server: {
    proxy: { '/api': { target: apiProxyTarget } },
  },
  // Note: 앱 코드와 라이브러리를 한 파일로 내면 500 kB 경고를 넘는다(ISE-112). 라이브러리를 따로 묶어
  // 앱 코드만 바뀐 배포에서 브라우저가 라이브러리 파일을 캐시에서 다시 쓰게 한다. React 묶음은 거의 바뀌지 않아 따로 둔다.
  build: {
    rolldownOptions: {
      output: {
        codeSplitting: {
          groups: [
            {
              name: 'react',
              test: /node_modules[\\/](react|react-dom|react-router|scheduler)[\\/]/,
              priority: 2,
            },
            { name: 'vendor', test: /node_modules[\\/]/, priority: 1 },
          ],
        },
      },
    },
  },
  test: {
    environment: 'jsdom',
    setupFiles: ['./src/test/setup.ts'],
  },
})
