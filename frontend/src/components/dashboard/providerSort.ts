import type { DashboardProviderStats } from '@/types'

export type ProviderSortKey = 'providerName' | 'orderCount' | 'failedCount' | 'avgGenerationSeconds'
export type SortDirection = 'asc' | 'desc'
export interface ProviderSort {
    key: ProviderSortKey
    direction: SortDirection
}

/** The direction a column starts with when it is clicked for the first time: names A-Z, numbers high first. */
export function defaultDirection(key: ProviderSortKey): SortDirection {
    return key === 'providerName' ? 'asc' : 'desc'
}

/**
 * Comparator for the provider table.
 * - Equal values fall back to the provider name, so the order never jumps around between clicks.
 * - A missing average (null) always sorts last, in both directions: "no data" is not "fastest" or "slowest".
 */
export function compareProviders(a: DashboardProviderStats, b: DashboardProviderStats, sort: ProviderSort): number {
    const sign = sort.direction === 'asc' ? 1 : -1
    const byName = a.providerName.localeCompare(b.providerName)
    if (sort.key === 'providerName') return sign * byName

    const x = a[sort.key]
    const y = b[sort.key]
    if (x === y) return byName
    if (x === null) return 1
    if (y === null) return -1
    return sign * (x - y)
}

export function sortProviders(rows: DashboardProviderStats[], sort: ProviderSort): DashboardProviderStats[] {
    return [...rows].sort((a, b) => compareProviders(a, b, sort))
}
