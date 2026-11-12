import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import axios, { AxiosError, type AxiosAdapter, type InternalAxiosRequestConfig } from 'axios'

// api.ts imports @/auth/auth, which builds a real Keycloak client on import.
// Replace the whole module so keycloak-js is never loaded; each export is a spy
// we can steer per test.
const auth = vi.hoisted(() => ({
  authorization: vi.fn(),
  currentGeneration: vi.fn(),
  expireSession: vi.fn(),
}))
vi.mock('@/auth/auth', () => auth)

import api from './api'

/** What the fake transport saw, so tests can inspect what the interceptors did to the request. */
let lastRequest: InternalAxiosRequestConfig | undefined
let sessionController: AbortController

/** Install a fake transport: the interceptors are real, only the network is replaced. */
function respondWith(status: number, data: unknown = {}, headers: Record<string, string> = {}) {
  const adapter: AxiosAdapter = async (config) => {
    lastRequest = config
    const response = { data, status, statusText: String(status), headers, config }
    if (status >= 200 && status < 300) return response
    throw new AxiosError(`Request failed with status code ${status}`, 'ERR_BAD_RESPONSE', config, null, response)
  }
  api.defaults.adapter = adapter
}

/** Transport failure with no HTTP response at all (network down, CORS, ...). */
function failWith(error: Error) {
  api.defaults.adapter = async () => { throw error }
}

beforeEach(() => {
  vi.resetAllMocks()
  lastRequest = undefined
  sessionController = new AbortController()
  auth.authorization.mockResolvedValue({ token: 'tok-123', signal: sessionController.signal, generation: 1 })
  auth.currentGeneration.mockReturnValue(1)
})

describe('request interceptor', () => {
  it('adds the bearer token from the auth module', async () => {
    respondWith(200)
    await api.get('/orders')

    expect(lastRequest?.headers.Authorization).toBe('Bearer tok-123')
  })

  it('replaces the request signal with the session-bound one', async () => {
    respondWith(200)
    await api.get('/orders')

    expect(lastRequest?.signal).toBe(sessionController.signal)
  })

  it('hands the caller\'s own signal to authorization() so it can be combined', async () => {
    respondWith(200)
    const caller = new AbortController()
    await api.get('/orders', { signal: caller.signal })

    expect(auth.authorization).toHaveBeenCalledWith(caller.signal)
  })

  it('stamps the request with the auth generation it was issued under', async () => {
    auth.authorization.mockResolvedValue({ token: 't', signal: sessionController.signal, generation: 4 })
    auth.currentGeneration.mockReturnValue(4)
    respondWith(200)
    await api.get('/orders')

    expect((lastRequest as unknown as { authGeneration: number }).authGeneration).toBe(4)
  })

  it('fails the request, without calling the network, when sign-in is required', async () => {
    auth.authorization.mockRejectedValue(new Error('Sign in required'))
    respondWith(200)

    await expect(api.get('/orders')).rejects.toThrow('Sign in required')
    expect(lastRequest).toBeUndefined()
  })

  it('sends JSON by default', () => {
    expect(api.defaults.headers['Content-Type']).toBe('application/json')
  })
})

describe('baseURL', () => {
  afterEach(() => {
    vi.unstubAllEnvs()
    vi.resetModules()
  })

  it('defaults to /api/v1', async () => {
    vi.stubEnv('VITE_API_URL', '')
    vi.resetModules()
    const fresh = (await import('./api')).default

    expect(fresh.defaults.baseURL).toBe('/api/v1')
  })

  it('can be overridden with VITE_API_URL', async () => {
    vi.stubEnv('VITE_API_URL', 'https://api.example.test/v2')
    vi.resetModules()
    const fresh = (await import('./api')).default

    expect(fresh.defaults.baseURL).toBe('https://api.example.test/v2')
  })
})

describe('response interceptor: success', () => {
  it('returns the response when the auth generation has not changed', async () => {
    respondWith(200, { ok: true })
    const response = await api.get('/orders')

    expect(response.data).toEqual({ ok: true })
  })

  it('discards a response that arrives after the session changed', async () => {
    respondWith(200, { secret: 'clinical data' })
    // The request was issued under generation 1, but sign-out/expiry bumped it to 2 meanwhile.
    auth.currentGeneration.mockReturnValue(2)

    const error = await api.get('/orders').catch((e: unknown) => e)

    expect(axios.isCancel(error)).toBe(true)
    expect((error as Error).message).toBe('Session changed')
  })
})

describe('response interceptor: errors', () => {
  it('maps the backend error body onto the thrown error', async () => {
    respondWith(409, {
      type: 'block',
      code: 'DUPLICATE_ORDER',
      message: 'Duplicate order',
      detail: { orderId: 3 },
      httpStatus: 409,
    })

    await expect(api.post('/orders', {})).rejects.toMatchObject({
      message: 'Duplicate order',
      type: 'block',
      code: 'DUPLICATE_ORDER',
      detail: { orderId: 3 },
      status: 409,
    })
  })

  it('takes status from the transport, not from body.httpStatus', async () => {
    respondWith(500, { message: 'x', httpStatus: 418 })

    await expect(api.get('/orders')).rejects.toMatchObject({ status: 500 })
  })

  it('falls back to the transport message when the body has no message', async () => {
    respondWith(500, {})

    await expect(api.get('/orders')).rejects.toThrow('Request failed with status code 500')
  })

  it('handles a failure with no response at all (network down)', async () => {
    failWith(new AxiosError('Network Error', 'ERR_NETWORK'))

    const error = (await api.get('/orders').catch((e: unknown) => e)) as Error & { status?: number }
    expect(error.message).toBe('Network Error')
    expect(error.status).toBeUndefined()
  })

  it('uses a generic message when neither body nor transport provide one', async () => {
    failWith(new AxiosError('', 'ERR_UNKNOWN'))

    await expect(api.get('/orders')).rejects.toThrow('An error occurred')
  })

  it('reads the JSON error out of a Blob body (file downloads)', async () => {
    respondWith(403, new Blob([JSON.stringify({ message: 'Permission denied', code: 'FORBIDDEN' })]))

    await expect(api.get('/export', { responseType: 'blob' })).rejects.toMatchObject({
      message: 'Permission denied',
      code: 'FORBIDDEN',
      status: 403,
    })
  })

  it('falls back to the transport message when a Blob body is not JSON', async () => {
    respondWith(502, new Blob(['<html>Bad gateway</html>']))

    await expect(api.get('/export', { responseType: 'blob' })).rejects.toMatchObject({
      message: 'Request failed with status code 502',
      status: 502,
    })
  })

  it('passes cancellations through untouched', async () => {
    const canceled = new axios.CanceledError('aborted by caller')
    failWith(canceled)

    await expect(api.get('/orders')).rejects.toBe(canceled)
    expect(auth.expireSession).not.toHaveBeenCalled()
  })
})

describe('response interceptor: 401 handling', () => {
  it('expires the session on a 401 for a current-generation request', async () => {
    respondWith(401, { message: 'Unauthorized' })

    await expect(api.get('/orders')).rejects.toMatchObject({ status: 401 })
    expect(auth.expireSession).toHaveBeenCalledTimes(1)
  })

  it('ignores a 401 that belongs to an older session (already replaced)', async () => {
    respondWith(401, { message: 'Unauthorized' })
    auth.currentGeneration.mockReturnValue(2) // request was issued under generation 1

    await expect(api.get('/orders')).rejects.toMatchObject({ status: 401 })
    expect(auth.expireSession).not.toHaveBeenCalled()
  })

  it.each([400, 403, 404, 500])('does not expire the session on a %i', async (status) => {
    respondWith(status, { message: 'nope' })

    await expect(api.get('/orders')).rejects.toMatchObject({ status })
    expect(auth.expireSession).not.toHaveBeenCalled()
  })
})
