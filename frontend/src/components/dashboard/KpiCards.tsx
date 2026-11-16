import type { ReactNode } from 'react'
import type { DashboardStats } from '@/types'
import { formatDuration, formatPercent } from './format'

function KpiCard({ label, value, hint }: { label: string; value: ReactNode; hint: string }) {
    return (
        <div role="group" aria-label={label} className="rounded-lg bg-white p-4 shadow">
            <p className="text-sm font-medium text-gray-500">{label}</p>
            <p className="mt-1 text-2xl font-bold text-gray-900">{value}</p>
            <p className="mt-1 text-xs text-gray-500">{hint}</p>
        </div>
    )
}

/**
 * The four headline figures. Mind the two different denominators:
 * - Completion rate: share of ALL orders in the window that are completed (same base as the status chart).
 * - Failure rate: failed / (completed + failed) over LLM generations that have ENDED; plans that are still
 *   waiting or running, and manual uploads, are not part of it. So the two rates do not add up to 100%.
 */
export function KpiCards({ stats }: { stats: DashboardStats }) {
    const completedShare = stats.statusBreakdown
        .filter((entry) => entry.status === 'completed')
        .reduce((sum, entry) => sum + entry.share, 0)

    return (
        <section aria-label="Key figures" className="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-4">
            <KpiCard
                label="Total orders"
                value={stats.kpi.totalOrders}
                hint={`Created in the last ${stats.days} days`}
            />
            <KpiCard
                label="Completion rate"
                value={formatPercent(completedShare)}
                hint="Share of all orders that are completed"
            />
            <KpiCard
                label="Failure rate"
                value={formatPercent(stats.kpi.failureRate)}
                hint="Share of finished LLM generations that failed"
            />
            <KpiCard
                label="Avg generation time"
                value={formatDuration(stats.kpi.avgGenerationSeconds)}
                hint="Completed LLM generations, including retries"
            />
        </section>
    )
}
