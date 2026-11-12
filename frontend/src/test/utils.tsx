import type { ReactElement, ReactNode } from 'react'
import { render } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom'

/** A QueryClient for tests: no cache sharing between tests, retries are instant. */
export function createTestQueryClient() {
  return new QueryClient({
    defaultOptions: {
      // Hooks that declare their own `retry` function still override this, but
      // `retryDelay: 0` stops the default 1s+ back-off from slowing error-state tests.
      queries: { retry: false, retryDelay: 0, gcTime: Infinity },
      mutations: { retry: false },
    },
  })
}

/** Provider wrapper for renderHook (hooks need react-query, not a router). */
export function queryWrapper(client: QueryClient) {
  return function Wrapper({ children }: { children: ReactNode }) {
    return <QueryClientProvider client={client}>{children}</QueryClientProvider>
  }
}

/** Shows the current router path so tests can assert on navigation. */
function LocationProbe() {
  const location = useLocation()
  return <div data-testid="location">{location.pathname}</div>
}

/**
 * Render a page inside a fresh QueryClient and an in-memory router.
 * `path` is the route pattern (e.g. "/orders/:id"), `route` the URL to start on.
 * Every other path renders a "location" probe, so a navigation can be asserted
 * with `screen.getByTestId('location')`.
 */
export function renderPage(
  ui: ReactElement,
  { route = '/', path = '/', client = createTestQueryClient() } = {},
) {
  const result = render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={[route]}>
        <Routes>
          <Route path={path} element={ui} />
          <Route path="*" element={<LocationProbe />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  )
  return { ...result, client }
}
