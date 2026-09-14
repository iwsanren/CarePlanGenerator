import { useMutation, useQuery, useQueryClient, keepPreviousData } from '@tanstack/react-query'
import { orderService } from '@/services/orderService'
import type { ApiError, ConfirmationRequiredDetail, CreateOrderRequest, OrderListParams } from '@/types'

/**
 * True when the backend reported that the requested order id does not exist.
 * We key off the stable error `code`, not the HTTP status, so this keeps working
 * even if the status mapping changes again.
 */
export function isNotFoundError(error: unknown): boolean {
    return (error as ApiError)?.code === 'ORDER_NOT_FOUND'
}

/**
 * useOrders — the single bridge between components and orderService,
 * and the only place in the frontend that needs to know react-query exists.
 * Usage: const { data, isLoading, isError, error } = useOrders(params)
 */
export function useOrders(params: OrderListParams = {}) {
    return useQuery({
        // Cache key: when params change (pagination / filters), the key changes and data is refetched.
        // If the key stays the same, react-query serves the cached result.
        queryKey: ['orders', params],

        // The function that actually fetches the data
        queryFn: () => orderService.getOrders(params),

        // Improves the pagination experience: keep showing the previous page while the next loads
        placeholderData: keepPreviousData,
    })
}

/**
 * useOrder — same idea, but for a single order (GET /orders/{id}).
 * Usage: const { data, isLoading, isError, error } = useOrder(id)
 */
export function useOrder(id: number) {
    return useQuery({
        queryKey: ['order', id],
        queryFn: () => orderService.getOrder(id),

        // Don't fire until the route param has parsed into a real number.
        enabled: Number.isFinite(id),

        // A missing order will never succeed on retry, so fail fast for it.
        // Genuine transient errors (a network blip) still get one retry.
        retry: (failureCount, error) => !isNotFoundError(error) && failureCount < 1,
    })
}

/** True when the backend hard-blocked the request (HTTP 409) — no confirm option. */
export function isBlockedError(error: unknown): boolean {
    return (error as ApiError)?.type === 'block'
}

/**
 * True when the backend returned "confirmation required" (HTTP 200,
 * code CONFIRMATION_REQUIRED). Narrows the error's type so callers can read
 * `error.detail.warnings` without an extra cast.
 */
export function isConfirmationRequiredError(
    error: unknown,
): error is ApiError & { detail: ConfirmationRequiredDetail } {
    return (error as ApiError)?.type === 'warning' && (error as ApiError)?.code === 'CONFIRMATION_REQUIRED'
}

/** useMutation (react-query) — the write-side counterpart to useQuery: it does
 *  not fetch automatically, you call `mutate`/`mutateAsync` to trigger it. */
export function useCreateOrder() {
    const queryClient = useQueryClient()
    return useMutation({
        mutationFn: (payload: CreateOrderRequest) => orderService.createOrder(payload),
        onSuccess: () => {
            // Invalidate the cached orders list so OrdersPage refetches and
            // shows the new order next time it is visited.
            queryClient.invalidateQueries({ queryKey: ['orders'] })
        },
    })
}