import { useQuery, keepPreviousData } from '@tanstack/react-query'
import { orderService } from '@/services/orderService'
import type { OrderListParams } from '@/types'

/** The Error shape produced by the axios interceptor in services/api.ts */
type ApiError = Error & { code?: string; status?: number }

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