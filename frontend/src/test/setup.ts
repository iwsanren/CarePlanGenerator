import '@testing-library/jest-dom/vitest'
import { afterEach } from 'vitest'
import { cleanup } from '@testing-library/react'

// jsdom's Blob lacks .text() (real browsers have it), but services/api.ts uses it
// to read JSON error bodies of failed downloads. Provide it only where missing.
if (!Blob.prototype.text) {
  Blob.prototype.text = function text(this: Blob) {
    return new Promise<string>((resolve, reject) => {
      const reader = new FileReader()
      reader.onload = () => resolve(String(reader.result))
      reader.onerror = () => reject(reader.error)
      reader.readAsText(this)
    })
  }
}

// React Router 6 prints a "future flag" migration notice for every <MemoryRouter>.
// main.tsx does not opt in to those flags, so tests must not either (they would then
// behave differently from production). Drop only that notice; every other warning stays.
const originalWarn = console.warn
console.warn = (...args: unknown[]) => {
  if (typeof args[0] === 'string' && args[0].startsWith('⚠️ React Router Future Flag Warning')) return
  originalWarn(...args)
}

// Globals are off in this project, so React Testing Library cannot register its
// own auto-cleanup. Unmount rendered trees after each test so they do not leak.
afterEach(() => {
  cleanup()
})
