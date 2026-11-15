import { describe, expect, it } from 'vitest'
import type { DashboardProviderStats } from '@/types'
import { compareProviders, defaultDirection, sortProviders, type ProviderSort } from './providerSort'

function provider(overrides: Partial<DashboardProviderStats>): DashboardProviderStats {
  return { providerId: 1, providerName: 'Dr. A', orderCount: 1, failedCount: 0, avgGenerationSeconds: 10, ...overrides }
}

const names = (rows: DashboardProviderStats[]) => rows.map((r) => r.providerName)

describe('defaultDirection', () => {
  it('names start A-Z, numbers start with the highest', () => {
    expect(defaultDirection('providerName')).toBe('asc')
    expect(defaultDirection('orderCount')).toBe('desc')
    expect(defaultDirection('failedCount')).toBe('desc')
    expect(defaultDirection('avgGenerationSeconds')).toBe('desc')
  })
})

describe('compareProviders', () => {
  const a = provider({ providerName: 'Dr. A', orderCount: 5, avgGenerationSeconds: 10 })
  const b = provider({ providerName: 'Dr. B', orderCount: 2, avgGenerationSeconds: 30 })
  const none = provider({ providerName: 'Dr. N', avgGenerationSeconds: null })
  const asc = (key: ProviderSort['key']): ProviderSort => ({ key, direction: 'asc' })
  const desc = (key: ProviderSort['key']): ProviderSort => ({ key, direction: 'desc' })

  it('sorts names alphabetically in both directions', () => {
    expect(compareProviders(a, b, asc('providerName'))).toBeLessThan(0)
    expect(compareProviders(a, b, desc('providerName'))).toBeGreaterThan(0)
  })

  it('sorts numbers ascending and descending', () => {
    expect(compareProviders(a, b, asc('orderCount'))).toBeGreaterThan(0) // 5 vs 2
    expect(compareProviders(a, b, desc('orderCount'))).toBeLessThan(0)
    expect(compareProviders(a, b, asc('avgGenerationSeconds'))).toBeLessThan(0) // 10 vs 30
  })

  it('equal numbers fall back to the name, whatever the direction', () => {
    const tie = provider({ providerName: 'Dr. Z', orderCount: 5 })
    expect(compareProviders(a, tie, asc('orderCount'))).toBeLessThan(0)
    expect(compareProviders(a, tie, desc('orderCount'))).toBeLessThan(0)
  })

  it('a missing average is always last: seen from either side, in both directions', () => {
    for (const sort of [asc('avgGenerationSeconds'), desc('avgGenerationSeconds')]) {
      expect(compareProviders(none, a, sort)).toBe(1)
      expect(compareProviders(a, none, sort)).toBe(-1)
    }
  })

  it('two missing averages are ordered by name', () => {
    const other = provider({ providerName: 'Dr. O', avgGenerationSeconds: null })
    expect(compareProviders(none, other, desc('avgGenerationSeconds'))).toBeLessThan(0) // "Dr. N" before "Dr. O"
  })
})

describe('sortProviders', () => {
  const rows = [
    provider({ providerId: 1, providerName: 'Dr. Beta', orderCount: 4, failedCount: 0, avgGenerationSeconds: 30 }),
    provider({ providerId: 2, providerName: 'Dr. Alpha', orderCount: 3, failedCount: 1, avgGenerationSeconds: 15 }),
    provider({ providerId: 3, providerName: 'Dr. Gamma', orderCount: 1, failedCount: 0, avgGenerationSeconds: null }),
  ]

  it('does not change the array it was given', () => {
    const before = names(rows)
    sortProviders(rows, { key: 'providerName', direction: 'asc' })
    expect(names(rows)).toEqual(before)
  })

  it.each([
    ['providerName', 'asc', ['Dr. Alpha', 'Dr. Beta', 'Dr. Gamma']],
    ['providerName', 'desc', ['Dr. Gamma', 'Dr. Beta', 'Dr. Alpha']],
    ['orderCount', 'desc', ['Dr. Beta', 'Dr. Alpha', 'Dr. Gamma']],
    ['orderCount', 'asc', ['Dr. Gamma', 'Dr. Alpha', 'Dr. Beta']],
    ['failedCount', 'desc', ['Dr. Alpha', 'Dr. Beta', 'Dr. Gamma']], // tie 0/0 -> name
    ['avgGenerationSeconds', 'asc', ['Dr. Alpha', 'Dr. Beta', 'Dr. Gamma']],
    ['avgGenerationSeconds', 'desc', ['Dr. Beta', 'Dr. Alpha', 'Dr. Gamma']], // null stays last
  ] as const)('%s %s -> %j', (key, direction, expected) => {
    expect(names(sortProviders(rows, { key, direction }))).toEqual(expected)
  })
})
