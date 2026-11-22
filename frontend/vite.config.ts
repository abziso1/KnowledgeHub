import vue from '@vitejs/plugin-vue'
import { defineConfig } from 'vitest/config'

export default defineConfig({
  plugins: [vue()],
  server: {
    proxy: {
      '/api': process.env.KH_DEV_API_TARGET || 'http://127.0.0.1:8080',
    },
  },
  test: {
    include: ['src/**/*.test.ts'],
  },
})
