import { beforeEach, describe, expect, it, vi } from 'vitest'
import { renderHook, waitFor } from '@testing-library/react'
import { dashboardService } from '@/services/dashboardService'
import { createTestQueryClient, queryWrapper } from '@/test/utils'
import { makeApiError, makeDashboardStats } from '@/test/fixtures'
import { useDashboard } from './useDashboard'

vi.mock('@/services/dashboardService', () => ({ dashboardService: { getStats: vi.fn() } }))
const service = vi.mocked(dashboardService)

beforeEach(() => {
  vi.resetAllMocks()
})

describe('useDashboard', () => {
  it('fetches the stats for the given range', async () => {
    service.getStats.mockResolvedValue(makeDashboardStats())
    const { result } = renderHook(() => useDashboard(7), { wrapper: queryWrapper(createTestQueryClient()) })

    await waitFor(() => expect(result.current.isSuccess).toBe(true))
    expect(service.getStats).toHaveBeenCalledWith(7)
    expect(result.current.data?.kpi.totalOrders).toBe(8)
  })

  it('keeps the old range on screen (placeholder data) while the other range loads', async () => {
    const sevenDays = makeDashboardStats({ days: 7 })
    let resolveThirty: (value: ReturnType<typeof makeDashboardStats>) => void = () => {}
    service.getStats
      .mockResolvedValueOnce(sevenDays)
      .mockReturnValueOnce(new Promise((resolve) => { resolveThirty = resolve }))

    const { result, rerender } = renderHook(({ days }) => useDashboard(days), {
      wrapper: queryWrapper(createTestQueryClient()),
      initialProps: { days: 7 as 7 | 30 },
    })
    await waitFor(() => expect(result.current.data).toEqual(sevenDays))

    rerender({ days: 30 })
    await waitFor(() => expect(result.current.isPlaceholderData).toBe(true))
    expect(result.current.data).toEqual(sevenDays)

    resolveThirty(makeDashboardStats({ days: 30 }))
    await waitFor(() => expect(result.current.isPlaceholderData).toBe(false))
    expect(result.current.data?.days).toBe(30)
  })

  it('keeps one cache entry per range: going back to 7 days shows it at once and refreshes it', async () => {
    service.getStats.mockImplementation(async (days) => makeDashboardStats({ days }))
    const { result, rerender } = renderHook(({ days }) => useDashboard(days), {
      wrapper: queryWrapper(createTestQueryClient()),
      initialProps: { days: 7 as 7 | 30 },
    })
    await waitFor(() => expect(result.current.data?.days).toBe(7))
    rerender({ days: 30 })
    await waitFor(() => expect(result.current.data?.days).toBe(30))

    rerender({ days: 7 })

    // no skeleton, no wait: the cached 7-day answer is there in the same render
    expect(result.current.data?.days).toBe(7)
    expect(result.current.isPlaceholderData).toBe(false)
    // the figures are live, so the stale entry is refreshed in the background (7, 30, 7 again)
    await waitFor(() => expect(service.getStats).toHaveBeenCalledTimes(3))
  })

  it('exposes a failure as isError', async () => {
    service.getStats.mockRejectedValue(makeApiError('boom', { status: 500 }))
    const { result } = renderHook(() => useDashboard(7), { wrapper: queryWrapper(createTestQueryClient()) })

    await waitFor(() => expect(result.current.isError).toBe(true))
    expect(result.current.error?.message).toBe('boom')
  })
})
