/**
 * Pure rules of the order list search: what the user can choose (filters, sort, page), how a change affects the
 * rest, what is valid, and how the choice becomes the request parameters of GET /orders. No React in here, so
 * every rule is tested directly.
 */
import type { ApiError, OrderListParams, OrderStatus } from '@/types'
import { MRN_REGEX } from '@/utils/validators'

/** Rows per page. The backend accepts 1..100. */
export const PAGE_SIZE = 20
/** The longest search text the backend accepts (INVALID_SEARCH_TEXT above it). */
export const MAX_TEXT_LENGTH = 100
/** The backend refuses dates outside this range (INVALID_DATE). ISO dates sort like text, so ">=" works on them. */
export const EARLIEST_DATE = '1900-01-01'
export const LATEST_DATE = '2999-12-31'

// ---- what the user can choose ---------------------------------------------------------------------

export type StatusFilter = OrderStatus | 'all'
/** In the order the buttons appear. */
export const STATUS_FILTERS: readonly StatusFilter[] = ['all', 'completed', 'pending', 'processing', 'failed']

/** The columns the list can be sorted by (the Care Plan column is derived from the status, so it is not one). */
export const SORT_COLUMNS = ['patient_name', 'provider_name', 'medication', 'status', 'created_at'] as const
export type SortColumn = (typeof SORT_COLUMNS)[number]
export type SortDirection = 'asc' | 'desc'
export interface OrderSort {
    column: SortColumn
    direction: SortDirection
}

export interface OrderFilters {
    status: StatusFilter
    patientName: string
    mrn: string
    medication: string
    providerName: string
    /** yyyy-MM-dd as an <input type="date"> gives it, or '' */
    createdFrom: string
    createdTo: string
}

export interface OrderSearchState {
    filters: OrderFilters
    /** null = the backend's default order (newest first); this is the third step of the header click cycle */
    sort: OrderSort | null
    page: number
}

export const EMPTY_FILTERS: OrderFilters = {
    status: 'all',
    patientName: '',
    mrn: '',
    medication: '',
    providerName: '',
    createdFrom: '',
    createdTo: '',
}

export const INITIAL_SEARCH: OrderSearchState = { filters: EMPTY_FILTERS, sort: null, page: 1 }

// ---- words shown to the user ---------------------------------------------------------------------

export const STATUS_FILTER_LABELS: Record<StatusFilter, string> = {
    all: 'All',
    completed: 'Completed',
    pending: 'Pending',
    processing: 'Processing',
    failed: 'Failed',
}

export const SORT_COLUMN_LABELS: Record<SortColumn, string> = {
    patient_name: 'Patient',
    provider_name: 'Provider',
    medication: 'Medication',
    status: 'Status',
    created_at: 'Created',
}

/** The line above the table that says in words how the list is ordered (a screen reader hears it too). */
export function describeSort(sort: OrderSort | null): string {
    if (sort === null) return 'Sorted by: newest first (default)'
    const direction = sort.direction === 'asc' ? 'ascending' : 'descending'
    return `Sorted by: ${SORT_COLUMN_LABELS[sort.column]} (${direction})`
}

/** "1 result" / "45 results" */
export function resultsLabel(count: number): string {
    return `${count} ${count === 1 ? 'result' : 'results'}`
}

// ---- changes: every change to the filters or the sort goes back to page 1 -------------------------

/** Changes some filters. The page goes back to 1: page 3 of the old results means nothing for the new ones. */
export function withFilters(state: OrderSearchState, changes: Partial<OrderFilters>): OrderSearchState {
    return { ...state, filters: { ...state.filters, ...changes }, page: 1 }
}

/** A click on a column header; page 1 again, for the same reason as above. */
export function withSort(state: OrderSearchState, column: SortColumn): OrderSearchState {
    return { ...state, sort: nextSort(state.sort, column), page: 1 }
}

/** Only the page changes (the Previous and Next buttons). */
export function withPage(state: OrderSearchState, page: number): OrderSearchState {
    return { ...state, page }
}

/** "Clear all filters". The sort is not a filter, so it stays. */
export function clearFilters(state: OrderSearchState): OrderSearchState {
    return { ...state, filters: EMPTY_FILTERS, page: 1 }
}

/**
 * The header click cycle for one column: ascending, then descending, then back to the default order.
 * Clicking a different column than the current one starts again at ascending.
 */
export function nextSort(current: OrderSort | null, column: SortColumn): OrderSort | null {
    if (current === null || current.column !== column) return { column, direction: 'asc' }
    return current.direction === 'asc' ? { column, direction: 'desc' } : null
}

/** "created_at,desc": the one string the backend reads. */
export function sortToParam(sort: OrderSort): string {
    return `${sort.column},${sort.direction}`
}

/** How many filters are set (a blank text does not count). Drives "Clear filters" and the empty state. */
export function activeFilterCount(filters: OrderFilters): number {
    const texts = [
        filters.patientName, filters.mrn, filters.medication, filters.providerName,
        filters.createdFrom, filters.createdTo,
    ]
    return texts.filter((text) => text.trim() !== '').length + (filters.status === 'all' ? 0 : 1)
}

export function hasActiveFilters(filters: OrderFilters): boolean {
    return activeFilterCount(filters) > 0
}

// ---- validation: a filter that fails here is never sent -------------------------------------------

export type FilterField = 'patientName' | 'mrn' | 'medication' | 'providerName' | 'createdFrom' | 'createdTo'
/** One readable message per field that is wrong; a field that is fine is absent. */
export type FilterErrors = Partial<Record<FilterField, string>>

const TEXT_FIELDS: { field: 'patientName' | 'medication' | 'providerName'; label: string }[] = [
    { field: 'patientName', label: 'Patient name' },
    { field: 'medication', label: 'Medication' },
    { field: 'providerName', label: 'Provider name' },
]

const DATE_ERROR = `Enter a valid date between ${EARLIEST_DATE} and ${LATEST_DATE}`

/** A real calendar day written yyyy-MM-dd and inside the range the backend accepts. */
export function isAllowedDate(value: string): boolean {
    if (!/^\d{4}-\d{2}-\d{2}$/.test(value)) return false
    if (value < EARLIEST_DATE || value > LATEST_DATE) return false
    // "2026-02-30" has the right shape but is not a day: it rolls over to March, so it does not round-trip.
    const parsed = new Date(`${value}T00:00:00Z`)
    return !Number.isNaN(parsed.getTime()) && parsed.toISOString().slice(0, 10) === value
}

export function validateFilters(filters: OrderFilters): FilterErrors {
    const errors: FilterErrors = {}

    for (const { field, label } of TEXT_FIELDS) {
        if (filters[field].trim().length > MAX_TEXT_LENGTH) {
            errors[field] = `${label} must be at most ${MAX_TEXT_LENGTH} characters`
        }
    }

    const mrn = filters.mrn.trim()
    if (mrn !== '' && !MRN_REGEX.test(mrn)) errors.mrn = 'MRN must be exactly 6 digits'

    const from = filters.createdFrom
    const to = filters.createdTo
    const fromValid = from !== '' && isAllowedDate(from)
    const toValid = to !== '' && isAllowedDate(to)
    if (from !== '' && !fromValid) errors.createdFrom = DATE_ERROR
    if (to !== '' && !toValid) errors.createdTo = DATE_ERROR
    if (fromValid && toValid && from > to) errors.createdTo = 'The end date must not be before the start date'

    return errors
}

// ---- state -> request parameters ---------------------------------------------------------------

/** Either the parameters to send, or the fields that are wrong (and then nothing is sent). */
export type RequestBuild =
    | { ok: true; params: OrderListParams }
    | { ok: false; errors: FilterErrors }

type TextParam = 'patient_name' | 'mrn' | 'medication' | 'provider_name' | 'created_from' | 'created_to'

/** {key: trimmed value}, or nothing at all when the text is blank: an empty parameter is never sent. */
function present<K extends TextParam>(key: K, value: string): Partial<Record<K, string>> {
    const trimmed = value.trim()
    return trimmed === '' ? {} : ({ [key]: trimmed } as Record<K, string>)
}

export function toRequestParams(state: OrderSearchState): RequestBuild {
    const errors = validateFilters(state.filters)
    if (Object.keys(errors).length > 0) return { ok: false, errors }

    const { filters } = state
    return {
        ok: true,
        params: {
            page: state.page,
            page_size: PAGE_SIZE,
            ...(filters.status === 'all' ? {} : { status: filters.status }),
            ...present('patient_name', filters.patientName),
            ...present('mrn', filters.mrn),
            ...present('medication', filters.medication),
            ...present('provider_name', filters.providerName),
            ...present('created_from', filters.createdFrom),
            ...present('created_to', filters.createdTo),
            ...(state.sort === null ? {} : { sort: sortToParam(state.sort) }),
        },
    }
}

// ---- errors from the backend ---------------------------------------------------------------------

/** What to tell the user about a failed list request. The backend's messages never contain the filter values. */
export function describeListError(error: unknown): string {
    const { code, detail, message } = (error ?? {}) as Partial<ApiError>
    switch (code) {
        case 'INVALID_SORT':
            return 'This sort is not supported. Choose another column, or click the header again to reset the sort.'
        case 'INVALID_DATE_RANGE':
            return 'The start date must not be after the end date.'
        case 'INVALID_DATE':
            return `Dates must be between ${EARLIEST_DATE} and ${LATEST_DATE}.`
        case 'INVALID_PAGE_SIZE':
            return 'This page size is not supported.'
        case 'INVALID_PAGE':
            return 'This page does not exist. Go to the first page.'
        case 'INVALID_SEARCH_TEXT':
            return `Search texts must be at most ${MAX_TEXT_LENGTH} characters.`
        case 'INVALID_STATUS':
            return 'This status is not supported.'
        case 'INVALID_REQUEST_PARAM':
            return invalidParamMessage(detail)
        default:
            return `Error loading orders: ${message ?? ''}`
    }
}

/** INVALID_REQUEST_PARAM carries {parameterName: "has an invalid value"} in detail; name the parameter if it is there. */
function invalidParamMessage(detail: unknown): string {
    const names = detail !== null && typeof detail === 'object' ? Object.keys(detail) : []
    return names.length > 0 ? `A filter value is not valid (${names[0]}).` : 'A filter value is not valid.'
}
