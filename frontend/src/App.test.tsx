import { beforeEach, describe, expect, it, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter } from 'react-router-dom'
import { orderService } from '@/services/orderService'
import { createTestQueryClient } from '@/test/utils'
import { makeListItem, makeOrder, makePage } from '@/test/fixtures'
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
