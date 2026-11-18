import { beforeEach, describe, expect, it, vi } from 'vitest'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom'
import { OrderSearchMemoryProvider } from '@/components/OrderSearchMemoryProvider'
import { orderService } from '@/services/orderService'
import { createTestQueryClient } from '@/test/utils'
import { makeListItem, makePage } from '@/test/fixtures'
import { OrdersPage } from './OrdersPage'

// The order list and the address bar: what the page reads from the URL, and what it writes back. The memory of the
// patient name / MRN is tested with and without the provider the Layout renders. Same boundaries as the other
// OrdersPage tests: auth is a stub, orderService is the HTTP boundary, everything else runs for real.
const auth = vi.hoisted(() => ({ state: { pharmacist: false }, useAuth: vi.fn() }))
vi.mock('@/auth/auth', () => ({ useAuth: auth.useAuth }))
vi.mock('@/services/orderService', () => ({ orderService: { getOrders: vi.fn() } }))
vi.mock('@/services/download', () => ({ downloadFile: vi.fn() }))

const getOrders = vi.mocked(orderService.getOrders)
const lastParams = () => getOrders.mock.lastCall?.[0]
const BASE = { page: 1, page_size: 20 }

/** Shows the address bar, so a test can read what the page wrote to it. */
function AddressBar() {
  const { pathname, search } = useLocation()
  return <div data-testid="address">{pathname + search}</div>
}

function renderOrders(route = '/orders', { provider = false } = {}) {
  const page = (
    <>
      <OrdersPage />
      <AddressBar />
    </>
  )
  return render(
    <QueryClientProvider client={createTestQueryClient()}>
      <MemoryRouter initialEntries={[route]}>
        <Routes>
          <Route
            path="/orders"
            element={provider ? <OrderSearchMemoryProvider>{page}</OrderSearchMemoryProvider> : page}
          />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

const address = () => screen.getByTestId('address').textContent
const statusButton = (name: string) => within(screen.getByRole('group', { name: 'Status' })).getByRole('button', { name })

/** 90 orders, 20 per page: five pages; each row says which page it came from. */
function serveFivePages() {
  getOrders.mockImplementation(async (params) =>
    makePage([makeListItem({ id: params?.page ?? 1, patientName: `Patient on page ${params?.page}` })], {
      count: 90, page: params?.page ?? 1, pageSize: 20,
    }),
  )
}

beforeEach(() => {
  vi.resetAllMocks()
  auth.state = { pharmacist: false }
  auth.useAuth.mockImplementation(() => auth.state)
  serveFivePages()
})

describe('OrdersPage and the URL: reading', () => {
  it('a URL with a search shows it in the panel and the header, and asks the server for exactly that', async () => {
    renderOrders('/orders?status=failed&medication=ivig&provider_name=green&created_from=2026-11-01'
      + '&sort=status%2Cdesc&page=2')

    expect(await screen.findByText('Patient on page 2')).toBeInTheDocument()
    expect(lastParams()).toEqual({
      page: 2, page_size: 20, status: 'failed', medication: 'ivig', provider_name: 'green',
      created_from: '2026-11-01', sort: 'status,desc',
    })
    expect(screen.getByLabelText('Medication')).toHaveValue('ivig')
    expect(screen.getByLabelText('Provider name')).toHaveValue('green')
    expect(screen.getByLabelText('Created from (UTC)')).toHaveValue('2026-11-01')
    expect(statusButton('Failed')).toHaveAttribute('aria-pressed', 'true')
    expect(screen.getByRole('columnheader', { name: 'Status' })).toHaveAttribute('aria-sort', 'descending')
    expect(screen.getByText('Page 2 of 5 · 90 orders')).toBeInTheDocument()
  })

  it('a URL with junk is read as the default for each bad part', async () => {
    renderOrders('/orders?status=nope&page=0&sort=password&created_from=2026-02-30')

    await screen.findByText('Patient on page 1')

    expect(lastParams()).toEqual(BASE)
    expect(screen.getByText('Sorted by: newest first (default)')).toBeInTheDocument()
  })

  it('a page past the end is the "no results on this page" state, with a way to the first page', async () => {
    getOrders.mockImplementation(async (params) =>
      params?.page === 1
        ? makePage([makeListItem({ patientName: 'On page one' })], { count: 45, pageSize: 20 })
        : makePage([], { count: 45, page: params?.page ?? 99, pageSize: 20 }))
    renderOrders('/orders?page=99')

    expect(await screen.findByText('There are no results on this page.')).toBeInTheDocument()

    await userEvent.click(screen.getByRole('button', { name: 'Go to the first page' }))

    expect(await screen.findByText('On page one')).toBeInTheDocument()
    expect(address()).toBe('/orders')
  })
})

describe('OrdersPage and the URL: writing', () => {
  it('the default list is a bare /orders', async () => {
    renderOrders()
    await screen.findByText('Patient on page 1')

    expect(address()).toBe('/orders')
  })

  it('a status, a date and a sort are written at once; the texts after the pause', async () => {
    renderOrders()
    await screen.findByText('Patient on page 1')

    await userEvent.click(statusButton('Failed'))
    expect(address()).toBe('/orders?status=failed')

    await userEvent.click(within(screen.getByRole('columnheader', { name: 'Medication' })).getByRole('button'))
    expect(address()).toBe('/orders?status=failed&sort=medication%2Casc')

    await userEvent.type(screen.getByLabelText('Provider name'), 'green')
    expect(address()).toBe('/orders?status=failed&sort=medication%2Casc') // still typing
    await waitFor(() => expect(address()).toBe('/orders?status=failed&provider_name=green&sort=medication%2Casc'))
  })

  it('Next and Previous change the page in the URL', async () => {
    renderOrders()
    await screen.findByText('Patient on page 1')

    await userEvent.click(screen.getByRole('button', { name: 'Next' }))
    expect(address()).toBe('/orders?page=2')
    await screen.findByText('Patient on page 2')

    await userEvent.click(screen.getByRole('button', { name: 'Previous' }))
    expect(address()).toBe('/orders')
  })

  it('"Clear all filters" empties the URL of filters and keeps the sort', async () => {
    renderOrders('/orders?status=failed&medication=ivig&sort=status%2Cdesc&page=2')
    await screen.findByText('Patient on page 2')

    await userEvent.click(screen.getByRole('button', { name: 'Clear all filters' }))

    expect(address()).toBe('/orders?sort=status%2Cdesc')
    expect(screen.getByLabelText('Medication')).toHaveValue('')
  })
})

describe('OrdersPage and the URL: patient name and MRN', () => {
  it.each([[false], [true]])('are requested but never put in the address bar (shared memory: %s)', async (provider) => {
    renderOrders('/orders', { provider })
    await screen.findByText('Patient on page 1')

    await userEvent.type(screen.getByLabelText('Patient name'), 'Jane Doe')
    await userEvent.type(screen.getByLabelText('MRN'), '123456')
    await waitFor(() => expect(lastParams()).toEqual({ ...BASE, patient_name: 'Jane Doe', mrn: '123456' }))

    expect(address()).toBe('/orders')
  })

  it.each([[false], [true]])(
    'on page 3, a new name is never requested together with page 3 (shared memory: %s)',
    async (provider) => {
      renderOrders('/orders?page=3', { provider })
      await screen.findByText('Patient on page 3')

      await userEvent.type(screen.getByLabelText('Patient name'), 'Jane')
      await screen.findByText('Patient on page 1')

      const requests = getOrders.mock.calls.map(([params]) => params)
      expect(requests.at(-1)).toEqual({ ...BASE, patient_name: 'Jane' })
      expect(requests.filter((params) => params?.patient_name !== undefined && params.page === 3)).toEqual([])
      expect(address()).toBe('/orders')
    },
  )

  it('an MRN on page 3 goes back to page 1 in the same step as well', async () => {
    renderOrders('/orders?page=3&status=failed', { provider: true })
    await screen.findByText('Patient on page 3')

    await userEvent.type(screen.getByLabelText('MRN'), '123456')
    await screen.findByText('Patient on page 1')

    const withMrn = getOrders.mock.calls.map(([params]) => params).filter((params) => params?.mrn !== undefined)
    expect(withMrn.map((params) => params?.page)).toEqual([1])
    expect(address()).toBe('/orders?status=failed')
  })
})

describe('OrdersPage and the URL: what must not be in a URL', () => {
  const JUNK = '/orders?patient_name=Jane%20Doe&mrn=123456&sort=password&foo=bar'

  it('a URL with the patient name, the MRN and a bad sort is ignored: nothing of it is requested or shown', async () => {
    renderOrders(JUNK)
    await screen.findByText('Patient on page 1')

    expect(getOrders).toHaveBeenCalledTimes(1)
    expect(lastParams()).toEqual(BASE)
    expect(screen.getByLabelText('Patient name')).toHaveValue('')
    expect(screen.getByLabelText('MRN')).toHaveValue('')
    expect(screen.getByRole('columnheader', { name: 'Patient' })).toHaveAttribute('aria-sort', 'none')
  })

  it('and after the next thing the user does, the address bar no longer has them', async () => {
    renderOrders(JUNK)
    await screen.findByText('Patient on page 1')
    expect(address()).toBe(JUNK) // not touched while the user does nothing

    await userEvent.click(statusButton('Failed'))

    expect(address()).toBe('/orders?status=failed')
    for (const part of ['patient_name', 'mrn', 'password', 'Jane', '123456', 'foo']) {
      expect(address()).not.toContain(part)
    }
    expect(lastParams()).toEqual({ ...BASE, status: 'failed' })
  })

  it('a patient name typed after that goes to the request, still not to the address bar', async () => {
    renderOrders(JUNK)
    await screen.findByText('Patient on page 1')

    await userEvent.type(screen.getByLabelText('Patient name'), 'Bob')
    await waitFor(() => expect(lastParams()).toEqual({ ...BASE, patient_name: 'Bob' })) // Bob, not the Jane of the URL

    expect(address()).toBe('/orders')
  })
})
