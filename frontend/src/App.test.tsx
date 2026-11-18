import { beforeEach, describe, expect, it, vi } from 'vitest'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter } from 'react-router-dom'
import { orderService } from '@/services/orderService'
import { createTestQueryClient } from '@/test/utils'
import { dashboardService } from '@/services/dashboardService'
import { makeDashboardStats, makeListItem, makeOrder, makePage } from '@/test/fixtures'
import type { AuthState } from '@/auth/auth'
import App from './App'

// App = AuthGate + Layout + routes. Auth is replaced by a stub (no keycloak-js) whose
// state we set per test; orderService is the HTTP boundary. Everything else is real.
const auth = vi.hoisted(() => ({
  state: {} as AuthState,
  useAuth: vi.fn(),
  initializeAuth: vi.fn(),
  signIn: vi.fn(),
  signOut: vi.fn(),
}))
vi.mock('@/auth/auth', () => ({
  useAuth: auth.useAuth,
  initializeAuth: auth.initializeAuth,
  signIn: auth.signIn,
  signOut: auth.signOut,
}))
vi.mock('@/services/orderService', () => ({
  orderService: { getOrders: vi.fn(), getOrder: vi.fn(), getCarePlanStatus: vi.fn() },
}))

vi.mock('@/services/dashboardService', () => ({ dashboardService: { getStats: vi.fn() } }))

function signedIn(overrides: Partial<AuthState> = {}) {
  auth.state = {
    ready: true, authenticated: true, staff: true, pharmacist: false, subject: 's1', username: 'alice', ...overrides,
  }
}

function renderApp(route: string) {
  return render(
    <QueryClientProvider client={createTestQueryClient()}>
      <MemoryRouter initialEntries={[route]}>
        <App />
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

beforeEach(() => {
  vi.resetAllMocks()
  auth.useAuth.mockImplementation(() => auth.state)
  auth.initializeAuth.mockResolvedValue(undefined)
  auth.signOut.mockResolvedValue(undefined)
  vi.mocked(orderService.getOrders).mockResolvedValue(makePage([makeListItem()]))
  vi.mocked(orderService.getOrder).mockResolvedValue(makeOrder({ id: 7 }))
  vi.mocked(orderService.getCarePlanStatus).mockResolvedValue({ orderId: 7, status: 'completed' })
  vi.mocked(dashboardService.getStats).mockResolvedValue(makeDashboardStats())
  signedIn()
})

describe('App routing', () => {
  it('serves the new-order form at /', () => {
    renderApp('/')

    expect(screen.getByRole('heading', { name: 'New Order' })).toBeInTheDocument()
  })

  it('serves the order list at /orders', async () => {
    renderApp('/orders')

    expect(screen.getByRole('heading', { name: 'Orders' })).toBeInTheDocument()
    expect(await screen.findByText('Jane Doe')).toBeInTheDocument()
  })

  it('serves the order detail at /orders/:id', async () => {
    renderApp('/orders/7')

    expect(await screen.findByRole('heading', { name: 'Order #7' })).toBeInTheDocument()
    expect(orderService.getOrder).toHaveBeenCalledWith(7)
  })

  it('renders only the layout for an unknown path (no crash)', () => {
    // React Router warns about the unmatched path; capture it so it does not clutter the output.
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {})
    renderApp('/nowhere')

    expect(screen.getByRole('heading', { name: 'Care Plan Generator' })).toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: /New Order|Orders|Order #/ })).not.toBeInTheDocument()
    expect(warn).toHaveBeenCalledWith(expect.stringContaining('No routes matched location "/nowhere"'))
    warn.mockRestore()
  })
})

describe('App layout', () => {
  it('shows the app title, the user and their role', () => {
    signedIn({ username: 'alice', pharmacist: false })
    renderApp('/')

    expect(screen.getByRole('heading', { name: 'Care Plan Generator' })).toBeInTheDocument()
    expect(screen.getByText('alice · Assistant')).toBeInTheDocument()
  })

  it('labels a pharmacist as Pharmacist', () => {
    signedIn({ username: 'pat', pharmacist: true })
    renderApp('/')

    expect(screen.getByText('pat · Pharmacist')).toBeInTheDocument()
  })

  it('highlights the nav link of the current page only', () => {
    renderApp('/orders')

    expect(screen.getByRole('link', { name: 'Orders' })).toHaveClass('bg-blue-100')
    expect(screen.getByRole('link', { name: 'New Order' })).not.toHaveClass('bg-blue-100')
  })

  it('navigates between pages with the nav links', async () => {
    renderApp('/')

    await userEvent.click(screen.getByRole('link', { name: 'Orders' }))

    expect(await screen.findByRole('heading', { name: 'Orders' })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Orders' })).toHaveClass('bg-blue-100')
  })

  it('Sign out calls signOut', async () => {
    renderApp('/')

    await userEvent.click(screen.getByRole('button', { name: 'Sign out' }))

    expect(auth.signOut).toHaveBeenCalledTimes(1)
  })
})

describe('App dashboard route and navigation (layer 1: UX only; the backend enforces the role)', () => {
  it('an assistant does not see the Dashboard entry in the navigation', () => {
    signedIn({ pharmacist: false })
    renderApp('/orders')

    expect(screen.queryByRole('link', { name: 'Dashboard' })).not.toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Orders' })).toBeInTheDocument() // the other entries stay
  })

  it('a pharmacist sees the Dashboard entry', () => {
    signedIn({ pharmacist: true })
    renderApp('/orders')

    expect(screen.getByRole('link', { name: 'Dashboard' })).toHaveAttribute('href', '/dashboard')
  })

  it('an assistant who opens /dashboard directly gets a no-permission message and nothing is requested', async () => {
    signedIn({ pharmacist: false })
    renderApp('/dashboard')

    expect(screen.getByText('No permission')).toBeInTheDocument()
    expect(screen.getByText('The dashboard is available to pharmacists only.')).toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: 'Dashboard' })).not.toBeInTheDocument()
    expect(dashboardService.getStats).not.toHaveBeenCalled()
  })

  it('a pharmacist gets the dashboard at /dashboard, loaded lazily, with its nav link highlighted', async () => {
    signedIn({ pharmacist: true })
    renderApp('/dashboard')

    // The first import() of the page makes Vitest compile Recharts, which takes a few seconds on a cold
    // cache (in a browser this is the chunk download), so wait longer than the default 1 s here.
    expect(await screen.findByRole('heading', { name: 'Dashboard' }, { timeout: 15000 })).toBeInTheDocument()
    expect(await screen.findByRole('group', { name: 'Total orders' })).toBeInTheDocument()
    expect(dashboardService.getStats).toHaveBeenCalledWith(7)
    expect(screen.getByRole('link', { name: 'Dashboard' })).toHaveClass('bg-blue-100')
  }, 20000)

  it('navigates to the dashboard from the nav link', async () => {
    signedIn({ pharmacist: true })
    renderApp('/orders')

    await userEvent.click(screen.getByRole('link', { name: 'Dashboard' }))

    expect(await screen.findByRole('heading', { name: 'Dashboard' })).toBeInTheDocument()
  })
})

// The order list search lives in the URL (status, dates, medication, provider, sort, page) and in the memory of the
// Layout (patient name, MRN). These tests go through the whole App: list -> order -> back. The text boxes wait
// 300 ms before they change the search, hence the waitFor.
describe('App: the order list search across pages', () => {
  const getOrders = vi.mocked(orderService.getOrders)
  const lastParams = () => getOrders.mock.lastCall?.[0]

  /** Types a patient name into the list and waits until the list has asked for it. */
  async function searchForPatient(name: string) {
    await screen.findByText('Jane Doe')
    await userEvent.type(screen.getByLabelText('Patient name'), name)
    await waitFor(() => expect(lastParams()).toMatchObject({ patient_name: name }))
  }

  async function openFirstOrder() {
    await userEvent.click(await screen.findByRole('link', { name: 'Jane Doe' }))
    await screen.findByRole('heading', { name: 'Order #7' })
  }

  it('"Back to orders" returns to the same list and keeps the patient name', async () => {
    renderApp('/orders')
    await searchForPatient('Smith')
    await userEvent.click(within(screen.getByRole('group', { name: 'Status' })).getByRole('button', { name: 'Failed' }))
    await waitFor(() => expect(lastParams()).toMatchObject({ patient_name: 'Smith', status: 'failed' }))
    await openFirstOrder()

    const back = screen.getByRole('link', { name: '← Back to orders' })
    expect(back).toHaveAttribute('href', '/orders?status=failed') // the name is not in the link
    await userEvent.click(back)

    expect(await screen.findByLabelText('Patient name')).toHaveValue('Smith')
    expect(within(screen.getByRole('group', { name: 'Status' })).getByRole('button', { name: 'Failed' }))
      .toHaveAttribute('aria-pressed', 'true')
    await waitFor(() => expect(lastParams()).toEqual({ page: 1, page_size: 20, status: 'failed', patient_name: 'Smith' }))
  })

  it('the "Orders" link in the navigation starts a fresh list: the patient name and MRN are cleared', async () => {
    renderApp('/orders')
    await searchForPatient('Smith')
    await userEvent.type(screen.getByLabelText('MRN'), '123456')
    await waitFor(() => expect(lastParams()).toMatchObject({ patient_name: 'Smith', mrn: '123456' }))

    await userEvent.click(screen.getByRole('link', { name: 'Orders' }))

    await waitFor(() => expect(lastParams()).toEqual({ page: 1, page_size: 20 }))
    expect(screen.getByLabelText('Patient name')).toHaveValue('')
    expect(screen.getByLabelText('MRN')).toHaveValue('')
  })

  it('"Orders" in the navigation from an order also gives a fresh list (URL part and memory part)', async () => {
    renderApp('/orders')
    await searchForPatient('Smith')
    await userEvent.click(within(screen.getByRole('group', { name: 'Status' })).getByRole('button', { name: 'Failed' }))
    await waitFor(() => expect(lastParams()).toMatchObject({ patient_name: 'Smith', status: 'failed' }))
    await openFirstOrder()

    await userEvent.click(screen.getByRole('link', { name: 'Orders' }))

    expect(await screen.findByLabelText('Patient name')).toHaveValue('')
    await waitFor(() => expect(lastParams()).toEqual({ page: 1, page_size: 20 }))
  })

  it('signing out throws the patient name away (the sign-in gate stops rendering the Layout)', async () => {
    const client = createTestQueryClient()
    // A new element every time: React skips a render when it gets the very same element again.
    const tree = () => (
      <QueryClientProvider client={client}>
        <MemoryRouter initialEntries={['/orders']}>
          <App />
        </MemoryRouter>
      </QueryClientProvider>
    )
    const view = render(tree())
    await searchForPatient('Smith')

    signedIn({ authenticated: false, staff: false })
    view.rerender(tree())
    expect(screen.getByRole('button', { name: 'Sign in' })).toBeInTheDocument()

    signedIn()
    view.rerender(tree())

    expect(await screen.findByLabelText('Patient name')).toHaveValue('')
    await waitFor(() => expect(lastParams()).toEqual({ page: 1, page_size: 20 }))
  })
})

describe('App access control', () => {
  it('shows the sign-in prompt instead of any page when signed out', () => {
    signedIn({ authenticated: false, staff: false })
    renderApp('/orders')

    expect(screen.getByRole('button', { name: 'Sign in' })).toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: 'Orders' })).not.toBeInTheDocument()
    expect(orderService.getOrders).not.toHaveBeenCalled() // protected data is never requested
  })

  it('shows the no-role message instead of any page for a user without a staff role', () => {
    signedIn({ staff: false })
    renderApp('/orders/7')

    expect(screen.getByText('This account has no pharmacy staff role. Contact your administrator.')).toBeInTheDocument()
    expect(orderService.getOrder).not.toHaveBeenCalled()
  })
})
