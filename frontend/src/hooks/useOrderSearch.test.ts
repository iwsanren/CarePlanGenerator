import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { act, renderHook } from '@testing-library/react'
import { EMPTY_FILTERS, INITIAL_SEARCH } from '@/utils/orderSearch'
import { DEBOUNCE_MS, useOrderSearch } from './useOrderSearch'

beforeEach(() => {
  vi.useFakeTimers()
})

afterEach(() => {
  vi.useRealTimers()
})

function setup() {
  return renderHook(() => useOrderSearch())
}

/** Let the typing pause for the debounce time. */
function pause() {
  act(() => { vi.advanceTimersByTime(DEBOUNCE_MS) })
}

/** The parameters of the request that would be sent now (fails the test if the filters are invalid). */
function params(result: ReturnType<typeof setup>['result']) {
  const { build } = result.current
  if (!build.ok) throw new Error('the filters are invalid')
  return build.params
}

describe('useOrderSearch: the starting state', () => {
  it('has no filter, the default order, page 1, no errors and a request for the first page', () => {
    const { result } = setup()

    expect(result.current.search).toEqual(INITIAL_SEARCH)
    expect(result.current.liveFilters).toEqual(EMPTY_FILTERS)
    expect(result.current.errors).toEqual({})
    expect(params(result)).toEqual({ page: 1, page_size: 20 })
  })
})

describe('useOrderSearch: the four text boxes wait for a pause', () => {
  it('shows what is typed at once, but changes the search only after the pause', () => {
    const { result } = setup()

    act(() => result.current.setDraft('medication', 'ivig'))

    expect(result.current.liveFilters.medication).toBe('ivig') // the box shows it
    expect(result.current.search.filters.medication).toBe('') // the search does not know yet
    expect(params(result)).toEqual({ page: 1, page_size: 20 })

    pause()

    expect(result.current.search.filters.medication).toBe('ivig')
    expect(params(result)).toEqual({ page: 1, page_size: 20, medication: 'ivig' })
  })

  it.each([
    ['patientName', 'patient_name', 'Jane'],
    ['mrn', 'mrn', '123456'],
    ['medication', 'medication', 'IVIG'],
    ['providerName', 'provider_name', 'Dr. Green'],
  ] as const)('%s ends up as %s in the request', (field, parameter, text) => {
    const { result } = setup()

    act(() => result.current.setDraft(field, text))
    pause()

    expect(params(result)).toEqual({ page: 1, page_size: 20, [parameter]: text })
  })

  it('a burst of typing is one change: the in-between texts never reach the search', () => {
    const { result } = setup()

    for (const text of ['J', 'Ja', 'Jan', 'Jane']) {
      act(() => result.current.setDraft('patientName', text))
      act(() => { vi.advanceTimersByTime(100) })
    }
    expect(result.current.search.filters.patientName).toBe('')

    pause()

    expect(result.current.search.filters.patientName).toBe('Jane')
  })

  it('the boxes are independent: typing in one keeps what is in the others', () => {
    const { result } = setup()

    act(() => result.current.setDraft('medication', 'ivig'))
    act(() => result.current.setDraft('providerName', 'green'))
    pause()

    expect(result.current.liveFilters).toMatchObject({ medication: 'ivig', providerName: 'green' })
    expect(params(result)).toMatchObject({ medication: 'ivig', provider_name: 'green' })
  })

  it('a committed text goes back to page 1', () => {
    const { result } = setup()
    act(() => result.current.setPage(3))

    act(() => result.current.setDraft('medication', 'ivig'))
    pause()

    expect(result.current.search.page).toBe(1)
  })

  it('typing something and putting it back before the pause leaves the page alone', () => {
    const { result } = setup()
    act(() => result.current.setDraft('medication', 'a'))
    pause()
    act(() => result.current.setPage(2))

    act(() => result.current.setDraft('medication', 'ab'))
    act(() => result.current.setDraft('medication', 'a')) // back to what is committed
    pause()

    expect(result.current.search.filters.medication).toBe('a')
    expect(result.current.search.page).toBe(2)
  })
})

describe('useOrderSearch: status, dates, sort and page change at once', () => {
  it('setStatus applies immediately and goes back to page 1', () => {
    const { result } = setup()
    act(() => result.current.setPage(3))

    act(() => result.current.setStatus('failed'))

    expect(result.current.search.filters.status).toBe('failed')
    expect(result.current.search.page).toBe(1)
    expect(params(result)).toEqual({ page: 1, page_size: 20, status: 'failed' })
  })

  it('setDate sets the start and the end separately, and goes back to page 1', () => {
    const { result } = setup()
    act(() => result.current.setPage(2))

    act(() => result.current.setDate('createdFrom', '2026-11-01'))
    expect(result.current.search.page).toBe(1)
    act(() => result.current.setDate('createdTo', '2026-11-30'))

    expect(params(result)).toEqual({
      page: 1, page_size: 20, created_from: '2026-11-01', created_to: '2026-11-30',
    })
  })

  it('toggleSort goes ascending, descending, default, and back to page 1', () => {
    const { result } = setup()
    act(() => result.current.setPage(2))

    act(() => result.current.toggleSort('patient_name'))
    expect(result.current.search.sort).toEqual({ column: 'patient_name', direction: 'asc' })
    expect(result.current.search.page).toBe(1)
    expect(params(result).sort).toBe('patient_name,asc')

    act(() => result.current.toggleSort('patient_name'))
    expect(params(result).sort).toBe('patient_name,desc')

    act(() => result.current.toggleSort('patient_name'))
    expect(result.current.search.sort).toBeNull()
    expect(params(result)).not.toHaveProperty('sort')
  })

  it('setPage changes only the page', () => {
    const { result } = setup()
    act(() => result.current.setStatus('failed'))

    act(() => result.current.setPage(4))

    expect(result.current.search.page).toBe(4)
    expect(result.current.search.filters.status).toBe('failed')
    expect(params(result)).toEqual({ page: 4, page_size: 20, status: 'failed' })
  })

  it('a status chosen while a text is still being typed: both end up applied', () => {
    const { result } = setup()

    act(() => result.current.setDraft('medication', 'ivig'))
    act(() => result.current.setStatus('completed')) // before the pause
    expect(params(result)).toEqual({ page: 1, page_size: 20, status: 'completed' })

    pause()

    expect(params(result)).toEqual({ page: 1, page_size: 20, status: 'completed', medication: 'ivig' })
  })
})

describe('useOrderSearch: clear', () => {
  it('empties the boxes and every filter at once, without waiting for a pause', () => {
    const { result } = setup()
    act(() => result.current.setDraft('patientName', 'Jane'))
    act(() => result.current.setStatus('failed'))
    act(() => result.current.setDate('createdFrom', '2026-11-01'))
    pause()
    act(() => result.current.setPage(2))

    act(() => result.current.clear())

    expect(result.current.liveFilters).toEqual(EMPTY_FILTERS)
    expect(result.current.search.filters).toEqual(EMPTY_FILTERS)
    expect(result.current.search.page).toBe(1)
    expect(params(result)).toEqual({ page: 1, page_size: 20 })
  })

  it('keeps the sort', () => {
    const { result } = setup()
    act(() => result.current.toggleSort('medication'))
    act(() => result.current.setStatus('failed'))

    act(() => result.current.clear())

    expect(result.current.search.sort).toEqual({ column: 'medication', direction: 'asc' })
  })

  it('what was typed but not yet committed does not come back after the pause', () => {
    const { result } = setup()
    act(() => result.current.setDraft('medication', 'abc')) // still waiting for the pause

    act(() => result.current.clear())
    pause()

    expect(result.current.search.filters.medication).toBe('')
    expect(result.current.liveFilters.medication).toBe('')
  })

  it('a committed text does not come back after the pause either', () => {
    const { result } = setup()
    act(() => result.current.setDraft('medication', 'abc'))
    pause()
    expect(result.current.search.filters.medication).toBe('abc')

    act(() => result.current.clear())
    pause()

    expect(result.current.search.filters.medication).toBe('')
  })
})

describe('useOrderSearch: invalid filters', () => {
  it('a wrong MRN gives an error and no parameters, once the typing has paused', () => {
    const { result } = setup()

    act(() => result.current.setDraft('mrn', '12'))
    expect(result.current.build.ok).toBe(true) // not judged while it is still being typed

    pause()

    expect(result.current.build.ok).toBe(false)
    expect(result.current.errors).toEqual({ mrn: 'MRN must be exactly 6 digits' })
  })

  it('fixing it makes the request possible again', () => {
    const { result } = setup()
    act(() => result.current.setDraft('mrn', '12'))
    pause()

    act(() => result.current.setDraft('mrn', '123456'))
    pause()

    expect(result.current.errors).toEqual({})
    expect(params(result)).toEqual({ page: 1, page_size: 20, mrn: '123456' })
  })

  it('a start date after the end date is an error on the end date', () => {
    const { result } = setup()

    act(() => result.current.setDate('createdFrom', '2026-11-16'))
    act(() => result.current.setDate('createdTo', '2026-11-15'))

    expect(result.current.build.ok).toBe(false)
    expect(result.current.errors).toEqual({ createdTo: 'The end date must not be before the start date' })
  })
})
