import { useMutation, useQuery, useQueryClient, keepPreviousData } from '@tanstack/react-query'
import { orderService } from '@/services/orderService'
import type {
    ApiError,
    ConfirmationRequiredDetail,
    CreateOrderRequest,
    OrderListParams,
    OrderStatus,
} from '@/types'

// Statuses that mean "still working" — polling continues while the backend reports one of these.
const ACTIVE_STATUSES: OrderStatus[] = ['pending', 'processing']

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

/**
 * useCarePlanStatus — polls GET /orders/{id}/status every 3s while the order
 * is pending/processing, and stops automatically once it reaches a terminal
 * state (completed/failed). react-query's refetchInterval is a function here
 * so it can inspect the *latest fetched* status on every tick and decide
 * whether to keep going, instead of a fixed interval that never turns off.
 */
export function useCarePlanStatus(id: number) {
    return useQuery({
        queryKey: ['carePlanStatus', id],
        queryFn: () => orderService.getCarePlanStatus(id),
        enabled: Number.isFinite(id),
        refetchInterval: (query) => {
            const status = query.state.data?.status
            return status && ACTIVE_STATUSES.includes(status) ? 3000 : false
        },
    })
}

/** useMutation for POST /orders/{id}/regenerate — invalidates the order + status caches on success. */
export function useRegenerateCarePlan() {
    const queryClient = useQueryClient()
    return useMutation({
        mutationFn: (id: number) => orderService.regenerateCarePlan(id),
        onSuccess: (_data, id) => {
            queryClient.invalidateQueries({ queryKey: ['order', id] })
            queryClient.invalidateQueries({ queryKey: ['carePlanStatus', id] })
        },
    })
}

/** useMutation for POST /orders/{id}/careplan/upload — invalidates the order + status caches on success. */
export function useUploadCarePlan() {
    const queryClient = useQueryClient()
    return useMutation({
        mutationFn: ({ id, upload }: { id: number; upload: { text?: string; file?: File } }) =>
            orderService.uploadCarePlan(id, upload),
        onSuccess: (_data, { id }) => {
            queryClient.invalidateQueries({ queryKey: ['order', id] })
            queryClient.invalidateQueries({ queryKey: ['carePlanStatus', id] })
        },
    })
}
