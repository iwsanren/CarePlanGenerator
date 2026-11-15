import type { DashboardStats } from '@/types'
import { DailyOrdersChart } from './DailyOrdersChart'
import { formatShortDate } from './format'
import { KpiCards } from './KpiCards'
import { MedicationChart } from './MedicationChart'
import { ProviderTable } from './ProviderTable'
import { StatusDonut } from './StatusDonut'
import { TrendChart } from './TrendChart'

/** The dashboard body for one response: KPI row, then three rows of charts. Purely presentational. */
export function DashboardContent({ stats }: { stats: DashboardStats }) {
    return (
        <div className="space-y-4">
            <p className="text-sm text-gray-500">
                {formatShortDate(stats.from)} – {formatShortDate(stats.to)} · whole {stats.timezone} calendar days
            </p>
            <KpiCards stats={stats} />
            <div className="grid grid-cols-1 gap-4 lg:grid-cols-3">
                <DailyOrdersChart data={stats.dailyOrders} days={stats.days} />
                <StatusDonut data={stats.statusBreakdown} />
            </div>
            <TrendChart data={stats.dailyTrend} />
            <div className="grid grid-cols-1 gap-4 lg:grid-cols-3">
                <MedicationChart data={stats.topMedications} />
                <ProviderTable data={stats.providerStats} />
            </div>
        </div>
    )
}
