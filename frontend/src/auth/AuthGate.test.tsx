import { beforeEach, describe, expect, it, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClientProvider } from '@tanstack/react-query'
import { createTestQueryClient } from '@/test/utils'
import type { AuthState } from './auth'
import { AuthGate } from './AuthGate'

// AuthGate is a thin view over ./auth. Replace that module (so keycloak-js never
// loads) and drive the gate by changing the state the mocked useAuth returns.
const auth = vi.hoisted(() => ({
  state: { ready: true, authenticated: false, pharmacist: false, staff: false } as AuthState,
  useAuth: vi.fn(),
  initializeAuth: vi.fn(),
  signIn: vi.fn(),
  signOut: vi.fn(),
}))
vi.mock('./auth', () => ({
  useAuth: auth.useAuth,
  initializeAuth: auth.initializeAuth,
  signIn: auth.signIn,
  signOut: auth.signOut,
}))

function setAuth(state: Partial<AuthState>) {
  auth.state = { ready: true, authenticated: false, pharmacist: false, staff: false, ...state }
}

function renderGate(client = createTestQueryClient()) {
  // A function, not a constant: rerender() with the very same element object would
  // be skipped by React, so every render needs a fresh element.
  const ui = () => (
    <QueryClientProvider client={client}>
      <AuthGate><p>protected content</p></AuthGate>
    </QueryClientProvider>
  )
  const view = render(ui())
  return { client, rerenderGate: () => view.rerender(ui()) }
}

beforeEach(() => {
  vi.resetAllMocks()
  auth.useAuth.mockImplementation(() => auth.state)
  auth.initializeAuth.mockResolvedValue(undefined)
  auth.signIn.mockResolvedValue(undefined)
  auth.signOut.mockResolvedValue(undefined)
  setAuth({})
})

describe('AuthGate', () => {
  it('starts the Keycloak initialisation once on mount', () => {
    renderGate()

    expect(auth.initializeAuth).toHaveBeenCalledTimes(1)
  })

  it('shows a connecting message and hides the content until auth is ready', () => {
    setAuth({ ready: false })
    renderGate()

    expect(screen.getByText('Connecting to sign-in service…')).toBeInTheDocument()
    expect(screen.queryByText('protected content')).not.toBeInTheDocument()
  })

  it('asks an anonymous visitor to sign in, and the button starts the login', async () => {
    renderGate()

    expect(screen.getByText('Sign in with your pharmacy staff account.')).toBeInTheDocument()
    expect(screen.queryByText('protected content')).not.toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: 'Sign in' }))

    expect(auth.signIn).toHaveBeenCalledTimes(1)
    expect(auth.signOut).not.toHaveBeenCalled()
  })

  it('shows the auth error instead of the default prompt (e.g. session expired)', () => {
    setAuth({ error: 'Your session expired. Please sign in again.' })
    renderGate()

    expect(screen.getByText('Your session expired. Please sign in again.')).toBeInTheDocument()
    expect(screen.queryByText('Sign in with your pharmacy staff account.')).not.toBeInTheDocument()
  })

  it('denies a signed-in user without a pharmacy role and offers sign-out', async () => {
    setAuth({ authenticated: true, staff: false })
    renderGate()

    expect(screen.getByText('This account has no pharmacy staff role. Contact your administrator.')).toBeInTheDocument()
    expect(screen.queryByText('protected content')).not.toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: 'Sign out' }))

    expect(auth.signOut).toHaveBeenCalledTimes(1)
    expect(auth.signIn).not.toHaveBeenCalled()
  })

  it.each([
    ['an assistant', { pharmacist: false }],
    ['a pharmacist', { pharmacist: true }],
  ])('renders the content for %s', (_name, extra) => {
    setAuth({ authenticated: true, staff: true, ...extra })
    renderGate()

    expect(screen.getByText('protected content')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Sign in' })).not.toBeInTheDocument()
  })

  it('does not let staff status alone through without authentication', () => {
    setAuth({ authenticated: false, staff: true })
    renderGate()

    expect(screen.queryByText('protected content')).not.toBeInTheDocument()
  })

  describe('cached data', () => {
    it('is cancelled and cleared when the gate mounts', () => {
      const client = createTestQueryClient()
      const cancel = vi.spyOn(client, 'cancelQueries')
      const clear = vi.spyOn(client, 'clear')
      renderGate(client)

      expect(cancel).toHaveBeenCalled()
      expect(clear).toHaveBeenCalled()
    })

    it('is dropped when a different user signs in, so one user never sees another user\'s orders', () => {
      setAuth({ authenticated: true, staff: true, subject: 'user-a' })
      const client = createTestQueryClient()
      client.setQueryData(['orders'], { secret: 'user-a data' })
      const { rerenderGate } = renderGate(client)
      const clear = vi.spyOn(client, 'clear')

      setAuth({ authenticated: true, staff: true, subject: 'user-b' })
      rerenderGate()

      expect(clear).toHaveBeenCalledTimes(1)
      expect(client.getQueryData(['orders'])).toBeUndefined()
    })

    it('is not cleared again by a re-render with the same identity', () => {
      setAuth({ authenticated: true, staff: true, subject: 'user-a' })
      const { client, rerenderGate } = renderGate()
      const clear = vi.spyOn(client, 'clear')

      rerenderGate()

      expect(clear).not.toHaveBeenCalled()
    })
  })
})
