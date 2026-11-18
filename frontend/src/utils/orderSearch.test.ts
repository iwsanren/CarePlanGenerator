import { describe, expect, it } from 'vitest'
import { makeApiError } from '@/test/fixtures'
import {
  activeFilterCount,
  clearFilters,
  describeListError,
  describeSort,
  EARLIEST_DATE,
  EMPTY_FILTERS,
  hasActiveFilters,
  INITIAL_SEARCH,
  isAllowedDate,
  LATEST_DATE,
  MAX_TEXT_LENGTH,
  nextSort,
  PAGE_SIZE,
  resultsLabel,
  SORT_COLUMN_LABELS,
  SORT_COLUMNS,
  sortToParam,
  STATUS_FILTER_LABELS,
  STATUS_FILTERS,
  toRequestParams,
  validateFilters,
  withFilters,
  withPage,
  withSort,
  type OrderFilters,
  type OrderSearchState,
  type OrderSort,
} from './orderSearch'

const filters = (changes: Partial<OrderFilters> = {}): OrderFilters => ({ ...EMPTY_FILTERS, ...changes })
const search = (changes: Partial<OrderSearchState> = {}): OrderSearchState => ({ ...INITIAL_SEARCH, ...changes })

describe('constants', () => {
  it('the page size is within what the backend accepts (1..100)', () => {
    expect(PAGE_SIZE).toBeGreaterThanOrEqual(1)
    expect(PAGE_SIZE).toBeLessThanOrEqual(100)
  })

  it('offers the five sortable columns and the status buttons in the agreed order', () => {
    expect(SORT_COLUMNS).toEqual(['patient_name', 'provider_name', 'medication', 'status', 'created_at'])
    expect(STATUS_FILTERS).toEqual(['all', 'completed', 'pending', 'processing', 'failed'])
  })

  it('starts with no filter, the default order and page 1', () => {
    expect(INITIAL_SEARCH).toEqual({ filters: EMPTY_FILTERS, sort: null, page: 1 })
    expect(EMPTY_FILTERS.status).toBe('all')
  })
})

describe('words shown to the user', () => {
  it('has a label for every status button and every sortable column', () => {
    expect(STATUS_FILTERS.map((status) => STATUS_FILTER_LABELS[status]))
      .toEqual(['All', 'Completed', 'Pending', 'Processing', 'Failed'])
    expect(SORT_COLUMNS.map((column) => SORT_COLUMN_LABELS[column]))
      .toEqual(['Patient', 'Provider', 'Medication', 'Status', 'Created'])
  })

  it('describeSort says how the list is ordered, in words', () => {
    expect(describeSort(null)).toBe('Sorted by: newest first (default)')
    expect(describeSort({ column: 'patient_name', direction: 'asc' })).toBe('Sorted by: Patient (ascending)')
    expect(describeSort({ column: 'created_at', direction: 'desc' })).toBe('Sorted by: Created (descending)')
  })

  it('resultsLabel uses the singular for exactly one', () => {
    expect(resultsLabel(0)).toBe('0 results')
    expect(resultsLabel(1)).toBe('1 result')
    expect(resultsLabel(2)).toBe('2 results')
    expect(resultsLabel(45)).toBe('45 results')
  })
})

describe('nextSort: the header click cycle', () => {
  it('no sort -> ascending on the clicked column', () => {
    expect(nextSort(null, 'medication')).toEqual({ column: 'medication', direction: 'asc' })
  })

  it('ascending -> descending -> back to the default order (null)', () => {
    const ascending: OrderSort = { column: 'status', direction: 'asc' }
    const descending = nextSort(ascending, 'status')

    expect(descending).toEqual({ column: 'status', direction: 'desc' })
    expect(nextSort(descending, 'status')).toBeNull()
  })

  it.each(SORT_COLUMNS)('%s goes through the whole cycle and back to the start', (column) => {
    const first = nextSort(null, column)
    const second = nextSort(first, column)
    const third = nextSort(second, column)

    expect([first?.direction, second?.direction, third]).toEqual(['asc', 'desc', null])
  })

  it('a different column starts again at ascending, whatever the old direction was', () => {
    expect(nextSort({ column: 'status', direction: 'desc' }, 'patient_name'))
      .toEqual({ column: 'patient_name', direction: 'asc' })
    expect(nextSort({ column: 'status', direction: 'asc' }, 'created_at'))
      .toEqual({ column: 'created_at', direction: 'asc' })
  })
})

describe('sortToParam', () => {
  it('is "column,direction", the single string the backend reads', () => {
    expect(sortToParam({ column: 'created_at', direction: 'desc' })).toBe('created_at,desc')
    expect(sortToParam({ column: 'patient_name', direction: 'asc' })).toBe('patient_name,asc')
  })
})

describe('state changes: filters and sort go back to page 1, a page change does not', () => {
  const onPageThree = search({ page: 3, sort: { column: 'status', direction: 'asc' } })

  it('withFilters merges the changes and resets the page', () => {
    const next = withFilters(search({ page: 3, filters: filters({ medication: 'ivig' }) }), { status: 'failed' })

    expect(next.page).toBe(1)
    expect(next.filters).toEqual(filters({ medication: 'ivig', status: 'failed' }))
  })

  it('withFilters keeps the sort', () => {
    expect(withFilters(onPageThree, { medication: 'x' }).sort).toEqual({ column: 'status', direction: 'asc' })
  })

  it('withSort cycles the sort and resets the page', () => {
    const next = withSort(onPageThree, 'status')

    expect(next.sort).toEqual({ column: 'status', direction: 'desc' })
    expect(next.page).toBe(1)
    expect(withSort(search({ page: 4 }), 'created_at').sort).toEqual({ column: 'created_at', direction: 'asc' })
  })

  it('withSort keeps the filters', () => {
    const state = search({ page: 2, filters: filters({ status: 'failed' }) })

    expect(withSort(state, 'medication').filters).toEqual(filters({ status: 'failed' }))
  })

  it('withPage changes only the page', () => {
    const state = search({ page: 2, filters: filters({ status: 'failed' }), sort: { column: 'status', direction: 'asc' } })

    expect(withPage(state, 3)).toEqual({ ...state, page: 3 })
  })

  it('clearFilters empties every filter, goes to page 1 and KEEPS the sort', () => {
    const state = search({
      page: 5,
      filters: filters({ status: 'failed', patientName: 'a', mrn: '123456', createdFrom: '2026-11-01' }),
      sort: { column: 'medication', direction: 'desc' },
    })

    expect(clearFilters(state)).toEqual({ filters: EMPTY_FILTERS, sort: { column: 'medication', direction: 'desc' }, page: 1 })
  })

  it('none of them changes the object it was given', () => {
    const before = JSON.stringify(onPageThree)
    withFilters(onPageThree, { medication: 'x' })
    withSort(onPageThree, 'medication')
    withPage(onPageThree, 9)
    clearFilters(onPageThree)

    expect(JSON.stringify(onPageThree)).toBe(before)
  })
})

describe('activeFilterCount / hasActiveFilters', () => {
  it('no filter set -> 0 and false', () => {
    expect(activeFilterCount(EMPTY_FILTERS)).toBe(0)
    expect(hasActiveFilters(EMPTY_FILTERS)).toBe(false)
  })

  it('a status other than "all" counts as one', () => {
    expect(activeFilterCount(filters({ status: 'failed' }))).toBe(1)
    expect(hasActiveFilters(filters({ status: 'completed' }))).toBe(true)
  })

  it.each<[keyof OrderFilters, string]>([
    ['patientName', 'jane'], ['mrn', '123456'], ['medication', 'ivig'], ['providerName', 'green'],
    ['createdFrom', '2026-11-01'], ['createdTo', '2026-11-30'],
  ])('a %s counts as one', (field, value) => {
    expect(activeFilterCount(filters({ [field]: value }))).toBe(1)
  })

  it('texts that are only spaces do not count', () => {
    expect(activeFilterCount(filters({ patientName: '   ', medication: '\t', createdFrom: ' ' }))).toBe(0)
  })

  it('counts every filter that is set', () => {
    const all = filters({
      status: 'pending', patientName: 'a', mrn: '123456', medication: 'b', providerName: 'c',
      createdFrom: '2026-11-01', createdTo: '2026-11-02',
    })

    expect(activeFilterCount(all)).toBe(7)
  })
})

describe('isAllowedDate', () => {
  it.each(['2026-11-15', '1900-01-01', '2999-12-31', '2024-02-29'])('%s is allowed', (value) => {
    expect(isAllowedDate(value)).toBe(true)
  })

  it.each([
    ['not a date', 'yesterday'],
    ['empty', ''],
    ['wrong order', '15-11-2026'],
    ['slashes', '2026/11/15'],
    ['no leading zero', '2026-1-5'],
    ['with a time', '2026-11-15T10:00'],
    ['month 13', '2026-13-01'],
    ['day 00', '2026-11-00'],
    ['day 31 of a 30-day month', '2026-11-31'],
    ['29 February in a common year', '2026-02-29'],
    ['the day before the earliest', '1899-12-31'],
    ['the day after the latest', '3000-01-01'],
    ['year 0000', '0000-01-01'],
  ])('%s (%s) is refused', (_label, value) => {
    expect(isAllowedDate(value)).toBe(false)
  })

  it('uses the same range the backend does', () => {
    expect([EARLIEST_DATE, LATEST_DATE]).toEqual(['1900-01-01', '2999-12-31'])
  })
})

describe('validateFilters', () => {
  it('nothing set -> no errors', () => {
    expect(validateFilters(EMPTY_FILTERS)).toEqual({})
  })

  it('a fully valid set -> no errors', () => {
    const valid = filters({
      status: 'failed', patientName: 'Jane Doe', mrn: '123456', medication: 'IVIG', providerName: 'Dr. Green',
      createdFrom: '2026-11-01', createdTo: '2026-11-30',
    })

    expect(validateFilters(valid)).toEqual({})
  })

  describe('MRN', () => {
    it.each(['12345', '1234567', 'abcdef', '12 456', '12345a', '-12345'])('"%s" is not 6 digits', (mrn) => {
      expect(validateFilters(filters({ mrn }))).toEqual({ mrn: 'MRN must be exactly 6 digits' })
    })

    it('exactly 6 digits are fine, spaces around them are ignored, blank means no filter', () => {
      expect(validateFilters(filters({ mrn: '123456' }))).toEqual({})
      expect(validateFilters(filters({ mrn: ' 123456 ' }))).toEqual({})
      expect(validateFilters(filters({ mrn: '   ' }))).toEqual({})
    })
  })

  describe('text length', () => {
    const exactly = 'a'.repeat(MAX_TEXT_LENGTH)
    const tooLong = 'a'.repeat(MAX_TEXT_LENGTH + 1)

    it('100 characters are accepted', () => {
      expect(validateFilters(filters({ patientName: exactly, medication: exactly, providerName: exactly }))).toEqual({})
    })

    it('101 characters are refused for each of the three texts, with the field named in the message', () => {
      expect(validateFilters(filters({ patientName: tooLong })))
        .toEqual({ patientName: 'Patient name must be at most 100 characters' })
      expect(validateFilters(filters({ medication: tooLong })))
        .toEqual({ medication: 'Medication must be at most 100 characters' })
      expect(validateFilters(filters({ providerName: tooLong })))
        .toEqual({ providerName: 'Provider name must be at most 100 characters' })
    })

    it('spaces around the text are not counted (the text is trimmed before it is sent)', () => {
      expect(validateFilters(filters({ patientName: `  ${exactly}  ` }))).toEqual({})
    })
  })

  describe('dates', () => {
    it('one end alone, or the same day on both ends, is fine', () => {
      expect(validateFilters(filters({ createdFrom: '2026-11-15' }))).toEqual({})
      expect(validateFilters(filters({ createdTo: '2026-11-15' }))).toEqual({})
      expect(validateFilters(filters({ createdFrom: '2026-11-15', createdTo: '2026-11-15' }))).toEqual({})
    })

    it('the start after the end is an error on the end field', () => {
      expect(validateFilters(filters({ createdFrom: '2026-11-16', createdTo: '2026-11-15' })))
        .toEqual({ createdTo: 'The end date must not be before the start date' })
    })

    it('an impossible date is an error on its own field', () => {
      const message = 'Enter a valid date between 1900-01-01 and 2999-12-31'

      expect(validateFilters(filters({ createdFrom: '2026-02-30' }))).toEqual({ createdFrom: message })
      expect(validateFilters(filters({ createdTo: '3000-01-01' }))).toEqual({ createdTo: message })
    })

    it('two impossible dates give two errors, and no "start after end" on top of them', () => {
      const message = 'Enter a valid date between 1900-01-01 and 2999-12-31'

      expect(validateFilters(filters({ createdFrom: 'x', createdTo: 'y' })))
        .toEqual({ createdFrom: message, createdTo: message })
    })

    it('one good and one bad date: only the bad one is reported, the order is not compared', () => {
      expect(validateFilters(filters({ createdFrom: '2026-11-20', createdTo: 'nope' })))
        .toEqual({ createdTo: 'Enter a valid date between 1900-01-01 and 2999-12-31' })
      expect(validateFilters(filters({ createdFrom: 'nope', createdTo: '2026-11-20' })))
        .toEqual({ createdFrom: 'Enter a valid date between 1900-01-01 and 2999-12-31' })
    })
  })

  it('reports every wrong field at once', () => {
    const errors = validateFilters(filters({
      mrn: '1', patientName: 'a'.repeat(101), createdFrom: '2026-11-16', createdTo: '2026-11-15',
    }))

    expect(Object.keys(errors).sort()).toEqual(['createdTo', 'mrn', 'patientName'])
  })
})

describe('toRequestParams', () => {
  const build = (state: OrderSearchState) => {
    const result = toRequestParams(state)
    if (!result.ok) throw new Error('expected parameters, got errors')
    return result.params
  }

  it('the initial state sends the page and the page size, and nothing else', () => {
    expect(build(INITIAL_SEARCH)).toEqual({ page: 1, page_size: 20 })
  })

  it('carries the page', () => {
    expect(build(search({ page: 3 }))).toEqual({ page: 3, page_size: 20 })
  })

  it('status "all" sends no status; any other status is sent as it is', () => {
    expect(build(search({ filters: filters({ status: 'all' }) }))).not.toHaveProperty('status')
    expect(build(search({ filters: filters({ status: 'failed' }) }))).toMatchObject({ status: 'failed' })
    expect(build(search({ filters: filters({ status: 'pending' }) }))).toMatchObject({ status: 'pending' })
  })

  it.each<[string, Partial<OrderFilters>, Record<string, string>]>([
    ['patient name', { patientName: 'Jane' }, { patient_name: 'Jane' }],
    ['MRN', { mrn: '123456' }, { mrn: '123456' }],
    ['medication', { medication: 'IVIG' }, { medication: 'IVIG' }],
    ['provider name', { providerName: 'Dr. Green' }, { provider_name: 'Dr. Green' }],
    ['start date', { createdFrom: '2026-11-01' }, { created_from: '2026-11-01' }],
    ['end date', { createdTo: '2026-11-30' }, { created_to: '2026-11-30' }],
  ])('%s alone is sent under its backend name', (_label, changes, expected) => {
    expect(build(search({ filters: filters(changes) }))).toEqual({ page: 1, page_size: 20, ...expected })
  })

  it('an empty or blank text is not sent at all (not even as an empty string)', () => {
    const params = build(search({
      filters: filters({ patientName: '', mrn: '  ', medication: '\t', providerName: ' ', createdFrom: '', createdTo: '' }),
    }))

    expect(params).toEqual({ page: 1, page_size: 20 })
    expect(Object.values(params)).not.toContain('')
  })

  it('trims the texts before sending them', () => {
    expect(build(search({ filters: filters({ patientName: '  Jane Doe ', mrn: ' 123456 ' }) })))
      .toEqual({ page: 1, page_size: 20, patient_name: 'Jane Doe', mrn: '123456' })
  })

  it('sends the sort as ONE string, never as an array', () => {
    const params = build(search({ sort: { column: 'patient_name', direction: 'desc' } }))

    expect(params.sort).toBe('patient_name,desc')
    expect(Array.isArray(params.sort)).toBe(false)
  })

  it('no sort sends no sort parameter (the backend then lists the newest first)', () => {
    expect(build(search({ sort: null }))).not.toHaveProperty('sort')
  })

  it('every filter, the sort and the page together', () => {
    const state = search({
      page: 2,
      sort: { column: 'created_at', direction: 'desc' },
      filters: {
        status: 'completed', patientName: 'Wong', mrn: '100001', medication: 'ivig', providerName: 'green',
        createdFrom: '2026-11-01', createdTo: '2026-11-30',
      },
    })

    expect(build(state)).toEqual({
      page: 2, page_size: 20, status: 'completed', patient_name: 'Wong', mrn: '100001', medication: 'ivig',
      provider_name: 'green', created_from: '2026-11-01', created_to: '2026-11-30', sort: 'created_at,desc',
    })
  })

  it('invalid filters give the errors and NO parameters (so nothing is sent)', () => {
    const result = toRequestParams(search({ filters: filters({ mrn: '12', medication: 'ivig' }) }))

    expect(result).toEqual({ ok: false, errors: { mrn: 'MRN must be exactly 6 digits' } })
    expect(result).not.toHaveProperty('params')
  })

  it('a start date after the end date is not sent', () => {
    const result = toRequestParams(search({ filters: filters({ createdFrom: '2026-11-16', createdTo: '2026-11-15' }) }))

    expect(result.ok).toBe(false)
  })

  it('never leaves a key that the backend does not know', () => {
    const known = ['page', 'page_size', 'status', 'patient_name', 'mrn', 'medication', 'provider_name',
      'created_from', 'created_to', 'sort']
    const params = build(search({
      sort: { column: 'status', direction: 'asc' },
      filters: filters({
        status: 'failed', patientName: 'a', mrn: '123456', medication: 'b', providerName: 'c',
        createdFrom: '2026-11-01', createdTo: '2026-11-02',
      }),
    }))

    expect(Object.keys(params).every((key) => known.includes(key))).toBe(true)
  })
})

describe('describeListError', () => {
  it.each<[string, string]>([
    ['INVALID_SORT', 'This sort is not supported. Choose another column, or click the header again to reset the sort.'],
    ['INVALID_DATE_RANGE', 'The start date must not be after the end date.'],
    ['INVALID_DATE', 'Dates must be between 1900-01-01 and 2999-12-31.'],
    ['INVALID_PAGE_SIZE', 'This page size is not supported.'],
    ['INVALID_PAGE', 'This page does not exist. Go to the first page.'],
    ['INVALID_SEARCH_TEXT', 'Search texts must be at most 100 characters.'],
    ['INVALID_STATUS', 'This status is not supported.'],
  ])('%s -> a readable sentence', (code, text) => {
    expect(describeListError(makeApiError('raw backend text', { code, status: 400 }))).toBe(text)
  })

  it('INVALID_REQUEST_PARAM names the parameter when the detail has one', () => {
    const error = makeApiError('Request parameter validation failed', {
      code: 'INVALID_REQUEST_PARAM', detail: { created_from: 'has an invalid value' },
    })

    expect(describeListError(error)).toBe('A filter value is not valid (created_from).')
  })

  it.each<[string, unknown]>([
    ['no detail', undefined],
    ['a null detail', null],
    ['an empty detail', {}],
    ['a detail that is not an object', 'text'],
  ])('INVALID_REQUEST_PARAM with %s is still readable', (_label, detail) => {
    expect(describeListError(makeApiError('x', { code: 'INVALID_REQUEST_PARAM', detail })))
      .toBe('A filter value is not valid.')
  })

  it('any other failure keeps the message the list showed before', () => {
    expect(describeListError(makeApiError('Server down', { status: 500 }))).toBe('Error loading orders: Server down')
    expect(describeListError(makeApiError('Nope', { code: 'SOMETHING_ELSE' }))).toBe('Error loading orders: Nope')
  })

  it('tolerates things that are not API errors', () => {
    expect(describeListError(new Error('Network Error'))).toBe('Error loading orders: Network Error')
    expect(describeListError(undefined)).toBe('Error loading orders: ')
    expect(describeListError(null)).toBe('Error loading orders: ')
    expect(describeListError('boom')).toBe('Error loading orders: ')
    expect(describeListError(42)).toBe('Error loading orders: ')
  })

  it('does not repeat the backend message for the known codes (it is replaced, not appended)', () => {
    const text = describeListError(makeApiError('internal wording', { code: 'INVALID_SORT' }))

    expect(text).not.toContain('internal wording')
  })
})
