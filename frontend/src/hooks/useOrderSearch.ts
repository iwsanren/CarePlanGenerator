import { useEffect, useRef, useState } from 'react'
import { useSearchParams } from 'react-router-dom'
import { useDebouncedValue } from '@/hooks/useDebouncedValue'
import { useOrderSearchMemory } from '@/hooks/useOrderSearchMemory'
import {
    clearFilters,
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
import { parseUrlSearch, toUrlParams } from '@/utils/orderSearchUrl'

/** How long the text boxes must be left alone before the search changes. */
export const DEBOUNCE_MS = 300

/** The four boxes people type in. Everything else (status, dates, sort, page) changes at once. */
export const TEXT_FIELDS = ['patientName', 'mrn', 'medication', 'providerName'] as const
export type TextField = (typeof TEXT_FIELDS)[number]
/** What is typed in the boxes that the search does not know yet. A box that is absent shows the search's value. */
export type TextDrafts = Partial<Record<TextField, string>>

/**
 * The state of the order list search, in three places:
 * - the URL has status, dates, medication, provider name, sort and page (so reload, Back and a shared link work);
 * - the memory (a context in the Layout) has the patient name and MRN: patient information never goes to the URL;
 * - `drafts` has what is typed in the four boxes and not yet applied (a text is applied after DEBOUNCE_MS without
 *   typing, so one pause means one request).
 * `search` is not stored: it is read from the URL and the memory on every render, so the three cannot disagree.
 *
 * Every change goes through `update`: one function that works out the next search and writes both places in the
 * same event, so a render never sees the new name together with the old page. The page goes into the history
 * (Back goes to the previous page); everything else replaces the current entry.
 */
export function useOrderSearch() {
    const [urlParams, setUrlParams] = useSearchParams()
    const { memory, setMemory } = useOrderSearchMemory()
    const [drafts, setDrafts] = useState<TextDrafts>({})

    const urlSearch = parseUrlSearch(urlParams)
    const search: OrderSearchState = {
        filters: { ...urlSearch.filters, patientName: memory.patientName, mrn: memory.mrn },
        sort: urlSearch.sort,
        page: urlSearch.page,
    }

    /**
     * Works out the next search and writes the memory part and the URL part together, in the same event.
     * `history: 'push'` is for the page only (Back then goes to the previous page); every other change replaces
     * the current entry, so Back does not walk through each filter tweak.
     */
    const update = (change: (current: OrderSearchState) => OrderSearchState, history: 'push' | 'replace' = 'replace') => {
        const next = change(search)
        if (next.filters.patientName !== memory.patientName || next.filters.mrn !== memory.mrn) {
            setMemory({ patientName: next.filters.patientName, mrn: next.filters.mrn })
        }
        // The URL is always written, also when it would look the same: that is what drops a parameter that
        // should not be there (see toUrlParams).
        setUrlParams(toUrlParams(next), { replace: history === 'replace' })
    }

    /** The typed texts that differ from the search's: the pause is over, make them the search. */
    const applyTexts = (typed: TextDrafts) => {
        const changes: Partial<Record<TextField, string>> = {}
        for (const field of TEXT_FIELDS) {
            const text = typed[field]
            if (text !== undefined && text !== search.filters[field]) changes[field] = text
        }
        if (Object.keys(changes).length > 0) update((current) => withFilters(current, changes))

        // The boxes that were applied show the search's value from now on; a box typed in since is left alone.
        setDrafts((current) => {
            const stillTyped: TextDrafts = {}
            for (const field of TEXT_FIELDS) {
                if (current[field] !== undefined && current[field] !== typed[field]) stillTyped[field] = current[field]
            }
            // The same object when nothing changed: a new one would restart the debounce and loop forever.
            return Object.keys(stillTyped).length === Object.keys(current).length ? current : stillTyped
        })
    }

    // The pause is over (`settled` is a new value): apply it. The effect runs only when `settled` changes, so
    // a URL change from outside (Back, a link) is never undone by an old text. It calls the newest applyTexts.
    const settled = useDebouncedValue(drafts, DEBOUNCE_MS)
    const latestApply = useRef(applyTexts)
    useEffect(() => {
        latestApply.current = applyTexts
    })
    useEffect(() => {
        latestApply.current(settled)
    }, [settled])

    /** What the filter panel shows: the texts as they are typed, everything else as it is in the search. */
    const liveFilters: OrderFilters = { ...search.filters, ...drafts }
    const build: RequestBuild = toRequestParams(search)
    const errors: FilterErrors = build.ok ? {} : build.errors

    return {
        search,
        liveFilters,
        build,
        errors,
        setDraft: (field: TextField, value: string) => setDrafts((current) => ({ ...current, [field]: value })),
        setStatus: (status: StatusFilter) => update((current) => withFilters(current, { status })),
        setDate: (field: 'createdFrom' | 'createdTo', value: string) =>
            update((current) => withFilters(current, { [field]: value })),
        toggleSort: (column: SortColumn) => update((current) => withSort(current, column)),
        setPage: (page: number) =>
            update((current) => withPage(current, page), page === search.page ? 'replace' : 'push'),
        /** Empties the boxes and every filter at once (no waiting for the pause), keeps the sort. */
        clear: () => {
            setDrafts({})
            update(clearFilters)
        },
    }
}
