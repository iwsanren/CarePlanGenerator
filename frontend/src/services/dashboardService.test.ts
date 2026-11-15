import { beforeEach, describe, expect, it, vi, type Mock } from 'vitest'
import api from '@/services/api'
import { dashboardService } from './dashboardService'
import { makeDashboardStats } from '@/test/fixtures'

// The axios instance is the boundary: the service only calls the endpoint and normalizes the result.
vi.mock('@/services/api', () => ({ default: { get: vi.fn() } }))
const http = api as unknown as { get: Mock }

beforeEach(() => {
  vi.resetAllMocks()
})

describe('dashboardService.getStats', () => {
  it.each([7, 30] as const)('GETs /dashboard/stats with days=%i', async (days) => {
    http.get.mockResolvedValue({ data: makeDashboardStats({ days }) })

    await dashboardService.getStats(days)

    expect(http.get).toHaveBeenCalledWith('/dashboard/stats', { params: { days } })
  })

  it('lower-cases the status names the backend sends in upper case', async () => {
    const stats = makeDashboardStats()
    http.get.mockResolvedValue({
      data: {
        ...stats,
        statusBreakdown: stats.statusBreakdown.map((s) => ({ ...s, status: s.status.toUpperCase() })),
      },
    })

    const result = await dashboardService.getStats(7)

    expect(result.statusBreakdown.map((s) => s.status)).toEqual(['pending', 'processing', 'completed', 'failed'])
  })

  it('returns everything else untouched, including nulls (no data is not 0)', async () => {
    const stats = makeDashboardStats({
      kpi: { ...makeDashboardStats().kpi, failureRate: null, avgGenerationSeconds: null },
    })
    http.get.mockResolvedValue({ data: stats })

    const result = await dashboardService.getStats(7)

    expect(result.kpi.failureRate).toBeNull()
    expect(result.kpi.avgGenerationSeconds).toBeNull()
    expect(result.dailyTrend).toEqual(stats.dailyTrend)
    expect(result.providerStats).toEqual(stats.providerStats)
  })

  it('lets a failure (e.g. the 403 built by the interceptor) reach the caller', async () => {
    http.get.mockRejectedValue(Object.assign(new Error('Permission denied'), { status: 403 }))

    await expect(dashboardService.getStats(7)).rejects.toThrow('Permission denied')
  })
})
