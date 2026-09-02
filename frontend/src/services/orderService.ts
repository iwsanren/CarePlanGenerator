import api from '@/services/api'
import type { OrderListItem, OrderListParams, PagedResponse } from '@/types'

/**
 * orderService — the layer responsible for sending HTTP requests and returning typed data.
 * No React or react-query code belongs here.
 * Similar to a Spring FeignClient: it only calls the API and deserializes the response.
 * It does not care who calls it or how the result is cached.
 */
export const orderService = {
    /** GET /orders — fetch a paginated list of orders */
    async getOrders(
        params: OrderListParams = {},
    ): Promise<PagedResponse<OrderListItem>> {
        const response = await api.get<PagedResponse<OrderListItem>>('/orders', {
            params,
        })
        return response.data
    },
}