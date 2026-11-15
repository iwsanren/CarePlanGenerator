import { Bar, BarChart, CartesianGrid, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts'
import type { DashboardDailyOrders } from '@/types'
import { ChartCard } from './ChartCard'
import { COLORS } from './colors'
import { formatShortDate } from './format'

export function DailyOrdersChart({ data, days }: { data: DashboardDailyOrders[]; days: number }) {
    const total = data.reduce((sum, day) => sum + day.count, 0)
    return (
        <ChartCard
            title="Daily orders"
            subtitle="Orders per day, by order creation day (UTC)"
            summary={`Bar chart of orders per day over the last ${days} days: ${total} orders in total.`}
            table={{ headers: ['Date', 'Orders'], rows: data.map((day) => [day.date, day.count]) }}
            className="lg:col-span-2"
        >
            <ResponsiveContainer width="100%" height={260}>
                <BarChart data={data} margin={{ top: 8, right: 8, bottom: 0, left: -16 }}>
                    <CartesianGrid stroke={COLORS.grid} vertical={false} />
                    <XAxis dataKey="date" tickFormatter={formatShortDate} fontSize={12} />
                    <YAxis allowDecimals={false} fontSize={12} />
                    <Tooltip />
                    <Bar dataKey="count" name="Orders" fill={COLORS.primary} isAnimationActive={false} />
                </BarChart>
            </ResponsiveContainer>
        </ChartCard>
    )
}
