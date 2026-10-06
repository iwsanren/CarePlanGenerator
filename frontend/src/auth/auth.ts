import Keycloak from 'keycloak-js'
import { useSyncExternalStore } from 'react'

export type AuthState = {
  ready: boolean
  authenticated: boolean
  subject?: string
  username?: string
  pharmacist: boolean
  staff: boolean
  error?: string
}

export const keycloak = new Keycloak({
  url: import.meta.env.VITE_KEYCLOAK_URL || 'http://localhost:8081',
  realm: 'careplan',
  clientId: 'careplan-web',
})

let state: AuthState = { ready: false, authenticated: false, pharmacist: false, staff: false }
let session = new AbortController()
let generation = 0
const listeners = new Set<() => void>()
let initialization: Promise<void> | undefined
let initialized = false

function publish(error?: string) {
  const roles = keycloak.resourceAccess?.['careplan-api']?.roles || []
  state = {
    ready: initialized,
    authenticated: !!keycloak.authenticated,
    subject: keycloak.tokenParsed?.sub,
    username: keycloak.tokenParsed?.preferred_username,
    pharmacist: roles.includes('PHARMACIST'),
    staff: roles.includes('PHARMACIST') || roles.includes('ASSISTANT'),
    error,
  }
  listeners.forEach(listener => listener())
}

export function expireSession(message = 'Your session expired. Please sign in again.') {
  generation++
  session.abort()
  session = new AbortController()
  keycloak.clearToken()
  publish(message)
}

export function initializeAuth() {
  initialization ??= (async () => {
    keycloak.onAuthSuccess = () => publish()
    keycloak.onAuthRefreshSuccess = () => publish()
    keycloak.onAuthLogout = () => {
      generation++
      session.abort()
      session = new AbortController()
      publish()
    }
    try {
      await keycloak.init({ onLoad: 'check-sso', pkceMethod: 'S256', checkLoginIframe: false })
      initialized = true
      publish()
    } catch {
      initialized = true
      keycloak.clearToken()
      publish('Sign-in service unavailable. Please retry.')
    }
  })()
  return initialization
}

export function useAuth() {
  return useSyncExternalStore(listener => {
    listeners.add(listener)
    return () => { listeners.delete(listener) }
  }, () => state)
}

export function getAuthState() { return state }

export async function authorization(signal?: AbortSignal) {
  const expectedGeneration = generation
  if (!state.authenticated) throw new Error('Sign in required')
  try {
    await keycloak.updateToken(30)
  } catch {
    expireSession()
    throw new Error('Session expired')
  }
  if (expectedGeneration !== generation || !keycloak.token) throw new Error('Session changed')
  return {
    token: keycloak.token,
    signal: signal ? AbortSignal.any([signal, session.signal]) : session.signal,
    generation,
  }
}

export function currentGeneration() { return generation }

export async function signIn() {
  await keycloak.login({ redirectUri: window.location.origin + '/orders' })
}

export async function signOut() {
  generation++
  session.abort()
  // Clear the protected view and its query cache before redirecting.
  state = { ready: true, authenticated: false, pharmacist: false, staff: false }
  listeners.forEach(listener => listener())
  await keycloak.logout({ redirectUri: window.location.origin })
}
