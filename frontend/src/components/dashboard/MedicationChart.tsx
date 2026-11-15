import { Bar, BarChart, LabelList, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts'
import type { DashboardMedicationCount } from '@/types'
import { ChartCard } from './ChartCard'
import { COLORS } from './colors'

const ROW_HEIGHT = 34

export function MedicationChart({ data }: { data: DashboardMedicationCount[] }) {
    return (
        <ChartCard
            title="Top medications"
            subtitle="Orders per medication (names are matched ignoring case)"
            summary={`Horizontal bar chart of the ${data.length} most ordered medications.`}
            table={{ headers: ['Medication', 'Orders'], rows: data.map((m) => [m.medicationName, m.orderCount]) }}
        >
            <ResponsiveContainer width="100%" height={Math.max(160, data.length * ROW_HEIGHT)}>
                <BarChart data={data} layout="vertical" margin={{ top: 4, right: 32, bottom: 0, left: 0 }}>
                    <XAxis type="number" allowDecimals={false} hide />
                    <YAxis type="category" dataKey="medicationName" width={110} fontSize={12} />
                    <Tooltip />
                    <Bar dataKey="orderCount" name="Orders" fill={COLORS.primary} isAnimationActive={false}>
                        <LabelList dataKey="orderCount" position="right" fontSize={12} />
                    </Bar>
                </BarChart>
            </ResponsiveContainer>
        </ChartCard>
    )
}
