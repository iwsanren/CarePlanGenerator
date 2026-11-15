import '@testing-library/jest-dom/vitest'
import { cloneElement, type ReactElement } from 'react'
import { afterEach, vi } from 'vitest'
import { cleanup } from '@testing-library/react'

// Recharts' ResponsiveContainer measures its parent with ResizeObserver, and jsdom has no layout:
// every size is 0, so no chart would draw anything. Give the container a fixed 800 px width and
// pass the requested height on to the chart; everything else in Recharts is the real library.
// Tests assert the data -> text mapping (legend, hidden data tables, labels), never SVG details.
vi.mock('recharts', async (importOriginal) => {
  const actual = await importOriginal<typeof import('recharts')>()
  return {
    ...actual,
    ResponsiveContainer: ({ children, height }: { children: ReactElement; height?: number | string }) =>
      cloneElement(children, { width: 800, height: typeof height === 'number' ? height : 300 }),
  }
})

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
