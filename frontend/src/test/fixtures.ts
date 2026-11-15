import type { ApiError, DashboardStats, OrderListItem, OrderResponse, PagedResponse } from '@/types'

export function makeOrder(overrides: Partial<OrderResponse> = {}): OrderResponse {
  return {
    id: 7,
    patientId: 1,
    providerId: 2,
    patientFirstName: 'Jane',
    patientLastName: 'Doe',
    patientMrn: '123456',
    patientDateOfBirth: '1980-01-31',
    providerName: 'Dr. Smith',
    providerNpi: '1234567890',
    medicationName: 'IVIG',
    status: 'completed',
    carePlanContent: 'Problem list\nGoals',
    resultType: 'SUCCESS',
    message: null,
    warnings: [],
    requiresConfirm: false,
    ...overrides,
  }
}

export function makeListItem(overrides: Partial<OrderListItem> = {}): OrderListItem {
  return {
    id: 1,
    patientName: 'Jane Doe',
    patientMrn: '123456',
    medicationName: 'IVIG',
    status: 'completed',
    createdAt: '2026-08-30T12:34:56Z',
    providerName: 'Dr. Smith',
    providerNpi: '1234567890',
    ...overrides,
  }
}

export function makePage(
  results: OrderListItem[],
  overrides: Partial<PagedResponse<OrderListItem>> = {},
): PagedResponse<OrderListItem> {
  return { count: results.length, page: 1, pageSize: 20, results, ...overrides }
}

/** An Error carrying the extra fields services/api.ts attaches to every failure. */
export function makeApiError(message: string, fields: Partial<Omit<ApiError, 'message'>> = {}): ApiError {
  return Object.assign(new Error(message), fields) as ApiError
}

/** A dashboard response for 7 days (2026-11-08 .. 2026-11-14), shaped like GET /dashboard/stats after dashboardService. */
export function makeDashboardStats(overrides: Partial<DashboardStats> = {}): DashboardStats {
  const dates = ['2026-11-08', '2026-11-09', '2026-11-10', '2026-11-11', '2026-11-12', '2026-11-13', '2026-11-14']
  const orders = [2, 0, 3, 0, 2, 0, 1]
  return {
    days: 7,
    from: '2026-11-08',
    to: '2026-11-14',
    timezone: 'UTC',
    kpi: {
      totalOrders: 8,
      completedCount: 4,
      failedCount: 1,
      failureRate: 0.2,
      avgGenerationSeconds: 20,
      manualUploadCount: 1,
    },
    dailyOrders: dates.map((date, i) => ({ date, count: orders[i] })),
    dailyTrend: dates.map((date, i) => ({
      date,
      completedCount: i === 0 ? 2 : 0,
      failedCount: i === 2 ? 1 : 0,
      failureRate: i === 0 ? 0 : i === 2 ? 0.5 : null,
      avgGenerationSeconds: i === 0 ? 15 : i === 2 ? 30 : null,
    })),
    statusBreakdown: [
      { status: 'pending', count: 1, share: 0.125 },
      { status: 'processing', count: 1, share: 0.125 },
      { status: 'completed', count: 5, share: 0.625 },
      { status: 'failed', count: 1, share: 0.125 },
    ],
    topMedications: [
      { medicationName: 'Gamunex-C', orderCount: 3 },
      { medicationName: 'Metformin', orderCount: 3 },
      { medicationName: 'Lisinopril', orderCount: 2 },
    ],
    providerStats: [
      { providerId: 2, providerName: 'Dr. Beta', orderCount: 4, failedCount: 0, avgGenerationSeconds: 30 },
      { providerId: 1, providerName: 'Dr. Alpha', orderCount: 3, failedCount: 1, avgGenerationSeconds: 15 },
      { providerId: 3, providerName: 'Dr. Gamma', orderCount: 1, failedCount: 0, avgGenerationSeconds: null },
    ],
    ...overrides,
  }
}
