/** The four care plan statuses returned by GET /orders */
export type OrderStatus = 'pending' | 'processing' | 'completed' | 'failed'

export type ResultType = 'SUCCESS' | 'WARNING'

/** Response body for POST /orders (201) and GET /orders/{id} (200) */
export interface OrderResponse {
    id: number
    patientId: number
    providerId: number
    medicationName: string
    status: OrderStatus
    carePlanContent?: string | null   // Present only when status === 'completed'
    resultType: ResultType
    message?: string | null
    warnings: string[]                // Plain string array, not structured objects
    requiresConfirm: boolean
}

/** A single item in the paginated GET /orders list; contains fewer fields than OrderResponse */
export interface OrderListItem {
    id: number
    patientName: string
    medicationName: string
    status: OrderStatus
    createdAt: string                 // ISO-8601 UTC, e.g. "2026-08-30T12:34:56Z"
}

/** Paginated response for GET /orders: page-based pagination (page + pageSize), not next/previous URLs */
export interface PagedResponse<T> {
    count: number                     // Total number of records matching the filters, not the current page size
    page: number
    pageSize: number
    results: T[]
}

/** Polling response for GET /orders/{id}/status */
export interface CarePlanStatus {
    orderId: number
    status: OrderStatus
    carePlanPreview?: string
    errorMessage?: string
}

/** Query parameters for GET /orders; these query param names remain snake_case on the backend */
export interface OrderListParams {
    page?: number
    page_size?: number
    status?: OrderStatus
    patient_id?: number
    provider_id?: number
    patient_name?: string
}