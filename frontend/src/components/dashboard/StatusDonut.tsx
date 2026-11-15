import { Pie, PieChart, ResponsiveContainer } from 'recharts'
import type { DashboardStatusShare, OrderStatus } from '@/types'
import { ChartCard } from './ChartCard'
import { COLORS } from './colors'
import { formatPercent } from './format'

const STATUS_STYLE: Record<OrderStatus, { label: string; color: string }> = {
    pending: { label: 'Pending', color: COLORS.pending },
    processing: { label: 'Processing', color: COLORS.processing },
    completed: { label: 'Completed', color: COLORS.primary },
    failed: { label: 'Failed', color: COLORS.failed },
}

export function StatusDonut({ data }: { data: DashboardStatusShare[] }) {
    const slices = data.map((entry) => ({
        name: STATUS_STYLE[entry.status].label,
        value: entry.count,
        fill: STATUS_STYLE[entry.status].color,
    }))
    const total = data.reduce((sum, entry) => sum + entry.count, 0)
    const summary = data
        .map((entry) => `${STATUS_STYLE[entry.status].label} ${entry.count} (${formatPercent(entry.share)})`)
        .join(', ')

    return (
        <ChartCard
            title="Order status"
            subtitle="Percentages are the share of all orders"
            summary={`Donut chart of ${total} orders by status: ${summary}.`}
            legend={
                // The legend is the text version of the chart: colour is never the only cue.
                <ul aria-label="Order status legend" className="mt-2 space-y-1 text-sm">
                    {data.map((entry) => (
                        <li key={entry.status} className="flex items-center justify-between gap-2">
                            <span className="flex items-center gap-2">
                                <span
                                    aria-hidden="true"
                                    className="inline-block h-3 w-3 rounded-sm"
                                    style={{ backgroundColor: STATUS_STYLE[entry.status].color }}
                                />
                                {STATUS_STYLE[entry.status].label}
                            </span>
                            <span className="tabular-nums text-gray-700">
                                {entry.count} · {formatPercent(entry.share)}
                            </span>
                        </li>
                    ))}
                </ul>
            }
        >
            <ResponsiveContainer width="100%" height={200}>
                <PieChart>
                    <Pie
                        data={slices}
                        dataKey="value"
                        nameKey="name"
                        innerRadius="55%"
                        outerRadius="85%"
                        stroke="#ffffff"
                        paddingAngle={1}
                        isAnimationActive={false}
                    />
                </PieChart>
            </ResponsiveContainer>
        </ChartCard>
    )
}
