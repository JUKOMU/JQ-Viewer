/// <reference types="vitest" />

import legacy from '@vitejs/plugin-legacy'
import vue from '@vitejs/plugin-vue'
import path from 'path'
import { defineConfig } from 'vite'
import Components from 'unplugin-vue-components/vite'

function runtimePlatform(mode: string) {
  if (mode !== 'desktop') return 'android'
  const platform = process.env.JQ_VIEWER_PLATFORM
  if (platform !== 'windows' && platform !== 'macos' && platform !== 'linux') {
    throw new Error('JQ_VIEWER_PLATFORM must be windows, macos, or linux')
  }
  return platform
}

// https://vitejs.dev/config/
export default defineConfig(({ mode }) => ({
  define: {
    __JQ_RUNTIME_PLATFORM__: JSON.stringify(runtimePlatform(mode)),
  },
  plugins: [
    vue(),
    legacy(),
    Components({
      resolvers: [
        (name) => {
          if (name.startsWith('Ion')) {
            return {
              name,
              from: '@ionic/vue',
            }
          }
        },
      ],
    }),
  ],
  resolve: {
    alias: {
      '@': path.resolve(__dirname, './src'),
    },
  },
  test: {
    globals: true,
    environment: 'jsdom',
    environmentOptions: {
      jsdom: {
        url: 'http://localhost',
      },
    },
    setupFiles: ['./tests/setup.ts'],
  },
}))
