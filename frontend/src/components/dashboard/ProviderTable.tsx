import { useState } from 'react'
import type { DashboardProviderStats } from '@/types'
import { cn } from '@/utils/utils'
import { formatDuration } from './format'
import { defaultDirection, sortProviders, type ProviderSort, type ProviderSortKey } from './providerSort'

const COLUMNS: { key: ProviderSortKey; label: string; numeric: boolean }[] = [
    { key: 'providerName', label: 'Provider', numeric: false },
    { key: 'orderCount', label: 'Orders', numeric: true },
    { key: 'failedCount', label: 'Failed', numeric: true },
    { key: 'avgGenerationSeconds', label: 'Avg time', numeric: true },
]

/** Provider table; a click on a column header sorts by it, a second click reverses the order. */
export function ProviderTable({ data }: { data: DashboardProviderStats[] }) {
    // The backend already sends the busiest providers first, which is this starting sort.
    const [sort, setSort] = useState<ProviderSort>({ key: 'orderCount', direction: 'desc' })
    const rows = sortProviders(data, sort)

    const toggle = (key: ProviderSortKey) =>
        setSort((current) =>
            current.key === key
                ? { key, direction: current.direction === 'asc' ? 'desc' : 'asc' }
                : { key, direction: defaultDirection(key) },
        )

    return (
        <section aria-labelledby="provider-table-title" className="rounded-lg bg-white p-4 shadow lg:col-span-2">
            <h2 id="provider-table-title" className="text-base font-semibold text-gray-900">Providers</h2>
            <p className="mb-3 text-xs text-gray-500">
                Top providers by orders. Click a column header to sort. Avg time covers completed LLM generations.
            </p>
            <div className="overflow-x-auto">
                <table className="min-w-full divide-y divide-gray-200">
                    <thead>
                        <tr>
                            {COLUMNS.map((column) => {
                                const active = sort.key === column.key
                                return (
                                    <th
                                        key={column.key}
                                        scope="col"
                                        aria-sort={active ? (sort.direction === 'asc' ? 'ascending' : 'descending') : 'none'}
                                        className={cn('px-3 py-2 text-xs font-medium uppercase text-gray-500',
                                            column.numeric ? 'text-right' : 'text-left')}
                                    >
                                        <button
                                            type="button"
                                            onClick={() => toggle(column.key)}
                                            className="uppercase hover:text-gray-900 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-teal-600"
                                        >
                                            {column.label}
                                            <span aria-hidden="true">{active ? (sort.direction === 'asc' ? ' ▲' : ' ▼') : ''}</span>
                                        </button>
                                    </th>
                                )
                            })}
                        </tr>
                    </thead>
                    <tbody className="divide-y divide-gray-100">
                        {rows.map((row) => (
                            <tr key={row.providerId}>
                                <td className="px-3 py-2 text-sm text-gray-900">{row.providerName}</td>
                                <td className="px-3 py-2 text-right text-sm tabular-nums">{row.orderCount}</td>
                                <td className="px-3 py-2 text-right text-sm tabular-nums">{row.failedCount}</td>
                                <td className="px-3 py-2 text-right text-sm tabular-nums">
                                    {formatDuration(row.avgGenerationSeconds)}
                                </td>
                            </tr>
                        ))}
                    </tbody>
                </table>
            </div>
        </section>
    )
}
