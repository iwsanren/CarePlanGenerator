import { useQuery, keepPreviousData } from '@tanstack/react-query'
import { orderService } from '@/services/orderService'
import type { OrderListParams } from '@/types'

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

        // Improves the pagination experience; see explanation below
        placeholderData: keepPreviousData,
    })
}