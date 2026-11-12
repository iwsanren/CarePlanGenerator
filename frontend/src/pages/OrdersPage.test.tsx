import { beforeEach, describe, expect, it, vi } from 'vitest'
import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { orderService } from '@/services/orderService'
import { downloadFile } from '@/services/download'
import { renderPage } from '@/test/utils'
import { makeApiError, makeListItem, makePage } from '@/test/fixtures'
import type { OrderStatus } from '@/types'
import { OrdersPage } from './OrdersPage'

// Why these mocks:
// - '@/auth/auth' builds a real Keycloak client on import. The page only needs
//   useAuth() (is this user a pharmacist?), so we replace the module with a stub
//   whose state we control. keycloak-js is then never loaded.
// - orderService / download are the HTTP boundary. The page, the useOrders hook and
//   react-query all run for real on top of them.
const auth = vi.hoisted(() => ({ state: { pharmacist: false }, useAuth: vi.fn() }))
vi.mock('@/auth/auth', () => ({ useAuth: auth.useAuth }))
vi.mock('@/services/orderService', () => ({ orderService: { getOrders: vi.fn() } }))
vi.mock('@/services/download', () => ({ downloadFile: vi.fn() }))

const getOrders = vi.mocked(orderService.getOrders)

function renderOrders() {
  return renderPage(<OrdersPage />, { route: '/orders', path: '/orders' })
}

beforeEach(() => {
  vi.resetAllMocks()
  auth.state = { pharmacist: false }
  auth.useAuth.mockImplementation(() => auth.state)
})

describe('OrdersPage states', () => {
  it('shows a skeleton and no table while the first load is in flight', () => {
    getOrders.mockReturnValue(new Promise(() => {})) // never resolves
    const { container } = renderOrders()

    expect(screen.getByRole('heading', { name: 'Orders' })).toBeInTheDocument()
    expect(container.querySelector('.animate-pulse')).toBeInTheDocument()
    expect(screen.queryByRole('table')).not.toBeInTheDocument()
    expect(screen.queryByText('No orders yet')).not.toBeInTheDocument()
  })

  it('shows the error message when loading fails', async () => {
    getOrders.mockRejectedValue(makeApiError('Server down', { status: 500 }))
    renderOrders()

    expect(await screen.findByText('Error loading orders: Server down')).toBeInTheDocument()
    expect(screen.queryByRole('table')).not.toBeInTheDocument()
  })

  it('shows an empty state with a link to create the first order', async () => {
    getOrders.mockResolvedValue(makePage([]))
    renderOrders()

    expect(await screen.findByText('No orders yet')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Create your first order' })).toHaveAttribute('href', '/')
  })

  it('requests the first page with the page size of 20', async () => {
    getOrders.mockResolvedValue(makePage([]))
    renderOrders()

    await screen.findByText('No orders yet')
    expect(getOrders).toHaveBeenCalledWith({ page: 1, page_size: 20 })
  })
})

describe('OrdersPage table', () => {
  it('renders the column headers and one row per order', async () => {
    getOrders.mockResolvedValue(makePage([
      makeListItem({ id: 1, patientName: 'Jane Doe', patientMrn: '111111', providerName: 'Dr. Smith', medicationName: 'IVIG' }),
      makeListItem({ id: 2, patientName: 'John Roe', patientMrn: '222222', providerName: 'Dr. Jones', medicationName: 'Humira' }),
    ]))
    renderOrders()

    await screen.findByRole('table')
    for (const header of ['Patient', 'Provider', 'Medication', 'Status', 'Created', 'Care Plan']) {
      expect(screen.getByRole('columnheader', { name: header })).toBeInTheDocument()
    }
    const rows = screen.getAllByRole('row')
    expect(rows).toHaveLength(3) // header + 2 orders
    const first = within(rows[1])
    expect(first.getByText('Jane Doe')).toBeInTheDocument()
    expect(first.getByText('MRN: 111111')).toBeInTheDocument()
    expect(first.getByText('Dr. Smith')).toBeInTheDocument()
    expect(first.getByText('IVIG')).toBeInTheDocument()
    expect(within(rows[2]).getByText('John Roe')).toBeInTheDocument()
  })

  it('formats the created timestamp', async () => {
    getOrders.mockResolvedValue(makePage([makeListItem({ createdAt: '2026-08-30T12:34:56Z' })]))
    renderOrders()

    expect(await screen.findByText('Aug 30, 2026, 12:34 PM')).toBeInTheDocument() // TZ is pinned to UTC
  })

  it.each<[OrderStatus, string]>([
    ['completed', '✓ Ready'],
    ['processing', 'Generating…'],
    ['failed', 'Failed'],
    ['pending', 'Pending'],
  ])('shows the status badge and the "%s" care plan label', async (status, label) => {
    getOrders.mockResolvedValue(makePage([makeListItem({ status })]))
    renderOrders()

    const row = within((await screen.findAllByRole('row'))[1])
    expect(row.getByText(status)).toBeInTheDocument() // badge
    expect(row.getByText(label)).toBeInTheDocument() // care plan column
  })

  it('links the patient name to the order detail', async () => {
    getOrders.mockResolvedValue(makePage([makeListItem({ id: 42, patientName: 'Jane Doe' })]))
    renderOrders()

    expect(await screen.findByRole('link', { name: 'Jane Doe' })).toHaveAttribute('href', '/orders/42')
  })

  it('opens the order when the row is clicked', async () => {
    getOrders.mockResolvedValue(makePage([makeListItem({ id: 42, medicationName: 'IVIG' })]))
    renderOrders()

    await userEvent.click(await screen.findByText('IVIG'))

    expect(screen.getByTestId('location')).toHaveTextContent('/orders/42')
  })

  it('opens the order when the patient link is clicked', async () => {
    getOrders.mockResolvedValue(makePage([makeListItem({ id: 42, patientName: 'Jane Doe' })]))
    renderOrders()

    await userEvent.click(await screen.findByRole('link', { name: 'Jane Doe' }))

    expect(screen.getByTestId('location')).toHaveTextContent('/orders/42')
  })

  it('goes to the new-order form from the "New Order" button', async () => {
    getOrders.mockResolvedValue(makePage([]))
    renderOrders()

    await userEvent.click(screen.getByRole('button', { name: 'New Order' }))

    expect(screen.getByTestId('location')).toHaveTextContent('/')
  })
})

describe('OrdersPage pagination', () => {
  /** 45 orders in total, 20 per page -> 3 pages. */
  function serveThreePages() {
    getOrders.mockImplementation(async (params) =>
      makePage([makeListItem({ id: params?.page ?? 1, patientName: `Patient on page ${params?.page}` })], {
        count: 45,
        page: params?.page ?? 1,
        pageSize: 20,
      }),
    )
  }

  it('hides the controls when everything fits on one page', async () => {
    getOrders.mockResolvedValue(makePage([makeListItem()], { count: 20, pageSize: 20 }))
    renderOrders()

    await screen.findByRole('table')
    expect(screen.queryByRole('button', { name: 'Next' })).not.toBeInTheDocument()
    expect(screen.queryByText(/Page \d+ of/)).not.toBeInTheDocument()
  })

  it('shows the page position and total, with Previous disabled on the first page', async () => {
    serveThreePages()
    renderOrders()

    expect(await screen.findByText('Page 1 of 3 · 45 orders')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Previous' })).toBeDisabled()
    expect(screen.getByRole('button', { name: 'Next' })).toBeEnabled()
  })

  it('moves forward and back, requesting each page from the server', async () => {
    serveThreePages()
    renderOrders()
    await screen.findByText('Patient on page 1')

    await userEvent.click(screen.getByRole('button', { name: 'Next' }))
    expect(await screen.findByText('Patient on page 2')).toBeInTheDocument()
    expect(getOrders).toHaveBeenLastCalledWith({ page: 2, page_size: 20 })
    expect(screen.getByText('Page 2 of 3 · 45 orders')).toBeInTheDocument()

    await userEvent.click(screen.getByRole('button', { name: 'Previous' }))
    expect(await screen.findByText('Patient on page 1')).toBeInTheDocument()
  })

  it('disables Next on the last page', async () => {
    serveThreePages()
    renderOrders()
    await screen.findByText('Patient on page 1')

    await userEvent.click(screen.getByRole('button', { name: 'Next' }))
    await screen.findByText('Patient on page 2')
    await userEvent.click(screen.getByRole('button', { name: 'Next' }))
    await screen.findByText('Patient on page 3')

    expect(screen.getByRole('button', { name: 'Next' })).toBeDisabled()
    expect(screen.getByRole('button', { name: 'Previous' })).toBeEnabled()
  })

  it('keeps the previous rows visible (dimmed) while the next page loads', async () => {
    getOrders
      .mockResolvedValueOnce(makePage([makeListItem({ patientName: 'Old page patient' })], { count: 45, pageSize: 20 }))
      .mockReturnValueOnce(new Promise(() => {})) // page 2 never arrives
    const { container } = renderOrders()
    await screen.findByText('Old page patient')

    await userEvent.click(screen.getByRole('button', { name: 'Next' }))

    await waitFor(() => expect(container.querySelector('.opacity-50')).toBeInTheDocument())
    expect(screen.getByText('Old page patient')).toBeInTheDocument()
  })
})

describe('OrdersPage CSV export', () => {
  it('is not offered to an assistant', async () => {
    getOrders.mockResolvedValue(makePage([]))
    renderOrders()

    await screen.findByText('No orders yet')
    expect(screen.queryByRole('button', { name: 'Export CSV' })).not.toBeInTheDocument()
  })

  it('downloads /export as orders.csv for a pharmacist', async () => {
    auth.state = { pharmacist: true }
    getOrders.mockResolvedValue(makePage([]))
    renderOrders()

    await userEvent.click(screen.getByRole('button', { name: 'Export CSV' }))

    expect(downloadFile).toHaveBeenCalledWith('/export', 'orders.csv')
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  })

  it('shows the server\'s message when the export fails', async () => {
    auth.state = { pharmacist: true }
    getOrders.mockResolvedValue(makePage([]))
    vi.mocked(downloadFile).mockRejectedValue(new Error('Permission denied'))
    renderOrders()

    await userEvent.click(screen.getByRole('button', { name: 'Export CSV' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('Permission denied')
  })

  it('falls back to a generic message when the failure has none', async () => {
    auth.state = { pharmacist: true }
    getOrders.mockResolvedValue(makePage([]))
    vi.mocked(downloadFile).mockRejectedValue(new Error(''))
    renderOrders()

    await userEvent.click(screen.getByRole('button', { name: 'Export CSV' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('Export failed. Please try again.')
  })
})
