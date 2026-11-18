/**
 * The part of the order list search that lives in the address bar, and how it is read and written.
 *
 * Only these seven things are in the URL: status, created_from, created_to, medication, provider_name, sort and
 * page. The patient name and the MRN are patient information: they are never read from the URL and never written
 * to it (they live in memory, see orderSearchMemory.ts). Reading is a whitelist, so any other key is ignored.
 *
 * Pure functions, no React: every rule is tested directly.
 */
import {
    isAllowedDate,
    SORT_COLUMNS,
    sortToParam,
    STATUS_FILTERS,
    type OrderFilters,
    type OrderSearchState,
    type OrderSort,
    type SortColumn,
    type SortDirection,
    type StatusFilter,
} from '@/utils/orderSearch'

/** The filters that are allowed in the URL (everything in OrderFilters except the two patient fields). */
export type UrlFilterField = 'status' | 'medication' | 'providerName' | 'createdFrom' | 'createdTo'

/** What the URL can say: a search without the patient name and the MRN. */
export interface UrlSearch {
    filters: Pick<OrderFilters, UrlFilterField>
    sort: OrderSort | null
    page: number
}

const KEYS = {
    status: 'status',
    createdFrom: 'created_from',
    createdTo: 'created_to',
    medication: 'medication',
    providerName: 'provider_name',
    sort: 'sort',
    page: 'page',
} as const

/** Up to 9 digits (fits a Java int), no leading zero: "0", "-1", "abc", "1.5" and "007" are not pages. */
const PAGE_REGEX = /^[1-9]\d{0,8}$/

function parseStatus(value: string | null): StatusFilter {
    return STATUS_FILTERS.find((status) => status === value) ?? 'all'
}

/** "column,direction" with a column the list can sort by; anything else (for example "password") is no sort. */
function parseSort(value: string | null): OrderSort | null {
    const [column, direction, ...rest] = (value ?? '').split(',')
    const knownColumn = SORT_COLUMNS.find((candidate) => candidate === column)
    const knownDirection = (['asc', 'desc'] as const).find((candidate) => candidate === direction)
    if (knownColumn === undefined || knownDirection === undefined || rest.length > 0) return null
    return { column: knownColumn as SortColumn, direction: knownDirection as SortDirection }
}

function parsePage(value: string | null): number {
    return value !== null && PAGE_REGEX.test(value) ? Number(value) : 1
}

/** A date that is not a real day inside the allowed range is no date (so it cannot reach a request or a field). */
function parseDate(value: string | null): string {
    return value !== null && isAllowedDate(value) ? value : ''
}

/**
 * Reads the address bar. Only the whitelisted keys count, the first value of a repeated key wins, and every
 * value that is not valid becomes the default. The result is always a state the page can show.
 */
export function parseUrlSearch(params: URLSearchParams): UrlSearch {
    return {
        filters: {
            status: parseStatus(params.get(KEYS.status)),
            createdFrom: parseDate(params.get(KEYS.createdFrom)),
            createdTo: parseDate(params.get(KEYS.createdTo)),
            medication: params.get(KEYS.medication) ?? '',
            providerName: params.get(KEYS.providerName) ?? '',
        },
        sort: parseSort(params.get(KEYS.sort)),
        page: parsePage(params.get(KEYS.page)),
    }
}

/** The text is kept as typed (a space between two words must survive); a blank text is not written at all. */
function setText(params: URLSearchParams, key: string, value: string) {
    if (value.trim() !== '') params.set(key, value)
}

/**
 * The address-bar form of a search. It is built from scratch, never from the old URL, so a key that was not
 * meant to be there (a patient name someone put in a link, an unknown parameter) is gone after the next change.
 * Defaults are left out: the default list is a bare "/orders".
 */
export function toUrlParams(state: OrderSearchState): URLSearchParams {
    const params = new URLSearchParams()
    const { filters } = state
    if (filters.status !== 'all') params.set(KEYS.status, filters.status)
    if (filters.createdFrom !== '') params.set(KEYS.createdFrom, filters.createdFrom)
    if (filters.createdTo !== '') params.set(KEYS.createdTo, filters.createdTo)
    setText(params, KEYS.medication, filters.medication)
    setText(params, KEYS.providerName, filters.providerName)
    if (state.sort !== null) params.set(KEYS.sort, sortToParam(state.sort))
    if (state.page !== 1) params.set(KEYS.page, String(state.page))
    return params
}

/** What the list puts into the router state of the link to an order; the detail page reads it back. */
export interface OrderLinkState {
    /** The list's "?status=failed&page=2" (empty when the list had no parameters) at the moment of the click. */
    ordersSearch: string
}

/**
 * The link back to the list from an order. The router state is whatever the history entry holds (null when the
 * order was opened directly). The saved query is read again through the whitelist, so what comes out is always a
 * clean list URL; without a usable state the link is the bare "/orders".
 */
export function ordersListPath(state: unknown): string {
    const ordersSearch = (state as Partial<OrderLinkState> | null)?.ordersSearch
    if (typeof ordersSearch !== 'string') return '/orders'
    const { filters, sort, page } = parseUrlSearch(new URLSearchParams(ordersSearch))
    const query = toUrlParams({ filters: { ...filters, patientName: '', mrn: '' }, sort, page }).toString()
    return query === '' ? '/orders' : `/orders?${query}`
}
