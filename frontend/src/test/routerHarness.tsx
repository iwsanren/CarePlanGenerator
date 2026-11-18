import { useEffect, type ReactNode } from 'react'
import { MemoryRouter, useLocation, useNavigate, type Location, type NavigateFunction } from 'react-router-dom'
import { OrderSearchMemoryProvider } from '@/components/OrderSearchMemoryProvider'
import { useOrderSearchMemory, type OrderSearchMemoryApi } from '@/hooks/useOrderSearchMemory'

/** What a test can look at from outside the tree: the router's location and navigate, and the shared memory. */
export interface Probe {
  location: Location
  navigate: NavigateFunction
  memory?: OrderSearchMemoryApi
}

/**
 * A wrapper for renderHook: an in-memory router starting at `route`, optionally with the shared memory provider
 * (the one the Layout renders). `probe` is kept up to date after every render.
 */
export function routerHarness(route = '/orders', { provider = false } = {}) {
  const probe = {} as Probe

  function Spy() {
    const location = useLocation()
    const navigate = useNavigate()
    useEffect(() => {
      probe.location = location
      probe.navigate = navigate
    })
    return null
  }

  function MemorySpy() {
    const api = useOrderSearchMemory()
    useEffect(() => {
      probe.memory = api
    })
    return null
  }

  function Wrapper({ children }: { children: ReactNode }) {
    const inner = (
      <>
        <Spy />
        {provider && <MemorySpy />}
        {children}
      </>
    )
    return (
      <MemoryRouter initialEntries={[route]}>
        {provider ? <OrderSearchMemoryProvider>{inner}</OrderSearchMemoryProvider> : inner}
      </MemoryRouter>
    )
  }

  return { Wrapper, probe }
}
