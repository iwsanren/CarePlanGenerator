import type { ApiError, OrderListItem, OrderResponse, PagedResponse } from '@/types'

export function makeOrder(overrides: Partial<OrderResponse> = {}): OrderResponse {
  return {
    id: 7,
    patientId: 1,
    providerId: 2,
    patientFirstName: 'Jane',
    patientLastName: 'Doe',
    patientMrn: '123456',
    patientDateOfBirth: '1980-01-31',
    providerName: 'Dr. Smith',
    providerNpi: '1234567890',
    medicationName: 'IVIG',
    status: 'completed',
    carePlanContent: 'Problem list\nGoals',
    resultType: 'SUCCESS',
    message: null,
    warnings: [],
    requiresConfirm: false,
    ...overrides,
  }
}

export function makeListItem(overrides: Partial<OrderListItem> = {}): OrderListItem {
  return {
    id: 1,
    patientName: 'Jane Doe',
    patientMrn: '123456',
    medicationName: 'IVIG',
    status: 'completed',
    createdAt: '2026-08-30T12:34:56Z',
    providerName: 'Dr. Smith',
    providerNpi: '1234567890',
    ...overrides,
  }
}

export function makePage(
  results: OrderListItem[],
  overrides: Partial<PagedResponse<OrderListItem>> = {},
): PagedResponse<OrderListItem> {
  return { count: results.length, page: 1, pageSize: 20, results, ...overrides }
}

/** An Error carrying the extra fields services/api.ts attaches to every failure. */
export function makeApiError(message: string, fields: Partial<Omit<ApiError, 'message'>> = {}): ApiError {
  return Object.assign(new Error(message), fields) as ApiError
}
