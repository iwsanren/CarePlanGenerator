import { beforeEach, describe, expect, it, vi } from 'vitest'
import { act, renderHook } from '@testing-library/react'

// auth.ts runs `new Keycloak(...)` the moment it is imported. We replace keycloak-js
// with a controllable stand-in: tests set the fields a real client would expose
// (authenticated, token, tokenParsed, resourceAccess) and observe the calls made on it.
interface FakeKeycloak {
  authenticated?: boolean
  token?: string
  tokenParsed?: { sub?: string; preferred_username?: string; exp?: number }
  resourceAccess?: Record<string, { roles: string[] }>
  onAuthSuccess?: () => void
  onAuthRefreshSuccess?: () => void
  onAuthLogout?: () => void
  init: ReturnType<typeof vi.fn>
  updateToken: ReturnType<typeof vi.fn>
  clearToken: ReturnType<typeof vi.fn>
  login: ReturnType<typeof vi.fn>
  logout: ReturnType<typeof vi.fn>
}

const kc = vi.hoisted(() => ({
  init: vi.fn(),
  updateToken: vi.fn(),
  clearToken: vi.fn(),
  login: vi.fn(),
  logout: vi.fn(),
}) as FakeKeycloak)

// `new Keycloak()` returns our single shared fake.
vi.mock('keycloak-js', () => ({
  default: class {
    constructor() {
      return kc
    }
  },
}))

// auth.ts keeps its state in module variables, so every test imports a fresh copy.
let auth: typeof import('./auth')

beforeEach(async () => {
  vi.resetAllMocks()
  for (const key of ['authenticated', 'token', 'tokenParsed', 'resourceAccess', 'onAuthSuccess', 'onAuthRefreshSuccess', 'onAuthLogout'] as const) {
    delete kc[key]
  }
  kc.init.mockResolvedValue(true)
  kc.updateToken.mockResolvedValue(true)
  kc.login.mockResolvedValue(undefined)
  kc.logout.mockResolvedValue(undefined)
  // The real clearToken drops the session; mimic that so publish() sees a signed-out client.
  kc.clearToken.mockImplementation(() => {
    kc.authenticated = false
    delete kc.token
    delete kc.tokenParsed
    delete kc.resourceAccess
  })
  vi.resetModules()
  auth = await import('./auth')
})

/** Make the fake client look signed in with the given API roles. */
function signedInAs(roles: string[], username = 'alice') {
  kc.authenticated = true
  kc.token = 'access-token'
  kc.tokenParsed = { sub: 'subject-1', preferred_username: username, exp: 9999999999 }
  kc.resourceAccess = { 'careplan-api': { roles } }
}

describe('initial state', () => {
  it('starts not ready, not authenticated, with no roles', () => {
    expect(auth.getAuthState()).toEqual({ ready: false, authenticated: false, pharmacist: false, staff: false })
  })
})

describe('initializeAuth', () => {
  it('initialises Keycloak with PKCE and silent SSO check, then publishes a ready state', async () => {
    await auth.initializeAuth()

    expect(kc.init).toHaveBeenCalledWith({ onLoad: 'check-sso', pkceMethod: 'S256', checkLoginIframe: false })
    expect(auth.getAuthState()).toMatchObject({ ready: true, authenticated: false, error: undefined })
  })

  it('runs only once even when called repeatedly (React StrictMode mounts twice)', async () => {
    await Promise.all([auth.initializeAuth(), auth.initializeAuth()])
    await auth.initializeAuth()

    expect(kc.init).toHaveBeenCalledTimes(1)
  })

  it('copies identity and roles from the token into the state', async () => {
    signedInAs(['PHARMACIST'], 'pat')
    await auth.initializeAuth()

    expect(auth.getAuthState()).toEqual({
      ready: true,
      authenticated: true,
      subject: 'subject-1',
      username: 'pat',
      pharmacist: true,
      staff: true,
      error: undefined,
    })
  })

  it('treats an ASSISTANT as staff but not as pharmacist', async () => {
    signedInAs(['ASSISTANT'])
    await auth.initializeAuth()

    expect(auth.getAuthState()).toMatchObject({ pharmacist: false, staff: true })
  })

  it('gives no staff access to an authenticated user without a pharmacy role', async () => {
    signedInAs(['SOMETHING_ELSE'])
    await auth.initializeAuth()

    expect(auth.getAuthState()).toMatchObject({ authenticated: true, pharmacist: false, staff: false })
  })

  it('ignores roles that belong to another client', async () => {
    signedInAs([])
    kc.resourceAccess = { 'other-client': { roles: ['PHARMACIST'] } }
    await auth.initializeAuth()

    expect(auth.getAuthState()).toMatchObject({ pharmacist: false, staff: false })
  })

  it('becomes ready with an error and no token when the sign-in service is unreachable', async () => {
    kc.init.mockRejectedValue(new Error('network'))
    await auth.initializeAuth()

    expect(kc.clearToken).toHaveBeenCalled()
    expect(auth.getAuthState()).toMatchObject({
      ready: true,
      authenticated: false,
      error: 'Sign-in service unavailable. Please retry.',
    })
  })

  it('republishes the state when Keycloak reports a successful login or token refresh', async () => {
    await auth.initializeAuth()
    expect(auth.getAuthState().authenticated).toBe(false)

    signedInAs(['PHARMACIST'], 'first')
    kc.onAuthSuccess?.()
    expect(auth.getAuthState()).toMatchObject({ authenticated: true, username: 'first' })

    kc.tokenParsed = { sub: 'subject-1', preferred_username: 'renamed' }
    kc.onAuthRefreshSuccess?.()
    expect(auth.getAuthState().username).toBe('renamed')
  })

  it('invalidates the session when Keycloak reports a logout', async () => {
    signedInAs(['ASSISTANT'])
    await auth.initializeAuth()
    const { signal } = await auth.authorization()
    const before = auth.currentGeneration()

    kc.authenticated = false
    kc.onAuthLogout?.()

    expect(auth.currentGeneration()).toBe(before + 1)
    expect(signal.aborted).toBe(true)
    expect(auth.getAuthState().authenticated).toBe(false)
  })
})

describe('authorization', () => {
  it('refuses to run when nobody is signed in', async () => {
    await auth.initializeAuth()

    await expect(auth.authorization()).rejects.toThrow('Sign in required')
    expect(kc.updateToken).not.toHaveBeenCalled()
  })

  it('refreshes the token if it expires within 30 seconds, then returns it with a live signal', async () => {
    signedInAs(['ASSISTANT'])
    await auth.initializeAuth()

    const result = await auth.authorization()

    expect(kc.updateToken).toHaveBeenCalledWith(30)
    expect(result.token).toBe('access-token')
    expect(result.generation).toBe(auth.currentGeneration())
    expect(result.signal.aborted).toBe(false)
  })

  it('expires the session and fails when the refresh fails', async () => {
    signedInAs(['ASSISTANT'])
    await auth.initializeAuth()
    kc.updateToken.mockRejectedValue(new Error('refresh token invalid'))

    await expect(auth.authorization()).rejects.toThrow('Session expired')

    expect(kc.clearToken).toHaveBeenCalled()
    expect(auth.getAuthState()).toMatchObject({
      authenticated: false,
      error: 'Your session expired. Please sign in again.',
    })
  })

  it('fails when the session changed while the token was being refreshed', async () => {
    signedInAs(['ASSISTANT'])
    await auth.initializeAuth()
    // Another tab signs out while updateToken() is in flight.
    kc.updateToken.mockImplementation(async () => { kc.onAuthLogout?.() })

    await expect(auth.authorization()).rejects.toThrow('Session changed')
  })

  it('fails when there is no token after a refresh', async () => {
    signedInAs(['ASSISTANT'])
    await auth.initializeAuth()
    kc.token = undefined

    await expect(auth.authorization()).rejects.toThrow('Session changed')
  })

  it('aborts the returned signal when the caller aborts', async () => {
    signedInAs(['ASSISTANT'])
    await auth.initializeAuth()
    const caller = new AbortController()

    const { signal } = await auth.authorization(caller.signal)
    expect(signal.aborted).toBe(false)
    caller.abort()

    expect(signal.aborted).toBe(true)
  })

  it('aborts the returned signal when the session expires, even if the caller did not abort', async () => {
    signedInAs(['ASSISTANT'])
    await auth.initializeAuth()
    const caller = new AbortController()

    const { signal } = await auth.authorization(caller.signal)
    auth.expireSession()

    expect(signal.aborted).toBe(true)
    expect(caller.signal.aborted).toBe(false)
  })
})

describe('expireSession', () => {
  it('bumps the generation so in-flight requests can be recognised as stale', () => {
    const before = auth.currentGeneration()
    auth.expireSession()

    expect(auth.currentGeneration()).toBe(before + 1)
  })

  it('aborts pending requests, clears the token and publishes the default message', async () => {
    signedInAs(['PHARMACIST'])
    await auth.initializeAuth()
    const { signal } = await auth.authorization()

    auth.expireSession()

    expect(signal.aborted).toBe(true)
    expect(kc.clearToken).toHaveBeenCalled()
    expect(auth.getAuthState()).toMatchObject({
      authenticated: false,
      pharmacist: false,
      staff: false,
      error: 'Your session expired. Please sign in again.',
    })
  })

  it('accepts a custom message', () => {
    auth.expireSession('Signed out elsewhere')

    expect(auth.getAuthState().error).toBe('Signed out elsewhere')
  })

  it('gives the next session a fresh, un-aborted signal', async () => {
    signedInAs(['ASSISTANT'])
    await auth.initializeAuth()
    const first = await auth.authorization()
    auth.expireSession()

    signedInAs(['ASSISTANT']) // user signs in again
    kc.onAuthSuccess?.()
    const second = await auth.authorization()

    expect(second.signal).not.toBe(first.signal)
    expect(second.signal.aborted).toBe(false)
  })
})

describe('useAuth', () => {
  it('re-renders subscribers whenever the state is published', async () => {
    const { result } = renderHook(() => auth.useAuth())
    expect(result.current.ready).toBe(false)

    signedInAs(['PHARMACIST'], 'pat')
    await act(async () => { await auth.initializeAuth() })
    expect(result.current).toMatchObject({ ready: true, authenticated: true, username: 'pat', pharmacist: true })

    act(() => { auth.expireSession() })
    expect(result.current).toMatchObject({ authenticated: false, error: 'Your session expired. Please sign in again.' })
  })

  it('stops notifying a component after it unmounts', async () => {
    const { result, unmount } = renderHook(() => auth.useAuth())
    unmount()

    await act(async () => { await auth.initializeAuth() })

    expect(result.current.ready).toBe(false) // frozen at its last render
  })
})

describe('signIn', () => {
  it('sends the user to Keycloak and brings them back to /orders', async () => {
    await auth.signIn()

    expect(kc.login).toHaveBeenCalledWith({ redirectUri: window.location.origin + '/orders' })
  })
})

describe('signOut', () => {
  it('clears the visible state at once, aborts in-flight requests, then redirects through Keycloak', async () => {
    signedInAs(['PHARMACIST'])
    await auth.initializeAuth()
    const { result } = renderHook(() => auth.useAuth())
    const { signal } = await auth.authorization()
    const before = auth.currentGeneration()

    let finish: () => void = () => {}
    kc.logout.mockReturnValue(new Promise<void>((resolve) => { finish = resolve }))
    let pending: Promise<void> = Promise.resolve()
    act(() => { pending = auth.signOut() })

    // The protected view is gone before the redirect completes.
    expect(result.current).toEqual({ ready: true, authenticated: false, pharmacist: false, staff: false })
    expect(signal.aborted).toBe(true)
    expect(auth.currentGeneration()).toBe(before + 1)
    expect(kc.logout).toHaveBeenCalledWith({ redirectUri: window.location.origin })

    finish()
    await pending
  })
})
