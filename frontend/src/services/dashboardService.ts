import api from '@/services/api'
import type { DashboardDays, DashboardStats, OrderStatus } from '@/types'

/**
 * dashboardService — "make an HTTP call, return a typed object", like orderService.
 * Tokens and 401 handling come from the shared axios instance in api.ts.
 */
export const dashboardService = {
    /** GET /dashboard/stats?days=7|30 — PHARMACIST only (the backend answers 403 to anybody else). */
    async getStats(days: DashboardDays): Promise<DashboardStats> {
        const response = await api.get<DashboardStats>('/dashboard/stats', { params: { days } })
        return {
            ...response.data,
            // The endpoint sends the enum names ("COMPLETED"); the rest of the frontend only knows
            // the lowercase OrderStatus union, so fold it here (same rule as orderService).
            statusBreakdown: response.data.statusBreakdown.map((entry) => ({
                ...entry,
                status: String(entry.status).toLowerCase() as OrderStatus,
            })),
        }
    },
}
