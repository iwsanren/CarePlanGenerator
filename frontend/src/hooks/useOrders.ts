import { useQuery } from '@tanstack/react-query'
import { orderService } from '@/services/orderService.ts'
import type { OrderListParams } from '@/types'

/**
 * useOrders — the only bridge between components and orderService,
 * and the only place in this flow that knows react-query exists.
 * Components only need: const { data, isLoading, error } = useOrders(params)
 */
export function useOrders(params: OrderListParams = {}) {
    return useQuery({
        // queryKey: the cache key for this data. react-query deeply compares this array by value,
        // so passing a new { ... } object on each render is fine as long as its contents are the same.
        // If params change (pagination / filters), the key changes and a new request is triggered.
        // If the key stays the same, cached data is returned instead of hitting the backend again.
        queryKey: ['orders', params],

        // queryFn: the function that actually fetches the data. react-query decides when to call it,
        // and handles loading / error / retry / stale cache behavior for us.
        queryFn: () => orderService.getOrders(params),
    })
}