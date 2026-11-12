import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { act, renderHook, waitFor } from '@testing-library/react'
import { orderService } from '@/services/orderService'
import { createTestQueryClient, queryWrapper } from '@/test/utils'
import { makeApiError, makeListItem, makeOrder, makePage } from '@/test/fixtures'
import {
  isBlockedError,
  isConfirmationRequiredError,
  isNotFoundError,
  useCarePlanStatus,
  useCreateOrder,
  useOrder,
  useOrders,
  useRegenerateCarePlan,
  useUploadCarePlan,
} from './useOrders'

// The hooks are the bridge to orderService, so that is the boundary we replace:
// no HTTP, no axios, no Keycloak -- but the real react-query machinery runs.
vi.mock('@/services/orderService', () => ({
  orderService: {
    getOrders: vi.fn(),
    getOrder: vi.fn(),
    createOrder: vi.fn(),
    getCarePlanStatus: vi.fn(),
    regenerateCarePlan: vi.fn(),
    uploadCarePlan: vi.fn(),
  },
}))
const service = vi.mocked(orderService)

beforeEach(() => {
  vi.resetAllMocks()
})

describe('error type guards', () => {
  it('isNotFoundError keys off the error code, not the HTTP status', () => {
    expect(isNotFoundError(makeApiError('x', { code: 'ORDER_NOT_FOUND' }))).toBe(true)
    expect(isNotFoundError(makeApiError('x', { code: 'OTHER', status: 404 }))).toBe(false)
  })

  it('isBlockedError is true only for type "block"', () => {
    expect(isBlockedError(makeApiError('x', { type: 'block' }))).toBe(true)
    expect(isBlockedError(makeApiError('x', { type: 'warning' }))).toBe(false)
  })

  it('isConfirmationRequiredError needs both type "warning" and the CONFIRMATION_REQUIRED code', () => {
    expect(isConfirmationRequiredError(makeApiError('x', { type: 'warning', code: 'CONFIRMATION_REQUIRED' }))).toBe(true)
    expect(isConfirmationRequiredError(makeApiError('x', { type: 'warning', code: 'OTHER' }))).toBe(false)
    expect(isConfirmationRequiredError(makeApiError('x', { type: 'block', code: 'CONFIRMATION_REQUIRED' }))).toBe(false)
  })

  it.each([null, undefined, {}, 'text', 42])('all three guards tolerate %j', (value) => {
    expect(isNotFoundError(value)).toBe(false)
    expect(isBlockedError(value)).toBe(false)
    expect(isConfirmationRequiredError(value)).toBe(false)
  })
})

describe('useOrders', () => {
  it('fetches with the given params', async () => {
    service.getOrders.mockResolvedValue(makePage([makeListItem()]))
    const client = createTestQueryClient()
    const { result } = renderHook(() => useOrders({ page: 2, page_size: 20 }), { wrapper: queryWrapper(client) })

    await waitFor(() => expect(result.current.isSuccess).toBe(true))
    expect(service.getOrders).toHaveBeenCalledWith({ page: 2, page_size: 20 })
    expect(result.current.data?.results).toHaveLength(1)
  })

  it('defaults to empty params', async () => {
    service.getOrders.mockResolvedValue(makePage([]))
    const { result } = renderHook(() => useOrders(), { wrapper: queryWrapper(createTestQueryClient()) })

    await waitFor(() => expect(result.current.isSuccess).toBe(true))
    expect(service.getOrders).toHaveBeenCalledWith({})
  })

  it('keeps showing the previous page (placeholder data) while the next page loads', async () => {
    const page1 = makePage([makeListItem({ id: 1 })])
    let resolvePage2: (value: ReturnType<typeof makePage>) => void = () => {}
    service.getOrders
      .mockResolvedValueOnce(page1)
      .mockReturnValueOnce(new Promise((resolve) => { resolvePage2 = resolve }))

    const { result, rerender } = renderHook(({ page }) => useOrders({ page }), {
      wrapper: queryWrapper(createTestQueryClient()),
      initialProps: { page: 1 },
    })
    await waitFor(() => expect(result.current.data).toEqual(page1))

    rerender({ page: 2 })
    await waitFor(() => expect(result.current.isPlaceholderData).toBe(true))
    expect(result.current.data).toEqual(page1) // old rows stay on screen

    resolvePage2(makePage([makeListItem({ id: 2 })]))
    await waitFor(() => expect(result.current.isPlaceholderData).toBe(false))
    expect(result.current.data?.results[0].id).toBe(2)
  })

  it('exposes a failure as isError', async () => {
    service.getOrders.mockRejectedValue(makeApiError('boom', { status: 500 }))
    const { result } = renderHook(() => useOrders(), { wrapper: queryWrapper(createTestQueryClient()) })

    await waitFor(() => expect(result.current.isError).toBe(true))
    expect(result.current.error?.message).toBe('boom')
  })
})

describe('useOrder', () => {
  it('does not fetch while the id is not a real number', () => {
    const { result } = renderHook(() => useOrder(NaN), { wrapper: queryWrapper(createTestQueryClient()) })

    expect(result.current.fetchStatus).toBe('idle')
    expect(service.getOrder).not.toHaveBeenCalled()
  })

  it('fetches the order by id', async () => {
    service.getOrder.mockResolvedValue(makeOrder({ id: 5 }))
    const { result } = renderHook(() => useOrder(5), { wrapper: queryWrapper(createTestQueryClient()) })

    await waitFor(() => expect(result.current.isSuccess).toBe(true))
    expect(service.getOrder).toHaveBeenCalledWith(5)
  })

  it.each([
    ['ORDER_NOT_FOUND', makeApiError('nope', { code: 'ORDER_NOT_FOUND', status: 404 })],
    ['401', makeApiError('unauthorized', { status: 401 })],
    ['403', makeApiError('forbidden', { status: 403 })],
  ])('does not retry a %s failure', async (_name, error) => {
    service.getOrder.mockRejectedValue(error)
    const { result } = renderHook(() => useOrder(5), { wrapper: queryWrapper(createTestQueryClient()) })

    await waitFor(() => expect(result.current.isError).toBe(true))
    expect(service.getOrder).toHaveBeenCalledTimes(1)
  })

  it('retries a transient failure exactly once', async () => {
    service.getOrder.mockRejectedValue(makeApiError('flaky', { status: 500 }))
    const { result } = renderHook(() => useOrder(5), { wrapper: queryWrapper(createTestQueryClient()) })

    await waitFor(() => expect(result.current.isError).toBe(true))
    expect(service.getOrder).toHaveBeenCalledTimes(2) // first try + one retry
  })

  it('recovers when the retry succeeds', async () => {
    service.getOrder
      .mockRejectedValueOnce(makeApiError('flaky', { status: 500 }))
      .mockResolvedValueOnce(makeOrder({ id: 5 }))
    const { result } = renderHook(() => useOrder(5), { wrapper: queryWrapper(createTestQueryClient()) })

    await waitFor(() => expect(result.current.isSuccess).toBe(true))
  })
})

describe('useCreateOrder', () => {
  const payload = {
    patientFirstName: 'Jane',
    patientLastName: 'Doe',
    patientMrn: '123456',
    providerName: 'Dr. Smith',
    providerNpi: '1234567890',
    medicationName: 'IVIG',
    primaryDiagnosis: 'G70.00',
    additionalDiagnoses: [],
    medicationHistory: [],
  }

  it('sends the payload and invalidates the orders list on success', async () => {
    service.createOrder.mockResolvedValue(makeOrder({ id: 9 }))
    const client = createTestQueryClient()
    const invalidate = vi.spyOn(client, 'invalidateQueries')
    const { result } = renderHook(() => useCreateOrder(), { wrapper: queryWrapper(client) })

    let created
    await act(async () => { created = await result.current.mutateAsync(payload) })

    expect(service.createOrder).toHaveBeenCalledWith(payload)
    expect(created).toEqual(makeOrder({ id: 9 }))
    expect(invalidate).toHaveBeenCalledWith({ queryKey: ['orders'] })
  })

  it('leaves the cache alone when creation fails', async () => {
    service.createOrder.mockRejectedValue(makeApiError('blocked', { type: 'block' }))
    const client = createTestQueryClient()
    const invalidate = vi.spyOn(client, 'invalidateQueries')
    const { result } = renderHook(() => useCreateOrder(), { wrapper: queryWrapper(client) })

    await act(async () => { await result.current.mutateAsync(payload).catch(() => {}) })

    expect(invalidate).not.toHaveBeenCalled()
    await waitFor(() => expect(result.current.error?.message).toBe('blocked'))
  })
})

describe('useCarePlanStatus (polling)', () => {
  // shouldAdvanceTime lets testing-library's waitFor keep working under fake timers;
  // advanceTimersByTimeAsync then moves the clock by an exact, deterministic amount.
  beforeEach(() => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
  })
  afterEach(() => {
    vi.useRealTimers()
  })

  const status = (s: 'pending' | 'processing' | 'completed' | 'failed') => ({ orderId: 3, status: s })

  it('does not fetch while the id is not a real number', () => {
    const { result } = renderHook(() => useCarePlanStatus(NaN), { wrapper: queryWrapper(createTestQueryClient()) })

    expect(result.current.fetchStatus).toBe('idle')
    expect(service.getCarePlanStatus).not.toHaveBeenCalled()
  })

  it.each(['pending', 'processing'] as const)('keeps polling every 3s while the status is %s', async (s) => {
    service.getCarePlanStatus.mockResolvedValue(status(s))
    renderHook(() => useCarePlanStatus(3), { wrapper: queryWrapper(createTestQueryClient()) })

    await waitFor(() => expect(service.getCarePlanStatus).toHaveBeenCalledTimes(1))
    await act(async () => { await vi.advanceTimersByTimeAsync(3000) })
    expect(service.getCarePlanStatus).toHaveBeenCalledTimes(2)
    await act(async () => { await vi.advanceTimersByTimeAsync(3000) })
    expect(service.getCarePlanStatus).toHaveBeenCalledTimes(3)
  })

  it.each(['completed', 'failed'] as const)('stops polling once the status is %s', async (s) => {
    service.getCarePlanStatus.mockResolvedValueOnce(status('processing')).mockResolvedValue(status(s))
    const { result } = renderHook(() => useCarePlanStatus(3), { wrapper: queryWrapper(createTestQueryClient()) })

    await waitFor(() => expect(service.getCarePlanStatus).toHaveBeenCalledTimes(1))
    await act(async () => { await vi.advanceTimersByTimeAsync(3000) })
    expect(service.getCarePlanStatus).toHaveBeenCalledTimes(2)
    expect(result.current.data?.status).toBe(s)

    await act(async () => { await vi.advanceTimersByTimeAsync(15000) })
    expect(service.getCarePlanStatus).toHaveBeenCalledTimes(2) // no further requests
  })

  it.each([401, 403])('stops polling after a %i error', async (code) => {
    service.getCarePlanStatus.mockRejectedValue(makeApiError('denied', { status: code }))
    renderHook(() => useCarePlanStatus(3), { wrapper: queryWrapper(createTestQueryClient()) })

    await waitFor(() => expect(service.getCarePlanStatus).toHaveBeenCalledTimes(1))
    await act(async () => { await vi.advanceTimersByTimeAsync(15000) })
    expect(service.getCarePlanStatus).toHaveBeenCalledTimes(1)
  })
})

describe('useRegenerateCarePlan', () => {
  it('calls the service and invalidates that order and its status', async () => {
    service.regenerateCarePlan.mockResolvedValue(makeOrder({ id: 5, status: 'pending' }))
    const client = createTestQueryClient()
    const invalidate = vi.spyOn(client, 'invalidateQueries')
    const { result } = renderHook(() => useRegenerateCarePlan(), { wrapper: queryWrapper(client) })

    await act(async () => { await result.current.mutateAsync(5) })

    expect(service.regenerateCarePlan).toHaveBeenCalledWith(5)
    expect(invalidate).toHaveBeenCalledWith({ queryKey: ['order', 5] })
    expect(invalidate).toHaveBeenCalledWith({ queryKey: ['carePlanStatus', 5] })
  })
})

describe('useUploadCarePlan', () => {
  it('passes id and upload to the service and invalidates that order and its status', async () => {
    service.uploadCarePlan.mockResolvedValue(makeOrder({ id: 5 }))
    const client = createTestQueryClient()
    const invalidate = vi.spyOn(client, 'invalidateQueries')
    const { result } = renderHook(() => useUploadCarePlan(), { wrapper: queryWrapper(client) })

    await act(async () => { await result.current.mutateAsync({ id: 5, upload: { text: 'plan' } }) })

    expect(service.uploadCarePlan).toHaveBeenCalledWith(5, { text: 'plan' })
    expect(invalidate).toHaveBeenCalledWith({ queryKey: ['order', 5] })
    expect(invalidate).toHaveBeenCalledWith({ queryKey: ['carePlanStatus', 5] })
  })

  it('does not invalidate when the upload fails', async () => {
    service.uploadCarePlan.mockRejectedValue(makeApiError('too big', { status: 413 }))
    const client = createTestQueryClient()
    const invalidate = vi.spyOn(client, 'invalidateQueries')
    const { result } = renderHook(() => useUploadCarePlan(), { wrapper: queryWrapper(client) })

    await act(async () => {
      await result.current.mutateAsync({ id: 5, upload: { text: 'plan' } }).catch(() => {})
    })

    expect(invalidate).not.toHaveBeenCalled()
  })
})
