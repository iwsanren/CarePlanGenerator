import { describe, expect, it } from 'vitest'
import { cn, formatDate, formatDateTime, getStatusColor } from './utils'

describe('cn', () => {
  it('joins truthy class names and drops falsy ones', () => {
    const active = false as boolean // a runtime flag, as in real components: cn('x', active && 'y')
    expect(cn('a', active && 'b', undefined, null, 'c')).toBe('a c')
  })

  it('lets a later Tailwind class win over a conflicting earlier one', () => {
    expect(cn('px-2 py-1', 'px-4')).toBe('py-1 px-4')
  })
})

describe('getStatusColor', () => {
  it.each([
    ['pending', 'bg-yellow-100 text-yellow-800'],
    ['processing', 'bg-blue-100 text-blue-800'],
    ['completed', 'bg-green-100 text-green-800'],
    ['failed', 'bg-red-100 text-red-800'],
  ])('maps %s to its badge colours', (status, classes) => {
    expect(getStatusColor(status)).toBe(classes)
  })

  it('falls back to grey for an unknown status', () => {
    expect(getStatusColor('something-else')).toBe('bg-gray-100 text-gray-800')
  })
})

// vitest.config.ts pins TZ=UTC, so these strings are stable across machines.
describe('date formatting', () => {
  it('formatDate renders a short US date', () => {
    expect(formatDate('2026-08-30T12:34:56Z')).toBe('Aug 30, 2026')
  })

  it('formatDateTime renders date plus time', () => {
    expect(formatDateTime('2026-08-30T12:34:56Z')).toBe('Aug 30, 2026, 12:34 PM')
  })
})
