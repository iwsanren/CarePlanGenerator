import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { act, renderHook } from '@testing-library/react'
import { routerHarness } from '@/test/routerHarness'
import type { OrderListParams } from '@/types'
import { EMPTY_FILTERS, INITIAL_SEARCH } from '@/utils/orderSearch'
import { DEBOUNCE_MS, useOrderSearch } from './useOrderSearch'

beforeEach(() => {
  vi.useFakeTimers()
})

afterEach(() => {
  vi.useRealTimers()
})

/** The hook inside an in-memory router (it reads and writes the URL); `probe` shows the router from outside. */
function setup(route = '/orders', options: { provider?: boolean } = {}) {
  const { Wrapper, probe } = routerHarness(route, options)
  return { ...renderHook(() => useOrderSearch(), { wrapper: Wrapper }), probe }
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

describe('useOrderSearch: the search is read from the URL', () => {
  it('everything the URL holds is in the search and in the request', () => {
    const { result } = setup('/orders?status=failed&medication=ivig&provider_name=green&sort=status%2Cdesc&page=3'
      + '&created_from=2026-11-01&created_to=2026-11-30')

    expect(params(result)).toEqual({
      page: 3, page_size: 20, status: 'failed', medication: 'ivig', provider_name: 'green', sort: 'status,desc',
      created_from: '2026-11-01', created_to: '2026-11-30',
    })
    expect(result.current.liveFilters).toMatchObject({ status: 'failed', medication: 'ivig', providerName: 'green' })
  })

  it('a URL with junk in it gives the default for each bad part', () => {
    const { result } = setup('/orders?status=nope&page=-4&sort=password&created_from=2026-02-30')

    expect(params(result)).toEqual({ page: 1, page_size: 20 })
    expect(result.current.errors).toEqual({})
  })

  it('does not rewrite the address bar when it only reads it', () => {
    const { probe } = setup('/orders?patient_name=Jane&mrn=123456&sort=password&status=failed')

    expect(probe.location.search).toBe('?patient_name=Jane&mrn=123456&sort=password&status=failed')
  })
})

describe('useOrderSearch: the URL is written', () => {
  it('status, dates and sort are written at once; the defaults are not written', () => {
    const { result, probe } = setup()
    expect(probe.location.search).toBe('')

    act(() => result.current.setStatus('failed'))
    expect(probe.location.search).toBe('?status=failed')

    act(() => result.current.setDate('createdFrom', '2026-11-01'))
    act(() => result.current.toggleSort('patient_name'))
    expect(probe.location.search).toBe('?status=failed&created_from=2026-11-01&sort=patient_name%2Casc')

    act(() => result.current.setStatus('all'))
    expect(probe.location.search).toBe('?created_from=2026-11-01&sort=patient_name%2Casc')
  })

  it('medication and provider name are written after the pause, not while typing', () => {
    const { result, probe } = setup()

    act(() => result.current.setDraft('medication', 'ivig'))
    act(() => result.current.setDraft('providerName', 'Dr. Green'))
    expect(probe.location.search).toBe('')

    pause()

    expect(probe.location.search).toBe('?medication=ivig&provider_name=Dr.+Green')
  })

  it('a text that is only spaces is not written', () => {
    const { result, probe } = setup()

    act(() => result.current.setDraft('medication', '   '))
    pause()

    expect(probe.location.search).toBe('')
    expect(params(result)).toEqual({ page: 1, page_size: 20 })
  })

  it('a change of filters or sort takes the page out of the URL (back to page 1)', () => {
    const { result, probe } = setup('/orders?page=3&status=failed')

    act(() => result.current.setStatus('pending'))
    expect(probe.location.search).toBe('?status=pending')

    act(() => result.current.setPage(4))
    act(() => result.current.toggleSort('status'))
    expect(probe.location.search).toBe('?status=pending&sort=status%2Casc')
  })

  it('a text that is applied takes the page out of the URL too', () => {
    const { result, probe } = setup('/orders?page=3')

    act(() => result.current.setDraft('medication', 'ivig'))
    pause()

    expect(probe.location.search).toBe('?medication=ivig')
  })

  it('clear removes the filters from the URL and keeps the sort', () => {
    const { result, probe } = setup('/orders?status=failed&medication=ivig&sort=status%2Cdesc&page=2')

    act(() => result.current.clear())

    expect(probe.location.search).toBe('?sort=status%2Cdesc')
  })
})

describe('useOrderSearch: history (only the page makes an entry)', () => {
  it('Previous and Next walk through the pages with the Back button', () => {
    const { result, probe } = setup('/orders?status=failed')

    act(() => result.current.setPage(2))
    act(() => result.current.setPage(3))
    expect(probe.location.search).toBe('?status=failed&page=3')

    act(() => probe.navigate(-1))
    expect(probe.location.search).toBe('?status=failed&page=2')
    expect(result.current.search.page).toBe(2)

    act(() => probe.navigate(-1))
    expect(probe.location.search).toBe('?status=failed')
    expect(result.current.search.page).toBe(1)
  })

  it('choosing the page that is already shown adds no entry', () => {
    const { result, probe } = setup('/orders?page=2')
    act(() => result.current.setPage(3))

    act(() => result.current.setPage(3))
    act(() => probe.navigate(-1))

    expect(probe.location.search).toBe('?page=2')
  })

  it('a filter change replaces the entry, so Back does not walk through every tweak', () => {
    const { result, probe } = setup('/orders')
    act(() => result.current.setPage(2)) // entry 2: ?page=2

    act(() => result.current.setStatus('failed')) // replaces entry 2
    act(() => result.current.setStatus('pending')) // replaces it again
    act(() => probe.navigate(-1)) // back to entry 1: the bare list

    expect(probe.location.search).toBe('')
    expect(result.current.search.filters.status).toBe('all')
  })

  it('a text, a date and a sort replace the entry as well', () => {
    const { result, probe } = setup('/orders')
    act(() => result.current.setPage(2))

    act(() => result.current.setDraft('medication', 'ivig'))
    pause()
    act(() => result.current.setDate('createdFrom', '2026-11-01'))
    act(() => result.current.toggleSort('medication'))
    act(() => probe.navigate(-1))

    expect(probe.location.search).toBe('')
  })
})

describe('useOrderSearch: the boxes follow the URL', () => {
  it('Back puts the old text back into the box', () => {
    const { result, probe } = setup('/orders?medication=ivig')
    act(() => result.current.setPage(2)) // entry 2: ?medication=ivig&page=2
    act(() => result.current.setDraft('medication', 'ivig plus'))
    pause() // replaces entry 2 with ?medication=ivig+plus
    expect(result.current.liveFilters.medication).toBe('ivig plus')

    act(() => probe.navigate(-1))

    expect(probe.location.search).toBe('?medication=ivig')
    expect(result.current.liveFilters.medication).toBe('ivig')
    expect(params(result)).toEqual({ page: 1, page_size: 20, medication: 'ivig' })
  })

  it('a change of the URL from outside (a link) shows in the boxes and the request', () => {
    const { result, probe } = setup('/orders?medication=ivig')

    act(() => probe.navigate('/orders?provider_name=green&status=failed'))

    expect(result.current.liveFilters).toMatchObject({ medication: '', providerName: 'green', status: 'failed' })
    expect(params(result)).toEqual({ page: 1, page_size: 20, status: 'failed', provider_name: 'green' })
  })

  it('and an old text is not applied again afterwards', () => {
    const { result, probe } = setup('/orders?medication=ivig')
    act(() => result.current.setDraft('medication', 'ivig plus'))
    pause() // applied: ?medication=ivig+plus

    act(() => probe.navigate('/orders?medication=other'))
    pause()
    pause()

    expect(probe.location.search).toBe('?medication=other')
    expect(result.current.liveFilters.medication).toBe('other')
  })

  it('keeps typing after a text was applied: the new letters are not overwritten by what was applied', () => {
    const { result, probe } = setup()
    act(() => result.current.setDraft('medication', 'ab'))
    pause()
    expect(probe.location.search).toBe('?medication=ab')

    act(() => result.current.setDraft('medication', 'abc'))

    expect(result.current.liveFilters.medication).toBe('abc')
    expect(probe.location.search).toBe('?medication=ab') // still waiting for the pause

    pause()

    expect(probe.location.search).toBe('?medication=abc')
    expect(result.current.liveFilters.medication).toBe('abc')
  })

  it('a text with a space at the end keeps the space in the box', () => {
    const { result } = setup()

    act(() => result.current.setDraft('medication', 'immune '))
    pause()

    expect(result.current.liveFilters.medication).toBe('immune ')
  })

  it('two boxes typed in before the pause are both applied', () => {
    const { result, probe } = setup('/orders?page=2')

    act(() => result.current.setDraft('medication', 'ivig'))
    act(() => result.current.setDraft('providerName', 'green'))
    pause()

    expect(probe.location.search).toBe('?medication=ivig&provider_name=green')
  })

  it('a box typed in at the moment another one is applied keeps what was typed', () => {
    const { result, probe } = setup()
    act(() => result.current.setDraft('medication', 'ivig'))

    // The pause ends and, in the same render, a letter arrives in another box.
    act(() => {
      vi.advanceTimersByTime(DEBOUNCE_MS)
      result.current.setDraft('providerName', 'gre')
    })

    expect(probe.location.search).toBe('?medication=ivig') // the first box is applied
    expect(result.current.liveFilters).toMatchObject({ medication: 'ivig', providerName: 'gre' }) // the second is kept

    pause()

    expect(probe.location.search).toBe('?medication=ivig&provider_name=gre')
  })
})

describe('useOrderSearch: patient name and MRN stay out of the URL', () => {
  it.each([[false], [true]])('they reach the request, not the address bar (shared memory: %s)', (provider) => {
    const { result, probe } = setup('/orders', { provider })

    act(() => result.current.setDraft('patientName', 'Jane Doe'))
    act(() => result.current.setDraft('mrn', '123456'))
    pause()

    expect(params(result)).toEqual({ page: 1, page_size: 20, patient_name: 'Jane Doe', mrn: '123456' })
    expect(probe.location.search).toBe('')
    expect(probe.location.pathname + probe.location.search + probe.location.hash).not.toMatch(/Jane|123456/)
  })

  it('they are not read from the URL either', () => {
    const { result } = setup('/orders?patient_name=Jane&mrn=123456')

    expect(params(result)).toEqual({ page: 1, page_size: 20 })
    expect(result.current.liveFilters).toMatchObject({ patientName: '', mrn: '' })
  })

  it('they stay in the memory when the URL changes (a page, a status)', () => {
    const { result } = setup('/orders', { provider: true })
    act(() => result.current.setDraft('patientName', 'Jane'))
    pause()

    act(() => result.current.setPage(2))
    act(() => result.current.setStatus('failed'))

    expect(params(result)).toEqual({ page: 1, page_size: 20, status: 'failed', patient_name: 'Jane' })
  })

  it('the shared memory has them, and emptying it empties the box and the request', () => {
    const { result, probe } = setup('/orders', { provider: true })
    act(() => result.current.setDraft('patientName', 'Jane'))
    act(() => result.current.setDraft('mrn', '123456'))
    pause()
    expect(probe.memory?.memory).toEqual({ patientName: 'Jane', mrn: '123456' })

    act(() => probe.memory?.clearMemory())

    expect(result.current.liveFilters).toMatchObject({ patientName: '', mrn: '' })
    expect(params(result)).toEqual({ page: 1, page_size: 20 })
  })

  it('clear empties them too', () => {
    const { result, probe } = setup('/orders', { provider: true })
    act(() => result.current.setDraft('patientName', 'Jane'))
    pause()

    act(() => result.current.clear())

    expect(probe.memory?.memory).toEqual({ patientName: '', mrn: '' })
    expect(params(result)).toEqual({ page: 1, page_size: 20 })
  })
})

describe('useOrderSearch: name and page change in one step', () => {
  it.each([[false], [true]])(
    'on page 3, a new name is never requested together with page 3 (shared memory: %s)',
    (provider) => {
      // Every render builds a request; the list asks the server for each of them. Collect them all.
      const { Wrapper } = routerHarness('/orders?page=3', { provider })
      const requested: OrderListParams[] = []
      const { result } = renderHook(() => {
        const search = useOrderSearch()
        if (search.build.ok) requested.push(search.build.params)
        return search
      }, { wrapper: Wrapper })
      expect(requested.at(-1)).toMatchObject({ page: 3 })

      act(() => result.current.setDraft('patientName', 'Jane'))
      pause()

      expect(requested.at(-1)).toEqual({ page: 1, page_size: 20, patient_name: 'Jane' })
      expect(requested.filter((request) => request.patient_name !== undefined && request.page === 3)).toEqual([])
      expect(requested.filter((request) => request.page === 1 && request.patient_name === undefined)).toEqual([])
    },
  )

  it('the same for the MRN', () => {
    const { Wrapper } = routerHarness('/orders?page=3&status=failed', { provider: true })
    const requested: OrderListParams[] = []
    const { result } = renderHook(() => {
      const search = useOrderSearch()
      if (search.build.ok) requested.push(search.build.params)
      return search
    }, { wrapper: Wrapper })

    act(() => result.current.setDraft('mrn', '123456'))
    pause()

    expect(requested.filter((request) => request.mrn !== undefined).map((request) => request.page)).toEqual([1])
  })
})

describe('useOrderSearch: what is not allowed in the URL never comes back out of it', () => {
  const JUNK = '/orders?patient_name=Jane&mrn=123456&sort=password&foo=bar'

  it.each([
    ['a status', (s: ReturnType<typeof setup>['result']) => s.current.setStatus('failed'), '?status=failed'],
    ['a date', (s: ReturnType<typeof setup>['result']) => s.current.setDate('createdTo', '2026-11-30'), '?created_to=2026-11-30'],
    ['a sort', (s: ReturnType<typeof setup>['result']) => s.current.toggleSort('status'), '?sort=status%2Casc'],
    ['a page', (s: ReturnType<typeof setup>['result']) => s.current.setPage(2), '?page=2'],
    ['clear', (s: ReturnType<typeof setup>['result']) => s.current.clear(), ''],
  ])('after %s, the address has only what the user chose', (_name, act1, expected) => {
    const { result, probe } = setup(JUNK)
    expect(params(result)).toEqual({ page: 1, page_size: 20 }) // the junk is not used

    act(() => act1(result))

    expect(probe.location.search).toBe(expected)
  })

  it('after typing a patient name (which is not in the URL), the junk is cleaned out as well', () => {
    const { result, probe } = setup(JUNK)

    act(() => result.current.setDraft('patientName', 'Bob'))
    pause()

    expect(probe.location.search).toBe('')
    expect(params(result)).toEqual({ page: 1, page_size: 20, patient_name: 'Bob' }) // Bob, not the Jane of the URL
  })
})
