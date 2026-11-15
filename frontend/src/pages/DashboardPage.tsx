import { useState } from 'react'
import { Link } from 'react-router-dom'
import { BarChart3 } from 'lucide-react'

import { useDashboard } from '@/hooks/useDashboard'
import { NoAccess } from '@/components/NoAccess'
import { Button } from '@/components/ui/Button'
import { DashboardContent } from '@/components/dashboard/DashboardContent'
import { RangeToggle } from '@/components/dashboard/RangeToggle'
import { cn } from '@/utils/utils'
import type { ApiError, DashboardDays } from '@/types'

/** Loading skeleton: grey blocks laid out like the real page. */
function DashboardSkeleton() {
    return (
        <div role="status" aria-label="Loading dashboard" className="animate-pulse space-y-4">
            <div className="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-4">
                {Array.from({ length: 4 }).map((_, i) => <div key={i} className="h-24 rounded-lg bg-gray-200" />)}
            </div>
            <div className="h-72 rounded-lg bg-gray-200" />
            <div className="h-72 rounded-lg bg-gray-200" />
        </div>
    )
}

export function DashboardPage() {
    // The range is plain component state; useDashboard fetches (or serves from cache) for it.
    const [days, setDays] = useState<DashboardDays>(7)
    const query = useDashboard(days)

    // Read from top to bottom as if / else-if / else, like OrdersPage.
    let body
    if (query.status === 'pending') {
        // 1. First load, nothing to show yet
        body = <DashboardSkeleton />
    } else if (query.status === 'error') {
        // 2. Request failed. A 403 means the backend refused this user (e.g. the role was removed
        //    after sign-in), which is a permission problem, not a broken page.
        body = (query.error as ApiError).status === 403 ? (
            <NoAccess />
        ) : (
            <div role="alert" className="rounded-lg border border-red-200 bg-red-50 p-4">
                <p className="mb-3 text-red-700">Error loading dashboard: {query.error.message}</p>
                <Button variant="outline" size="sm" onClick={() => { void query.refetch() }}>Try again</Button>
            </div>
        )
    } else if (query.data.kpi.totalOrders === 0) {
        // 3. Succeeded, but there is nothing to chart
        body = (
            <div className="rounded-lg bg-white p-8 text-center shadow">
                <BarChart3 className="mx-auto mb-4 h-12 w-12 text-gray-400" />
                <p className="mb-4 text-gray-500">No orders in the last {days} days</p>
                <Link to="/" className="text-teal-700 hover:underline">Create an order</Link>
            </div>
        )
    } else {
        // 4. Normal state. While the other range is loading, the old figures stay (dimmed).
        body = (
            <div aria-busy={query.isPlaceholderData} className={cn('transition-opacity', query.isPlaceholderData && 'opacity-50')}>
                <DashboardContent stats={query.data} />
            </div>
        )
    }

    return (
        <div>
            {/* Header: title and range toggle are shown in every state, so the range can always be changed */}
            <div className="mb-6 flex flex-wrap items-center justify-between gap-3">
                <h1 className="text-2xl font-bold">Dashboard</h1>
                <RangeToggle value={days} onChange={setDays} />
            </div>
            {body}
        </div>
    )
}
