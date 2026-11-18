import { describe, expect, it } from 'vitest'
import { EMPTY_FILTERS, INITIAL_SEARCH, type OrderFilters, type OrderSearchState } from '@/utils/orderSearch'
import { ordersListPath, parseUrlSearch, toUrlParams } from '@/utils/orderSearchUrl'

const parse = (query: string) => parseUrlSearch(new URLSearchParams(query))

function state(
  overrides: Partial<Omit<OrderSearchState, 'filters'>> & { filters?: Partial<OrderFilters> } = {},
): OrderSearchState {
  return {
    ...INITIAL_SEARCH,
    ...overrides,
    filters: { ...EMPTY_FILTERS, ...overrides.filters },
  }
}

describe('parseUrlSearch: reading the address bar', () => {
  it('an empty query is the default search: all statuses, no texts, no dates, no sort, page 1', () => {
    expect(parse('')).toEqual({
      filters: { status: 'all', medication: '', providerName: '', createdFrom: '', createdTo: '' },
      sort: null,
      page: 1,
    })
  })

  it('reads every key it knows', () => {
    const result = parse(
      'status=failed&created_from=2026-11-01&created_to=2026-11-30&medication=IVIG&provider_name=Dr.%20Green'
      + '&sort=created_at%2Cdesc&page=3',
    )

    expect(result).toEqual({
      filters: {
        status: 'failed', createdFrom: '2026-11-01', createdTo: '2026-11-30',
        medication: 'IVIG', providerName: 'Dr. Green',
      },
      sort: { column: 'created_at', direction: 'desc' },
      page: 3,
    })
  })

  it('also reads the sort with a literal comma', () => {
    expect(parse('sort=patient_name,asc').sort).toEqual({ column: 'patient_name', direction: 'asc' })
  })

  it('keeps a text as written, spaces included (a space between two words must survive)', () => {
    expect(parse('medication=%20immune%20globulin%20').filters.medication).toBe(' immune globulin ')
  })

  it('takes the first value of a repeated key', () => {
    const result = parse('status=failed&status=pending&page=2&page=5&medication=a&medication=b')

    expect(result.filters.status).toBe('failed')
    expect(result.page).toBe(2)
    expect(result.filters.medication).toBe('a')
  })
})

describe('parseUrlSearch: only the whitelisted keys count', () => {
  it('ignores the patient name, the MRN and any unknown key', () => {
    const result = parse('patient_name=Jane%20Doe&mrn=123456&patientName=Jane&foo=bar&status=failed')

    expect(result).toEqual({
      filters: { status: 'failed', medication: '', providerName: '', createdFrom: '', createdTo: '' },
      sort: null,
      page: 1,
    })
    expect(JSON.stringify(result)).not.toContain('Jane')
    expect(JSON.stringify(result)).not.toContain('123456')
  })

  it.each([
    ['an unknown status', 'status=archived'],
    ['a status in another case', 'status=FAILED'],
    ['an empty status', 'status='],
  ])('%s is "all"', (_name, query) => {
    expect(parse(query).filters.status).toBe('all')
  })

  it.each([
    ['0', 'page=0'], ['negative', 'page=-1'], ['text', 'page=abc'], ['decimal', 'page=1.5'],
    ['leading zero', 'page=007'], ['too long', 'page=12345678901234567890'], ['empty', 'page='], ['space', 'page=%202'],
  ])('a page that is %s is page 1', (_name, query) => {
    expect(parse(query).page).toBe(1)
  })

  it.each([['1', 1], ['2', 2], ['999999999', 999999999]])('page=%s is page %i', (value, page) => {
    expect(parse(`page=${value}`).page).toBe(page)
  })

  it.each([
    ['a column that cannot be sorted', 'sort=password'],
    ['the id', 'sort=id,asc'],
    ['no direction', 'sort=created_at'],
    ['an unknown direction', 'sort=created_at,up'],
    ['upper case', 'sort=CREATED_AT,DESC'],
    ['an extra part', 'sort=created_at,desc,extra'],
    ['an empty value', 'sort='],
    ['a second sort', 'sort=created_at,desc;password'],
  ])('%s is no sort at all', (_name, query) => {
    expect(parse(query).sort).toBeNull()
  })

  it.each([
    ['not a day', 'created_from=2026-02-30'],
    ['wrong shape', 'created_from=20261101'],
    ['text', 'created_from=abc'],
    ['before 1900', 'created_from=1899-12-31'],
    ['after 2999', 'created_from=3000-01-01'],
    ['markup', 'created_from=%3Cscript%3E'],
  ])('a date that is %s is no date', (_name, query) => {
    expect(parse(query).filters.createdFrom).toBe('')
    expect(parse(query.replace('created_from', 'created_to')).filters.createdTo).toBe('')
  })

  it('the limits of the date range are dates', () => {
    expect(parse('created_from=1900-01-01&created_to=2999-12-31').filters)
      .toMatchObject({ createdFrom: '1900-01-01', createdTo: '2999-12-31' })
  })
})

describe('toUrlParams: writing the address bar', () => {
  it('the default search is an empty query (a bare /orders)', () => {
    expect(toUrlParams(INITIAL_SEARCH).toString()).toBe('')
  })

  it('writes every part, in a fixed order, without the default values', () => {
    const params = toUrlParams(state({
      filters: {
        status: 'failed', createdFrom: '2026-11-01', createdTo: '2026-11-30',
        medication: 'IVIG', providerName: 'Dr. Green',
      },
      sort: { column: 'created_at', direction: 'desc' },
      page: 2,
    }))

    expect(params.toString()).toBe(
      'status=failed&created_from=2026-11-01&created_to=2026-11-30&medication=IVIG&provider_name=Dr.+Green'
      + '&sort=created_at%2Cdesc&page=2',
    )
  })

  it('leaves out the defaults one by one: status all, no sort, page 1', () => {
    expect(toUrlParams(state({ page: 4 })).toString()).toBe('page=4')
    expect(toUrlParams(state({ sort: { column: 'status', direction: 'asc' } })).toString()).toBe('sort=status%2Casc')
    expect(toUrlParams(state({ filters: { status: 'pending' } })).toString()).toBe('status=pending')
  })

  it('never writes the patient name or the MRN', () => {
    const params = toUrlParams(state({
      filters: { patientName: 'Jane Doe', mrn: '123456', medication: 'IVIG' },
    }))

    expect(params.toString()).toBe('medication=IVIG')
    expect(params.has('patient_name')).toBe(false)
    expect(params.has('mrn')).toBe(false)
  })

  it('a blank text is not written, but a text keeps its spaces', () => {
    expect(toUrlParams(state({ filters: { medication: '   ', providerName: '' } })).toString()).toBe('')
    expect(parse(toUrlParams(state({ filters: { medication: 'ivig ' } })).toString()).filters.medication)
      .toBe('ivig ')
  })

  it('what is written is read back as the same search (for everything the URL can hold)', () => {
    const original = state({
      filters: {
        status: 'completed', createdFrom: '2026-01-02', createdTo: '2026-03-04',
        medication: 'Ä & ö = 100%', providerName: 'Dr. O\'Neil, MD',
      },
      sort: { column: 'provider_name', direction: 'desc' },
      page: 17,
    })

    const roundTrip = parseUrlSearch(toUrlParams(original))

    expect(roundTrip).toEqual({
      filters: {
        status: 'completed', createdFrom: '2026-01-02', createdTo: '2026-03-04',
        medication: 'Ä & ö = 100%', providerName: 'Dr. O\'Neil, MD',
      },
      sort: { column: 'provider_name', direction: 'desc' },
      page: 17,
    })
  })

  it('is built from the state, so a key that was in the old URL and does not belong there is gone', () => {
    const fromUrl = parse('patient_name=Jane&mrn=123456&sort=password&foo=bar&status=failed')

    const written = toUrlParams({ filters: { ...fromUrl.filters, patientName: '', mrn: '' }, sort: fromUrl.sort, page: fromUrl.page })

    expect(written.toString()).toBe('status=failed')
  })
})

describe('ordersListPath: the link back to the list', () => {
  it.each([
    ['no state (the order was opened directly)', null],
    ['undefined', undefined],
    ['a string', 'status=failed'],
    ['a number', 7],
    ['an object without the query', {}],
    ['a query that is not a string', { ordersSearch: 42 }],
    ['an empty query', { ordersSearch: '' }],
    ['a query with nothing the list knows', { ordersSearch: '?foo=bar' }],
  ])('%s goes to the bare /orders', (_name, state) => {
    expect(ordersListPath(state)).toBe('/orders')
  })

  it('goes back to the list the order was opened from', () => {
    expect(ordersListPath({ ordersSearch: '?status=failed&sort=created_at%2Cdesc&page=2' }))
      .toBe('/orders?status=failed&sort=created_at%2Cdesc&page=2')
  })

  it('works with or without the leading "?"', () => {
    expect(ordersListPath({ ordersSearch: 'status=failed' })).toBe('/orders?status=failed')
  })

  it('reads the saved query through the whitelist: patient fields, unknown keys and invalid values are dropped', () => {
    const path = ordersListPath({
      ordersSearch: '?patient_name=Jane&mrn=123456&foo=bar&sort=password&page=0&status=pending&created_from=nope',
    })

    expect(path).toBe('/orders?status=pending')
  })
})
