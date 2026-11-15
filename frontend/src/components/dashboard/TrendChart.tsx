import { CartesianGrid, Line, LineChart, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts'
import type { DashboardDailyTrend } from '@/types'
import { ChartCard } from './ChartCard'
import { COLORS } from './colors'
import {
    formatPercent,
    formatPercentTick,
    formatSeconds,
    formatShortDate,
    formatTrendTooltip,
    TREND_RATE_NAME,
    TREND_SECONDS_NAME,
} from './format'

export function TrendChart({ data }: { data: DashboardDailyTrend[] }) {
    const hasRate = data.some((day) => day.failureRate !== null)
    const hasSeconds = data.some((day) => day.avgGenerationSeconds !== null)

    return (
        <ChartCard
            title="Generation time and failure rate"
            subtitle="By order creation day (UTC). LLM-generated care plans only; manual uploads are not included."
            summary="Line chart per day: average generation time in seconds (solid line) and failure rate (dashed line)."
            noData={hasRate || hasSeconds ? undefined : 'No data: no care plan has finished in this period.'}
            table={{
                headers: ['Date', 'Avg generation time', 'Failure rate', 'Completed', 'Failed'],
                rows: data.map((day) => [
                    day.date,
                    formatSeconds(day.avgGenerationSeconds),
                    formatPercent(day.failureRate),
                    day.completedCount,
                    day.failedCount,
                ]),
            }}
            legend={
                <>
                    <ul aria-label="Trend legend" className="mt-2 flex flex-wrap gap-x-6 gap-y-1 text-sm">
                        <li className="flex items-center gap-2">
                            <svg width="28" height="8" aria-hidden="true">
                                <line x1="0" y1="4" x2="28" y2="4" stroke={COLORS.primary} strokeWidth="3" />
                            </svg>
                            Avg generation time (solid line, left axis)
                        </li>
                        <li className="flex items-center gap-2">
                            <svg width="28" height="8" aria-hidden="true">
                                <line
                                    x1="0" y1="4" x2="28" y2="4"
                                    stroke={COLORS.amber} strokeWidth="3" strokeDasharray="6 4"
                                />
                            </svg>
                            Failure rate (dashed line, right axis)
                        </li>
                    </ul>
                    {!hasSeconds && (
                        <p className="mt-2 text-xs text-gray-500">
                            No data for generation time: none of these plans has timing data.
                        </p>
                    )}
                </>
            }
        >
            <ResponsiveContainer width="100%" height={280}>
                <LineChart data={data} margin={{ top: 8, right: 8, bottom: 0, left: 0 }}>
                    <CartesianGrid stroke={COLORS.grid} vertical={false} />
                    <XAxis dataKey="date" tickFormatter={formatShortDate} fontSize={12} />
                    <YAxis yAxisId="seconds" fontSize={12} unit=" s" />
                    <YAxis
                        yAxisId="rate" orientation="right" domain={[0, 1]}
                        tickFormatter={formatPercentTick} fontSize={12}
                    />
                    <Tooltip formatter={formatTrendTooltip} />
                    <Line
                        yAxisId="seconds" type="linear" dataKey="avgGenerationSeconds" name={TREND_SECONDS_NAME}
                        stroke={COLORS.primary} strokeWidth={2.5} connectNulls={false} isAnimationActive={false}
                    />
                    <Line
                        yAxisId="rate" type="linear" dataKey="failureRate" name={TREND_RATE_NAME}
                        stroke={COLORS.amber} strokeWidth={2.5} strokeDasharray="6 4"
                        connectNulls={false} isAnimationActive={false}
                    />
                </LineChart>
            </ResponsiveContainer>
        </ChartCard>
    )
}
