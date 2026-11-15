import { keepPreviousData, useQuery } from '@tanstack/react-query'
import { dashboardService } from '@/services/dashboardService'
import type { DashboardDays } from '@/types'

/**
 * useDashboard — the bridge between the Dashboard page and dashboardService.
 * Usage: const { data, isLoading, isError, error, isPlaceholderData } = useDashboard(7)
 */
export function useDashboard(days: DashboardDays) {
    return useQuery({
        // One cache entry per range: going back to a range already seen shows it at once, then refreshes it.
        queryKey: ['dashboard', days],
        queryFn: () => dashboardService.getStats(days),
        // While the other range loads, keep showing the old figures (the page dims them)
        // instead of dropping back to the skeleton.
        placeholderData: keepPreviousData,
    })
}
