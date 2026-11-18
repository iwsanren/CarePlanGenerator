import { useState } from 'react'
import { useDebouncedValue } from '@/hooks/useDebouncedValue'
import {
    clearFilters,
    INITIAL_SEARCH,
    toRequestParams,
    withFilters,
    withPage,
    withSort,
    type FilterErrors,
    type OrderFilters,
    type OrderSearchState,
    type RequestBuild,
    type SortColumn,
    type StatusFilter,
} from '@/utils/orderSearch'

/** How long the text boxes must be left alone before the search changes. */
export const DEBOUNCE_MS = 300

/** The four boxes people type in. Everything else (status, dates, sort, page) changes at once. */
export const TEXT_FIELDS = ['patientName', 'mrn', 'medication', 'providerName'] as const
export type TextField = (typeof TEXT_FIELDS)[number]
export type TextDrafts = Record<TextField, string>

const EMPTY_DRAFTS: TextDrafts = { patientName: '', mrn: '', medication: '', providerName: '' }

/**
 * The state of the order list search, in two layers:
 * - `drafts` is what is in the four text boxes right now (updated on every keystroke);
 * - `search` is the committed state the list is built from: the drafts join it only after the typing has
 *   paused for DEBOUNCE_MS, so one pause means one request.
 * Status, dates, sort and page go straight into `search`. Every change to filters or sort goes back to page 1.
 *
 * In this step the state lives in the component. (The next step moves part of it into the URL; this hook keeps
 * the same shape, so the page does not change.)
 */
export function useOrderSearch() {
    const [search, setSearch] = useState<OrderSearchState>(INITIAL_SEARCH)
    const [drafts, setDrafts] = useState<TextDrafts>(EMPTY_DRAFTS)
    const settled = useDebouncedValue(drafts, DEBOUNCE_MS)

    // The pause is over (`settled` is a new value): commit the texts, unless they are what is already committed.
    // This is the "adjust state while rendering" pattern from the React docs: it runs once per change of
    // `settled`, and unlike an effect it does not paint a frame with the old texts first.
    const [committedDrafts, setCommittedDrafts] = useState(settled)
    if (settled !== committedDrafts) {
        setCommittedDrafts(settled)
        setSearch((current) =>
            TEXT_FIELDS.every((field) => current.filters[field] === settled[field])
                ? current
                : withFilters(current, settled),
        )
    }

    /** What the filter panel shows: committed status and dates, but the texts as they are typed. */
    const liveFilters: OrderFilters = { ...search.filters, ...drafts }
    const build: RequestBuild = toRequestParams(search)
    const errors: FilterErrors = build.ok ? {} : build.errors

    return {
        search,
        liveFilters,
        build,
        errors,
        setDraft: (field: TextField, value: string) => setDrafts((current) => ({ ...current, [field]: value })),
        setStatus: (status: StatusFilter) => setSearch((current) => withFilters(current, { status })),
        setDate: (field: 'createdFrom' | 'createdTo', value: string) =>
            setSearch((current) => withFilters(current, { [field]: value })),
        toggleSort: (column: SortColumn) => setSearch((current) => withSort(current, column)),
        setPage: (page: number) => setSearch((current) => withPage(current, page)),
        /** Empties the boxes and the committed filters at once (no waiting for the pause), keeps the sort. */
        clear: () => {
            setDrafts(EMPTY_DRAFTS)
            setSearch(clearFilters)
        },
    }
}
