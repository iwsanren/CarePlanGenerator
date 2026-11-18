import { beforeEach, describe, expect, it, vi } from 'vitest'
import { fireEvent, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { orderService } from '@/services/orderService'
import { downloadFile } from '@/services/download'
import { renderPage } from '@/test/utils'
import { makeApiError, makeListItem, makePage } from '@/test/fixtures'
import { OrdersPage } from './OrdersPage'

// Same boundaries as OrdersPage.test.tsx: auth is a stub, orderService and download are the HTTP boundary, and
// everything else (the page, its components, useOrderSearch, useOrders, react-query) runs for real.
// The text boxes wait 300 ms before they change the search, so tests that type use waitFor.
const auth = vi.hoisted(() => ({ state: { pharmacist: false }, useAuth: vi.fn() }))
vi.mock('@/auth/auth', () => ({ useAuth: auth.useAuth }))
vi.mock('@/services/orderService', () => ({ orderService: { getOrders: vi.fn() } }))
vi.mock('@/services/download', () => ({ downloadFile: vi.fn() }))

const getOrders = vi.mocked(orderService.getOrders)

function renderOrders() {
  return renderPage(<OrdersPage />, { route: '/orders', path: '/orders' })
}

/** The parameters of the most recent request. */
const lastParams = () => getOrders.mock.lastCall?.[0]

/** Wait until the page has made its request with exactly these parameters. */
async function expectRequest(params: object) {
  await waitFor(() => expect(lastParams()).toEqual(params))
}

const BASE = { page: 1, page_size: 20 }

/** One order on screen, so the table and the panel are there. */
async function renderWithOrder() {
  getOrders.mockResolvedValue(makePage([makeListItem({ patientName: 'Jane Doe' })]))
  const view = renderOrders()
  await screen.findByRole('table')
  return view
}

beforeEach(() => {
  vi.resetAllMocks()
  auth.state = { pharmacist: false }
  auth.useAuth.mockImplementation(() => auth.state)
})

describe('OrdersPage search: the text boxes', () => {
  it.each([
    ['Patient name', 'patient_name', 'Jane'],
    ['MRN', 'mrn', '123456'],
    ['Medication', 'medication', 'IVIG'],
    ['Provider name', 'provider_name', 'Dr. Green'],
  ])('"%s" is sent as %s', async (label, parameter, text) => {
    await renderWithOrder()

    await userEvent.type(screen.getByLabelText(label), text)

    await expectRequest({ ...BASE, [parameter]: text })
  })

  it('nothing is sent while someone is typing, and one request is sent when they pause', async () => {
    await renderWithOrder()
    expect(getOrders).toHaveBeenCalledTimes(1) // the first load

    await userEvent.type(screen.getByLabelText('Patient name'), 'Jane Doe')
    expect(getOrders).toHaveBeenCalledTimes(1) // eight keystrokes, still no new request

    await waitFor(() => expect(getOrders).toHaveBeenCalledTimes(2))
    await new Promise((resolve) => setTimeout(resolve, 400)) // and no further one comes
    expect(getOrders).toHaveBeenCalledTimes(2)
    expect(lastParams()).toEqual({ ...BASE, patient_name: 'Jane Doe' })
  })

  it('emptying a box takes its parameter away again', async () => {
    await renderWithOrder()
    await userEvent.type(screen.getByLabelText('Medication'), 'ivig')
    await expectRequest({ ...BASE, medication: 'ivig' })

    await userEvent.clear(screen.getByLabelText('Medication'))

    await expectRequest(BASE)
  })

  it('a box with only spaces sends nothing', async () => {
    await renderWithOrder()
    await userEvent.type(screen.getByLabelText('Medication'), '   ')
    await new Promise((resolve) => setTimeout(resolve, 400))

    expect(lastParams()).toEqual(BASE)
  })

  it('the box shows what is typed at once, before the search changes', async () => {
    await renderWithOrder()

    await userEvent.type(screen.getByLabelText('Patient name'), 'Jane')

    expect(screen.getByLabelText('Patient name')).toHaveValue('Jane')
    expect(lastParams()).toEqual(BASE) // the search has not changed yet
  })
})

describe('OrdersPage search: status buttons and dates', () => {
  it('a status button sends that status, marks itself pressed, and "All" takes it away', async () => {
    await renderWithOrder()
    const group = screen.getByRole('group', { name: 'Status' })
    expect(within(group).getByRole('button', { name: 'All' })).toHaveAttribute('aria-pressed', 'true')

    await userEvent.click(within(group).getByRole('button', { name: 'Failed' }))
    await expectRequest({ ...BASE, status: 'failed' })
    expect(within(group).getByRole('button', { name: 'Failed' })).toHaveAttribute('aria-pressed', 'true')
    expect(within(group).getByRole('button', { name: 'All' })).toHaveAttribute('aria-pressed', 'false')

    await userEvent.click(within(group).getByRole('button', { name: 'All' }))
    await expectRequest(BASE)
  })

  it.each([
    ['Completed', 'completed'],
    ['Pending', 'pending'],
    ['Processing', 'processing'],
  ])('"%s" sends status=%s', async (label, status) => {
    await renderWithOrder()

    await userEvent.click(within(screen.getByRole('group', { name: 'Status' })).getByRole('button', { name: label }))

    await expectRequest({ ...BASE, status })
  })

  it('the start and the end date are sent as created_from and created_to', async () => {
    await renderWithOrder()

    fireEvent.change(screen.getByLabelText('Created from (UTC)'), { target: { value: '2026-11-01' } })
    await expectRequest({ ...BASE, created_from: '2026-11-01' })
    fireEvent.change(screen.getByLabelText('Created to (UTC)'), { target: { value: '2026-11-30' } })

    await expectRequest({ ...BASE, created_from: '2026-11-01', created_to: '2026-11-30' })
  })

  it('every filter together gives the full set of parameters', async () => {
    await renderWithOrder()

    await userEvent.type(screen.getByLabelText('Patient name'), 'Wong')
    await userEvent.type(screen.getByLabelText('MRN'), '100001')
    await userEvent.type(screen.getByLabelText('Medication'), 'ivig')
    await userEvent.type(screen.getByLabelText('Provider name'), 'green')
    fireEvent.change(screen.getByLabelText('Created from (UTC)'), { target: { value: '2026-11-01' } })
    fireEvent.change(screen.getByLabelText('Created to (UTC)'), { target: { value: '2026-11-30' } })
    await userEvent.click(within(screen.getByRole('group', { name: 'Status' })).getByRole('button', { name: 'Completed' }))
    await userEvent.click(screen.getByRole('button', { name: 'Created' }))

    await expectRequest({
      ...BASE, status: 'completed', patient_name: 'Wong', mrn: '100001', medication: 'ivig', provider_name: 'green',
      created_from: '2026-11-01', created_to: '2026-11-30', sort: 'created_at,asc',
    })
  })
})

describe('OrdersPage search: a filter that is wrong is not sent', () => {
  it('a start date after the end date: a message by the box, a hint, and no new request', async () => {
    await renderWithOrder()
    fireEvent.change(screen.getByLabelText('Created from (UTC)'), { target: { value: '2026-11-16' } })
    await expectRequest({ ...BASE, created_from: '2026-11-16' })
    const calls = getOrders.mock.calls.length

    fireEvent.change(screen.getByLabelText('Created to (UTC)'), { target: { value: '2026-11-15' } })

    expect(await screen.findByText('The end date must not be before the start date')).toBeInTheDocument()
    expect(screen.getByText('Fix the highlighted filters to update the results.')).toBeInTheDocument()
    expect(getOrders).toHaveBeenCalledTimes(calls) // nothing was sent
  })

  it('correcting the date sends the request again and the message goes away', async () => {
    await renderWithOrder()
    fireEvent.change(screen.getByLabelText('Created from (UTC)'), { target: { value: '2026-11-16' } })
    fireEvent.change(screen.getByLabelText('Created to (UTC)'), { target: { value: '2026-11-15' } })
    await screen.findByText('The end date must not be before the start date')

    fireEvent.change(screen.getByLabelText('Created to (UTC)'), { target: { value: '2026-11-20' } })

    await expectRequest({ ...BASE, created_from: '2026-11-16', created_to: '2026-11-20' })
    expect(screen.queryByText('The end date must not be before the start date')).not.toBeInTheDocument()
    expect(screen.queryByText('Fix the highlighted filters to update the results.')).not.toBeInTheDocument()
  })

  it('a MRN that is not 6 digits: the message by the box (after the pause) and no request', async () => {
    await renderWithOrder()
    const calls = getOrders.mock.calls.length

    await userEvent.type(screen.getByLabelText('MRN'), '12345')

    expect(await screen.findByText('MRN must be exactly 6 digits')).toBeInTheDocument()
    expect(screen.getByLabelText('MRN')).toHaveAttribute('aria-invalid', 'true')
    expect(getOrders).toHaveBeenCalledTimes(calls)
  })

  it('a 6-digit MRN is sent', async () => {
    await renderWithOrder()
    await userEvent.type(screen.getByLabelText('MRN'), '12345')
    await screen.findByText('MRN must be exactly 6 digits')

    await userEvent.type(screen.getByLabelText('MRN'), '6')

    await expectRequest({ ...BASE, mrn: '123456' })
    expect(screen.queryByText('MRN must be exactly 6 digits')).not.toBeInTheDocument()
  })

  it('the last good result stays on screen (dimmed) while the filters are wrong', async () => {
    const { container } = await renderWithOrder()

    await userEvent.type(screen.getByLabelText('MRN'), '12')
    await screen.findByText('MRN must be exactly 6 digits')

    expect(screen.getByText('Jane Doe')).toBeInTheDocument()
    expect(container.querySelector('.opacity-50')).toBeInTheDocument()
  })

  it('wrong filters before anything has loaded: the hint, no table, no request for them', async () => {
    getOrders.mockReturnValue(new Promise(() => {})) // the first load never ends
    renderOrders()

    await userEvent.type(screen.getByLabelText('MRN'), '12')

    expect(await screen.findByText('Fix the highlighted filters to update the results.')).toBeInTheDocument()
    expect(screen.queryByRole('table')).not.toBeInTheDocument()
    expect(getOrders).toHaveBeenCalledTimes(1) // only the first load
  })
})

describe('OrdersPage search: sorting', () => {
  const header = (name: string) => screen.getByRole('columnheader', { name })

  it('a header goes ascending, descending, then back to the default order', async () => {
    await renderWithOrder()
    expect(header('Patient')).toHaveAttribute('aria-sort', 'none')
    expect(screen.getByText('Sorted by: newest first (default)')).toBeInTheDocument()

    await userEvent.click(within(header('Patient')).getByRole('button'))
    await expectRequest({ ...BASE, sort: 'patient_name,asc' })
    expect(header('Patient')).toHaveAttribute('aria-sort', 'ascending')
    expect(screen.getByText('Sorted by: Patient (ascending)')).toBeInTheDocument()

    await userEvent.click(within(header('Patient')).getByRole('button'))
    await expectRequest({ ...BASE, sort: 'patient_name,desc' })
    expect(header('Patient')).toHaveAttribute('aria-sort', 'descending')

    await userEvent.click(within(header('Patient')).getByRole('button'))
    await expectRequest(BASE) // no sort parameter: the backend's default order
    expect(header('Patient')).toHaveAttribute('aria-sort', 'none')
    expect(screen.getByText('Sorted by: newest first (default)')).toBeInTheDocument()
  })

  it.each([
    ['Provider', 'provider_name'],
    ['Medication', 'medication'],
    ['Status', 'status'],
    ['Created', 'created_at'],
  ])('the "%s" header sorts by %s', async (name, column) => {
    await renderWithOrder()

    await userEvent.click(within(header(name)).getByRole('button'))

    await expectRequest({ ...BASE, sort: `${column},asc` })
  })

  it('only one column is sorted at a time, and only one sort parameter is ever sent', async () => {
    await renderWithOrder()
    await userEvent.click(within(header('Patient')).getByRole('button'))
    await expectRequest({ ...BASE, sort: 'patient_name,asc' })

    await userEvent.click(within(header('Created')).getByRole('button'))

    await expectRequest({ ...BASE, sort: 'created_at,asc' })
    expect(header('Patient')).toHaveAttribute('aria-sort', 'none')
    expect(header('Created')).toHaveAttribute('aria-sort', 'ascending')
    expect(typeof lastParams()?.sort).toBe('string')
  })

  it('"Care Plan" cannot be sorted', async () => {
    await renderWithOrder()

    expect(within(header('Care Plan')).queryByRole('button')).not.toBeInTheDocument()
  })

  it('sorting keeps the filters', async () => {
    await renderWithOrder()
    await userEvent.click(within(screen.getByRole('group', { name: 'Status' })).getByRole('button', { name: 'Failed' }))
    await expectRequest({ ...BASE, status: 'failed' })

    await userEvent.click(within(header('Medication')).getByRole('button'))

    await expectRequest({ ...BASE, status: 'failed', sort: 'medication,asc' })
  })
})

describe('OrdersPage search: page numbers', () => {
  /** 45 orders, 20 per page: three pages, and the row says which page it came from. */
  function serveThreePages() {
    getOrders.mockImplementation(async (params) =>
      makePage([makeListItem({ id: params?.page ?? 1, patientName: `Patient on page ${params?.page}` })], {
        count: 45, page: params?.page ?? 1, pageSize: 20,
      }),
    )
  }

  async function goToPageTwo() {
    serveThreePages()
    renderOrders()
    await screen.findByText('Patient on page 1')
    await userEvent.click(screen.getByRole('button', { name: 'Next' }))
    await screen.findByText('Patient on page 2')
  }

  it('a status change goes back to page 1', async () => {
    await goToPageTwo()

    await userEvent.click(within(screen.getByRole('group', { name: 'Status' })).getByRole('button', { name: 'Failed' }))

    await expectRequest({ ...BASE, status: 'failed' })
  })

  it('a date change goes back to page 1', async () => {
    await goToPageTwo()

    fireEvent.change(screen.getByLabelText('Created from (UTC)'), { target: { value: '2026-11-01' } })

    await expectRequest({ ...BASE, created_from: '2026-11-01' })
  })

  it('a sort change goes back to page 1', async () => {
    await goToPageTwo()

    await userEvent.click(within(screen.getByRole('columnheader', { name: 'Patient' })).getByRole('button'))

    await expectRequest({ ...BASE, sort: 'patient_name,asc' })
  })

  it('a text filter goes back to page 1', async () => {
    await goToPageTwo()

    await userEvent.type(screen.getByLabelText('Medication'), 'ivig')

    await expectRequest({ ...BASE, medication: 'ivig' })
  })

  it('the Previous and Next buttons still keep the filters and the sort', async () => {
    serveThreePages()
    renderOrders()
    await screen.findByText('Patient on page 1')
    await userEvent.click(within(screen.getByRole('group', { name: 'Status' })).getByRole('button', { name: 'Failed' }))
    await expectRequest({ ...BASE, status: 'failed' })

    await userEvent.click(screen.getByRole('button', { name: 'Next' }))

    await expectRequest({ page: 2, page_size: 20, status: 'failed' })
  })
})

describe('OrdersPage search: the total', () => {
  it('says how many results there are in a live region, with one page or several', async () => {
    getOrders.mockResolvedValue(makePage([makeListItem()], { count: 45, pageSize: 20 }))
    renderOrders()

    expect(await screen.findByRole('status')).toHaveTextContent('45 results')
    expect(screen.getByText('Page 1 of 3 · 45 orders')).toBeInTheDocument()
  })

  it('one result is "1 result"', async () => {
    getOrders.mockResolvedValue(makePage([makeListItem()]))
    renderOrders()

    expect(await screen.findByRole('status')).toHaveTextContent('1 result')
  })
})

describe('OrdersPage search: when the list is empty', () => {
  it('no orders at all, no filters: "No orders yet" and the link (as before)', async () => {
    getOrders.mockResolvedValue(makePage([]))
    renderOrders()

    expect(await screen.findByText('No orders yet')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Create your first order' })).toBeInTheDocument()
    expect(screen.queryByText('No orders match your filters')).not.toBeInTheDocument()
  })

  it('filters that match nothing: "No orders match your filters" and "Clear filters"', async () => {
    getOrders.mockImplementation(async (params) =>
      params?.status === 'failed' ? makePage([]) : makePage([makeListItem()]))
    renderOrders()
    await screen.findByRole('table')

    await userEvent.click(within(screen.getByRole('group', { name: 'Status' })).getByRole('button', { name: 'Failed' }))

    expect(await screen.findByText('No orders match your filters')).toBeInTheDocument()
    expect(screen.queryByText('No orders yet')).not.toBeInTheDocument()
    expect(screen.queryByRole('link', { name: 'Create your first order' })).not.toBeInTheDocument()
  })

  it('"Clear filters" empties the boxes and asks for the full list again', async () => {
    getOrders.mockImplementation(async (params) =>
      params?.medication ? makePage([]) : makePage([makeListItem({ patientName: 'Back again' })]))
    renderOrders()
    await screen.findByText('Back again')
    await userEvent.type(screen.getByLabelText('Medication'), 'zzz')
    await screen.findByText('No orders match your filters')

    await userEvent.click(screen.getByRole('button', { name: 'Clear filters' }))

    expect(await screen.findByText('Back again')).toBeInTheDocument()
    expect(screen.getByLabelText('Medication')).toHaveValue('')
    expect(lastParams()).toEqual(BASE)
  })

  it('a page past the last one is not "no orders": it says so and offers the first page', async () => {
    getOrders.mockImplementation(async (params) =>
      params?.page === 1
        ? makePage([makeListItem({ patientName: 'On page one' })], { count: 45, pageSize: 20 })
        : makePage([], { count: 45, page: params?.page ?? 2, pageSize: 20 }))
    renderOrders()
    await screen.findByText('On page one')
    await userEvent.click(screen.getByRole('button', { name: 'Next' }))

    expect(await screen.findByText('There are no results on this page.')).toBeInTheDocument()
    expect(screen.queryByText('No orders yet')).not.toBeInTheDocument()

    await userEvent.click(screen.getByRole('button', { name: 'Go to the first page' }))

    expect(await screen.findByText('On page one')).toBeInTheDocument()
    expect(lastParams()).toEqual(BASE)
  })
})

describe('OrdersPage search: "Clear all filters"', () => {
  it('is disabled until a filter is set', async () => {
    await renderWithOrder()

    expect(screen.getByRole('button', { name: 'Clear all filters' })).toBeDisabled()

    await userEvent.type(screen.getByLabelText('Medication'), 'x')

    expect(screen.getByRole('button', { name: 'Clear all filters' })).toBeEnabled() // even before the pause
  })

  it('empties every box, the status and the dates, goes to page 1, and keeps the sort', async () => {
    await renderWithOrder()
    await userEvent.type(screen.getByLabelText('Patient name'), 'Jane')
    await userEvent.type(screen.getByLabelText('MRN'), '123456')
    await userEvent.type(screen.getByLabelText('Medication'), 'ivig')
    await userEvent.type(screen.getByLabelText('Provider name'), 'green')
    fireEvent.change(screen.getByLabelText('Created from (UTC)'), { target: { value: '2026-11-01' } })
    fireEvent.change(screen.getByLabelText('Created to (UTC)'), { target: { value: '2026-11-30' } })
    await userEvent.click(within(screen.getByRole('group', { name: 'Status' })).getByRole('button', { name: 'Failed' }))
    await userEvent.click(within(screen.getByRole('columnheader', { name: 'Medication' })).getByRole('button'))
    await waitFor(() => expect(lastParams()).toMatchObject({ patient_name: 'Jane', mrn: '123456', sort: 'medication,asc' }))

    await userEvent.click(screen.getByRole('button', { name: 'Clear all filters' }))

    await expectRequest({ ...BASE, sort: 'medication,asc' })
    for (const label of ['Patient name', 'MRN', 'Medication', 'Provider name', 'Created from (UTC)', 'Created to (UTC)']) {
      expect(screen.getByLabelText(label)).toHaveValue('')
    }
    expect(within(screen.getByRole('group', { name: 'Status' })).getByRole('button', { name: 'All' }))
      .toHaveAttribute('aria-pressed', 'true')
    expect(screen.getByRole('button', { name: 'Clear all filters' })).toBeDisabled()
  })
})

describe('OrdersPage search: errors from the backend', () => {
  it.each([
    ['INVALID_SORT', 'This sort is not supported. Choose another column, or click the header again to reset the sort.'],
    ['INVALID_DATE_RANGE', 'The start date must not be after the end date.'],
    ['INVALID_DATE', 'Dates must be between 1900-01-01 and 2999-12-31.'],
    ['INVALID_PAGE_SIZE', 'This page size is not supported.'],
    ['INVALID_SEARCH_TEXT', 'Search texts must be at most 100 characters.'],
  ])('%s is shown as a sentence people can act on', async (code, text) => {
    getOrders.mockRejectedValue(makeApiError('internal wording', { code, status: 400 }))
    renderOrders()

    expect(await screen.findByRole('alert')).toHaveTextContent(text)
    expect(screen.queryByText(/internal wording/)).not.toBeInTheDocument()
  })

  it('the filter panel stays on screen and usable after an error', async () => {
    getOrders.mockRejectedValueOnce(makeApiError('x', { code: 'INVALID_SORT', status: 400 }))
    getOrders.mockResolvedValue(makePage([makeListItem({ patientName: 'Recovered' })]))
    renderOrders()
    await screen.findByRole('alert')
    expect(screen.getByLabelText('Medication')).toBeInTheDocument()

    await userEvent.click(within(screen.getByRole('group', { name: 'Status' })).getByRole('button', { name: 'Completed' }))

    expect(await screen.findByText('Recovered')).toBeInTheDocument()
  })

  it('any other failure keeps the old wording', async () => {
    getOrders.mockRejectedValue(makeApiError('Server down', { status: 500 }))
    renderOrders()

    expect(await screen.findByText('Error loading orders: Server down')).toBeInTheDocument()
  })
})

describe('OrdersPage search: what each role sees', () => {
  it('an assistant gets the same filter panel and table, and no Export button or hint', async () => {
    await renderWithOrder()
    await userEvent.click(within(screen.getByRole('group', { name: 'Status' })).getByRole('button', { name: 'Failed' }))

    expect(screen.getByLabelText('Patient name')).toBeInTheDocument()
    expect(screen.getByRole('table')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'New Order' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Export CSV' })).not.toBeInTheDocument()
    expect(screen.queryByText(/Export includes all orders/)).not.toBeInTheDocument()
  })

  it('a pharmacist gets the same panel and table plus the Export button, unchanged', async () => {
    auth.state = { pharmacist: true }
    await renderWithOrder()

    expect(screen.getByLabelText('Patient name')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Export CSV' })).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: 'Export CSV' }))
    expect(downloadFile).toHaveBeenCalledWith('/export', 'orders.csv')
  })

  it('the export hint is not there without a filter', async () => {
    auth.state = { pharmacist: true }
    await renderWithOrder()

    expect(screen.queryByText('Export includes all orders, not only the filtered ones.')).not.toBeInTheDocument()
  })

  it.each([
    ['a status', async () => userEvent.click(within(screen.getByRole('group', { name: 'Status' })).getByRole('button', { name: 'Failed' }))],
    ['a date', async () => { fireEvent.change(screen.getByLabelText('Created from (UTC)'), { target: { value: '2026-11-01' } }) }],
    ['a text', async () => userEvent.type(screen.getByLabelText('Medication'), 'ivig')],
  ])('a pharmacist with %s set is told that Export includes all orders', async (_label, setFilter) => {
    auth.state = { pharmacist: true }
    await renderWithOrder()

    await setFilter()

    expect(await screen.findByText('Export includes all orders, not only the filtered ones.')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Export CSV' })).toBeInTheDocument() // the button text is unchanged
  })

  it('the hint goes away when the filters are cleared', async () => {
    auth.state = { pharmacist: true }
    await renderWithOrder()
    await userEvent.click(within(screen.getByRole('group', { name: 'Status' })).getByRole('button', { name: 'Failed' }))
    await screen.findByText(/Export includes all orders/)

    await userEvent.click(screen.getByRole('button', { name: 'Clear all filters' }))

    await waitFor(() => expect(screen.queryByText(/Export includes all orders/)).not.toBeInTheDocument())
  })

  it('sorting alone does not trigger the export hint (a sort is not a filter)', async () => {
    auth.state = { pharmacist: true }
    await renderWithOrder()

    await userEvent.click(within(screen.getByRole('columnheader', { name: 'Patient' })).getByRole('button'))
    await expectRequest({ ...BASE, sort: 'patient_name,asc' })

    expect(screen.queryByText(/Export includes all orders/)).not.toBeInTheDocument()
  })
})
