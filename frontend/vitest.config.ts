import { defineConfig, mergeConfig } from 'vitest/config'
import viteConfig from './vite.config'

// Date formatting uses the machine's time zone; pin it so tests give the same
// result on a developer laptop and on the CI runner. Worker processes inherit this.
process.env.TZ = 'UTC'

/**
 * Coverage thresholds (percent). One place to tune them: raise a number as
 * coverage improves; a run that falls below any of them fails `npm run test:coverage`.
 * Initial values = the real level reached when the first test suite was added
 * (rounded down): statements 100, branches 97.01, functions 100, lines 100.
 * The agreed long-term target is 80; lower a number here if a strict value gets in the way.
 */
const COVERAGE_THRESHOLDS = {
  statements: 100,
  branches: 97,
  functions: 100,
  lines: 100,
}

// Inherit the plugins and the "@" alias from vite.config.ts so tests resolve
// imports exactly like the app does.
export default mergeConfig(
  viteConfig,
  defineConfig({
    test: {
      environment: 'jsdom',
      setupFiles: ['./src/test/setup.ts'],
      // Whitelist: only *.test.ts(x) under src are unit tests. Playwright specs
      // (e2e/*.spec.ts) need a running stack and a real browser, so Vitest must never load them.
      include: ['src/**/*.test.{ts,tsx}'],
      // Explicit blacklist as a second guard, in case someone widens `include` later.
      exclude: ['e2e/**', 'node_modules/**', 'dist/**', 'coverage/**', 'test-results/**'],
      coverage: {
        provider: 'v8',
        reporter: ['text', 'html', 'lcov'],
        include: ['src/**/*.{ts,tsx}'],
        exclude: [
          'src/**/*.test.{ts,tsx}',
          'src/test/**',
          'src/main.tsx', // bootstrap only: creates the root and providers
          'src/vite-env.d.ts',
          'src/types/index.ts', // type declarations only, no runtime code
        ],
        thresholds: COVERAGE_THRESHOLDS,
      },
    },
  }),
)
