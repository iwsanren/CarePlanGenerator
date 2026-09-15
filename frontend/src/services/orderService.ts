import api from '@/services/api'
import type {
    ApiError,
    ApiErrorBody,
    CarePlanStatus,
    CreateOrderRequest,
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

    /** POST /orders — create an order; may resolve, or throw a block/confirmation ApiError. */
    async createOrder(payload: CreateOrderRequest): Promise<OrderResponse> {
        const response = await api.post<OrderResponse | ApiErrorBody>('/orders', payload)

        // The backend answers "needs human confirmation" with HTTP 200, not a 4xx --
        // it isn't a transport failure, just a decision the order didn't get made yet.
        // axios only rejects on non-2xx, so the interceptor in api.ts never runs for
        // this response; detect the error-shaped body here and throw it the same way
        // the interceptor would, so isConfirmationRequiredError/isBlockedError work
        // no matter which path (thrown vs. resolved) the backend used.
        const body = response.data as ApiErrorBody
        if (body.type === 'warning' || body.type === 'block') {
            const error = new Error(body.message ?? 'Order could not be created') as ApiError
            error.type = body.type
            error.code = body.code
            error.detail = body.detail
            error.status = response.status
            throw error
        }

        const order = response.data as OrderResponse
        return {
            ...order,
            status: String(order.status).toLowerCase() as OrderStatus,
        }
    },

    /** GET /orders/{id}/status — lightweight polling endpoint used while a care plan is generating. */
    async getCarePlanStatus(id: number): Promise<CarePlanStatus> {
        const response = await api.get<CarePlanStatus>(`/orders/${id}/status`)
        return {
            ...response.data,
            status: String(response.data.status).toLowerCase() as OrderStatus,
        }
    },
}
