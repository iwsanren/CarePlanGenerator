/** The four care plan statuses returned by GET /orders */
export type OrderStatus = 'pending' | 'processing' | 'completed' | 'failed'

export type ResultType = 'SUCCESS' | 'WARNING'

/** Response body for POST /orders (201) and GET /orders/{id} (200) */
/** One duplicate-detection warning returned by the backend. */
export interface Warning {
    code: string
    message: string
    actionRequired: boolean
}

/** Shape of the JSON body the backend's ApiErrorResponse serializes to. */
export interface ApiErrorBody {
    type?: 'block' | 'warning' | 'validation' | 'error'
    code?: string
    message?: string
    detail?: unknown
    httpStatus?: number
}

/** The Error shape thrown by the axios interceptor in services/api.ts. */
export type ApiError = Error & {
    type?: ApiErrorBody['type']
    code?: string
    detail?: unknown
    status?: number   // the real HTTP status code, read from the transport layer
}

/** detail payload on a CONFIRMATION_REQUIRED (type: "warning") error */
export interface ConfirmationRequiredDetail {
    requiresConfirm: true
    warnings: Warning[]
}

/** Request body for POST /orders. Field names/types match the backend's
 *  CreateOrderRequest exactly -- no snake_case<->camelCase conversion layer. */
export interface CreateOrderRequest {
    patientFirstName: string
    patientLastName: string
    patientMrn: string
    patientDateOfBirth?: string          // "YYYY-MM-DD", optional
    patientSex?: string
    patientWeightKg?: number
    patientAllergies?: string
    providerName: string
    providerNpi: string
    medicationName: string
    primaryDiagnosis: string
    additionalDiagnoses: string[]
    medicationHistory: string[]
    patientRecords?: string
    confirm?: boolean
}

export interface OrderResponse {
    id: number
    patientId: number
    providerId: number
    patientFirstName: string
    patientLastName: string
    patientMrn: string
    patientDateOfBirth?: string
    providerName: string
    providerNpi: string
    medicationName: string
    status: OrderStatus
    carePlanContent?: string | null   // Present only when status === 'completed'
    resultType: ResultType
    message?: string | null
    warnings: Warning[]               // Structured objects since Phase 3
    requiresConfirm: boolean
}

/** A single item in the paginated GET /orders list; contains fewer fields than OrderResponse */
export interface OrderListItem {
    id: number
    patientName: string
    patientMrn: string
    medicationName: string
    status: OrderStatus
    createdAt: string                 // ISO-8601 UTC, e.g. "2026-08-30T12:34:56Z"
    providerName: string
    providerNpi: string
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