import api from '@/services/api'
import type {
    OrderListItem,
    OrderListParams,
    OrderResponse,
    OrderStatus,
    PagedResponse,
} from '@/types'

/**
 * orderService — the "make an HTTP call, return a typed object" layer.
 * No React / react-query here. Like a Spring FeignClient: it only calls the
 * endpoint and deserializes; it does not care who calls it or how the result
 * is cached.
 */
export const orderService = {
    /** GET /orders — paginated list of orders */
    async getOrders(
        params: OrderListParams = {},
    ): Promise<PagedResponse<OrderListItem>> {
        const response = await api.get<PagedResponse<OrderListItem>>('/orders', {
            params,
        })
        return response.data
    },

    /** GET /orders/{id} — fetch one order with its care plan state */
    async getOrder(id: number): Promise<OrderResponse> {
        const response = await api.get<OrderResponse>(`/orders/${id}`)
        // Defensive normalization: this endpoint has historically returned an UPPERCASE
        // enum name for `status` (e.g. "COMPLETED"). Every consumer above this layer
        // should only ever deal with the lowercase OrderStatus union, so we fold it here.
        return {
            ...response.data,
            status: String(response.data.status).toLowerCase() as OrderStatus,
        }
    },
}