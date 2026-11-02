import js from '@eslint/js'
import prettier from 'eslint-config-prettier/flat'
import reactHooks from 'eslint-plugin-react-hooks'
import reactRefresh from 'eslint-plugin-react-refresh'
import { defineConfig, globalIgnores } from 'eslint/config'
import globals from 'globals'
import tseslint from 'typescript-eslint'

export default defineConfig([
  globalIgnores(['dist']),
  {
    files: ['**/*.{ts,tsx}'],
    extends: [
      js.configs.recommended,
      tseslint.configs.recommended,
      reactHooks.configs.flat.recommended,
      reactRefresh.configs.vite,
    ],
    languageOptions: {
      globals: globals.browser,
    },
  },
  {
    // 클래스 합치기는 type-* 글자 토큰을 아는 src/lib/utils.ts의 cn만 쓴다.
    files: ['src/**/*.{ts,tsx}'],
    ignores: ['src/lib/utils.ts'],
    rules: {
      'no-restricted-imports': [
        'error',
        { paths: [{ name: 'cn', message: "type-* 글자 토큰을 아는 '@/lib/utils'의 cn을 쓴다" }] },
      ],
    },
  },
  {
    // shadcn/ui 컴포넌트는 컴포넌트와 스타일 변형(buttonVariants 등)을 한 파일에서 함께 내보낸다.
    files: ['src/components/ui/**/*.tsx'],
    rules: { 'react-refresh/only-export-components': 'off' },
  },
  // 포맷 규칙은 Prettier가 맡는다. 겹치는 ESLint 규칙을 끈다.
  prettier,
])
