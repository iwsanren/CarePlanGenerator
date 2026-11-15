import { beforeEach, describe, expect, it, vi } from 'vitest'
import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { dashboardService } from '@/services/dashboardService'
import { renderPage } from '@/test/utils'
import { makeApiError, makeDashboardStats } from '@/test/fixtures'
import type { DashboardStats } from '@/types'
import { DashboardPage } from './DashboardPage'

// The HTTP boundary is replaced; the page, useDashboard, react-query and the Recharts components run for real.
// Recharts' ResponsiveContainer is given a fixed size in src/test/setup.ts (jsdom has no layout), and the
// assertions below look at the TEXT side of every chart (legend, hidden data tables, labels), not at SVG.
vi.mock('@/services/dashboardService', () => ({ dashboardService: { getStats: vi.fn() } }))
const getStats = vi.mocked(dashboardService.getStats)

function renderDashboard() {
  return renderPage(<DashboardPage />, { route: '/dashboard', path: '/dashboard' })
}

/** Resolve with the standard stats and wait until the page has drawn them. */
async function renderLoaded(stats: DashboardStats = makeDashboardStats()) {
  getStats.mockResolvedValue(stats)
  const view = renderDashboard()
  await screen.findByRole('group', { name: 'Total orders' })
  return view
}

/** The text of the four cells of a hidden data table row, by the row's first cell. */
function rowOf(table: HTMLElement, first: string) {
  const row = within(table).getAllByRole('row').find((r) => within(r).queryAllByRole('cell')[0]?.textContent === first)
  if (!row) throw new Error(`no row starting with ${first}`)
  return within(row).getAllByRole('cell').map((cell) => cell.textContent)
}

beforeEach(() => {
  vi.resetAllMocks()
})

describe('DashboardPage states', () => {
  it('shows the title, the range buttons and a skeleton while the first load is in flight', () => {
    getStats.mockReturnValue(new Promise(() => {}))
    renderDashboard()

    expect(screen.getByRole('heading', { name: 'Dashboard' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Last 7 days' })).toBeInTheDocument()
    expect(screen.getByRole('status', { name: 'Loading dashboard' })).toBeInTheDocument()
    expect(screen.queryByRole('group', { name: 'Total orders' })).not.toBeInTheDocument()
  })

  it('requests the last 7 days first', async () => {
    await renderLoaded()

    expect(getStats).toHaveBeenCalledWith(7)
  })

  it('shows the error message (no blank page) and the range buttons when loading fails', async () => {
    getStats.mockRejectedValue(makeApiError('Server down', { status: 500 }))
    renderDashboard()

    expect(await screen.findByRole('alert')).toHaveTextContent('Error loading dashboard: Server down')
    expect(screen.getByRole('button', { name: 'Last 30 days' })).toBeInTheDocument()
    expect(screen.queryByRole('group', { name: 'Total orders' })).not.toBeInTheDocument()
  })

  it('"Try again" asks again and shows the dashboard when the second attempt works', async () => {
    getStats.mockRejectedValueOnce(makeApiError('Server down', { status: 500 }))
    getStats.mockResolvedValueOnce(makeDashboardStats())
    renderDashboard()

    await userEvent.click(await screen.findByRole('button', { name: 'Try again' }))

    expect(await screen.findByRole('group', { name: 'Total orders' })).toBeInTheDocument()
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
    expect(getStats).toHaveBeenCalledTimes(2)
  })

  it('a 403 from the backend is shown as a permission problem, not as a failure', async () => {
    getStats.mockRejectedValue(makeApiError('Permission denied', { status: 403 }))
    renderDashboard()

    expect(await screen.findByText('The dashboard is available to pharmacists only.')).toBeInTheDocument()
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  })

  it('shows an empty state with a link when there are no orders in the range', async () => {
    getStats.mockResolvedValue(makeDashboardStats({
      kpi: { totalOrders: 0, completedCount: 0, failedCount: 0, failureRate: null, avgGenerationSeconds: null, manualUploadCount: 0 },
    }))
    renderDashboard()

    expect(await screen.findByText('No orders in the last 7 days')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Create an order' })).toHaveAttribute('href', '/')
    expect(screen.queryByRole('group', { name: 'Total orders' })).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Last 30 days' })).toBeInTheDocument() // range can still be changed
  })
})

describe('DashboardPage figures', () => {
  it('shows the window as whole UTC days', async () => {
    await renderLoaded()

    expect(screen.getByText('Nov 8 – Nov 14 · whole UTC calendar days')).toBeInTheDocument()
  })

  it('shows the four KPI cards with their values and what each one is based on', async () => {
    await renderLoaded()

    const total = screen.getByRole('group', { name: 'Total orders' })
    expect(within(total).getByText('8')).toBeInTheDocument()
    expect(within(total).getByText('Created in the last 7 days')).toBeInTheDocument()

    const completion = screen.getByRole('group', { name: 'Completion rate' })
    expect(within(completion).getByText('62.5%')).toBeInTheDocument()
    expect(within(completion).getByText('Share of all orders that are completed')).toBeInTheDocument()

    const failure = screen.getByRole('group', { name: 'Failure rate' })
    expect(within(failure).getByText('20.0%')).toBeInTheDocument()
    expect(within(failure).getByText('Share of finished LLM generations that failed')).toBeInTheDocument()

    const time = screen.getByRole('group', { name: 'Avg generation time' })
    expect(within(time).getByText('20.0 s')).toBeInTheDocument()
  })

  it('no finished generation: failure rate is a dash and the time says "No data" (never 0%)', async () => {
    const base = makeDashboardStats()
    await renderLoaded({ ...base, kpi: { ...base.kpi, failureRate: null, avgGenerationSeconds: null } })

    expect(within(screen.getByRole('group', { name: 'Failure rate' })).getByText('—')).toBeInTheDocument()
    expect(within(screen.getByRole('group', { name: 'Avg generation time' })).getByText('No data')).toBeInTheDocument()
    expect(within(screen.getByRole('group', { name: 'Failure rate' })).queryByText('0.0%')).not.toBeInTheDocument()
  })

  it('a real 0% failure rate is shown as 0.0%', async () => {
    const base = makeDashboardStats()
    await renderLoaded({ ...base, kpi: { ...base.kpi, failureRate: 0 } })

    expect(within(screen.getByRole('group', { name: 'Failure rate' })).getByText('0.0%')).toBeInTheDocument()
  })

  it('the status donut has a legend with counts and percentages, and says what they are a share of', async () => {
    await renderLoaded()

    const legend = screen.getByRole('list', { name: 'Order status legend' })
    const items = within(legend).getAllByRole('listitem').map((li) => li.textContent)
    expect(items).toEqual(['Pending1 · 12.5%', 'Processing1 · 12.5%', 'Completed5 · 62.5%', 'Failed1 · 12.5%'])
    expect(screen.getByText('Percentages are the share of all orders')).toBeInTheDocument()
    expect(screen.getByRole('img', { name: /Donut chart of 8 orders by status: Pending 1 \(12\.5%\)/ })).toBeInTheDocument()
  })

  it('daily orders: a summary for the graphic and every day in a hidden table', async () => {
    await renderLoaded()

    expect(screen.getByRole('img', { name: 'Bar chart of orders per day over the last 7 days: 8 orders in total.' }))
      .toBeInTheDocument()
    const table = screen.getByRole('table', { name: 'Daily orders' })
    expect(within(table).getAllByRole('row')).toHaveLength(8) // header + 7 days
    expect(rowOf(table, '2026-11-08')).toEqual(['2026-11-08', '2'])
    expect(rowOf(table, '2026-11-09')).toEqual(['2026-11-09', '0']) // a day without orders is a 0 row
    expect(rowOf(table, '2026-11-10')).toEqual(['2026-11-10', '3'])
  })

  it('the hidden data tables sit NEXT to the role="img" graphic, not inside it (it would be skipped)', async () => {
    await renderLoaded()

    for (const name of ['Daily orders', 'Generation time and failure rate', 'Top medications']) {
      const table = screen.getByRole('table', { name })
      expect(table).toHaveClass('sr-only')
      expect(table.closest('[role="img"]')).toBeNull()
    }
    // the legend is not inside a graphic either
    expect(screen.getByRole('list', { name: 'Order status legend' }).closest('[role="img"]')).toBeNull()
    expect(screen.getByRole('list', { name: 'Trend legend' }).closest('[role="img"]')).toBeNull()
  })

  it('trend: says it is by order creation day, tells solid from dashed, and lists every day', async () => {
    await renderLoaded()

    expect(screen.getByText(/By order creation day \(UTC\)\. LLM-generated care plans only/)).toBeInTheDocument()
    const legend = screen.getByRole('list', { name: 'Trend legend' })
    expect(within(legend).getByText('Avg generation time (solid line, left axis)')).toBeInTheDocument()
    expect(within(legend).getByText('Failure rate (dashed line, right axis)')).toBeInTheDocument()

    const table = screen.getByRole('table', { name: 'Generation time and failure rate' })
    expect(rowOf(table, '2026-11-08')).toEqual(['2026-11-08', '15.0 s', '0.0%', '2', '0'])
    expect(rowOf(table, '2026-11-10')).toEqual(['2026-11-10', '30.0 s', '50.0%', '0', '1'])
    // a day with nothing to measure is "no data" / a dash, not 0
    expect(rowOf(table, '2026-11-09')).toEqual(['2026-11-09', 'No data', '—', '0', '0'])
    expect(screen.queryByText(/none of these plans has timing data/)).not.toBeInTheDocument()
  })

  it('trend: nothing has finished -> the card says "No data" instead of drawing empty axes', async () => {
    const base = makeDashboardStats()
    await renderLoaded({
      ...base,
      dailyTrend: base.dailyTrend.map((day) => ({
        ...day, completedCount: 0, failedCount: 0, failureRate: null, avgGenerationSeconds: null,
      })),
    })

    expect(screen.getByText('No data: no care plan has finished in this period.')).toBeInTheDocument()
    expect(screen.queryByRole('list', { name: 'Trend legend' })).not.toBeInTheDocument()
    expect(screen.queryByRole('table', { name: 'Generation time and failure rate' })).not.toBeInTheDocument()
  })

  it('trend: failure rate known but no timing data (old plans) -> chart stays, time is reported as no data', async () => {
    const base = makeDashboardStats()
    await renderLoaded({
      ...base,
      dailyTrend: base.dailyTrend.map((day) => ({ ...day, avgGenerationSeconds: null })),
    })

    expect(screen.getByText('No data for generation time: none of these plans has timing data.')).toBeInTheDocument()
    expect(screen.getByRole('list', { name: 'Trend legend' })).toBeInTheDocument()
    const table = screen.getByRole('table', { name: 'Generation time and failure rate' })
    expect(rowOf(table, '2026-11-10')).toEqual(['2026-11-10', 'No data', '50.0%', '0', '1'])
  })

  it('top medications: a summary and the names with their counts', async () => {
    await renderLoaded()

    expect(screen.getByRole('img', { name: 'Horizontal bar chart of the 3 most ordered medications.' })).toBeInTheDocument()
    const table = screen.getByRole('table', { name: 'Top medications' })
    expect(rowOf(table, 'Gamunex-C')).toEqual(['Gamunex-C', '3'])
    expect(rowOf(table, 'Lisinopril')).toEqual(['Lisinopril', '2'])
  })

  it('provider table: name, orders, failed and avg time; no timing data shows "No data"', async () => {
    await renderLoaded()

    const providers = screen.getByRole('region', { name: 'Providers' })
    const rows = within(providers).getAllByRole('row')
    expect(rows).toHaveLength(4)
    const cells = (row: HTMLElement) => within(row).getAllByRole('cell').map((c) => c.textContent)
    expect(cells(rows[1])).toEqual(['Dr. Beta', '4', '0', '30.0 s'])
    expect(cells(rows[2])).toEqual(['Dr. Alpha', '3', '1', '15.0 s'])
    expect(cells(rows[3])).toEqual(['Dr. Gamma', '1', '0', 'No data'])
  })

  it('never shows a patient name or MRN (the response has none)', async () => {
    const { container } = await renderLoaded()

    expect(container.textContent).not.toMatch(/MRN/)
  })
})

describe('DashboardPage range toggle', () => {
  it('7 days is pressed at first; the other button is not', async () => {
    await renderLoaded()

    expect(screen.getByRole('button', { name: 'Last 7 days', pressed: true })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Last 30 days', pressed: false })).toBeInTheDocument()
  })

  it('clicking 30 days requests days=30, flips aria-pressed and shows the 30-day figures', async () => {
    await renderLoaded()
    getStats.mockResolvedValue(makeDashboardStats({
      days: 30,
      from: '2026-10-16',
      kpi: { ...makeDashboardStats().kpi, totalOrders: 25 },
    }))

    await userEvent.click(screen.getByRole('button', { name: 'Last 30 days' }))

    expect(await screen.findByText('Created in the last 30 days')).toBeInTheDocument()
    expect(getStats).toHaveBeenLastCalledWith(30)
    expect(within(screen.getByRole('group', { name: 'Total orders' })).getByText('25')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Last 30 days', pressed: true })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Last 7 days', pressed: false })).toBeInTheDocument()
    expect(screen.getByText('Oct 16 – Nov 14 · whole UTC calendar days')).toBeInTheDocument()
  })

  it('switching back to 7 days works again', async () => {
    await renderLoaded()
    getStats.mockResolvedValue(makeDashboardStats({ days: 30 }))
    await userEvent.click(screen.getByRole('button', { name: 'Last 30 days' }))
    await screen.findByText('Created in the last 30 days')
    getStats.mockResolvedValue(makeDashboardStats({ days: 7 }))

    await userEvent.click(screen.getByRole('button', { name: 'Last 7 days' }))

    expect(await screen.findByText('Created in the last 7 days')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Last 7 days', pressed: true })).toBeInTheDocument()
  })

  it('keeps the old figures on screen, dimmed and marked busy, while the other range loads', async () => {
    const { container } = await renderLoaded()
    getStats.mockReturnValue(new Promise(() => {})) // the 30-day answer never arrives

    await userEvent.click(screen.getByRole('button', { name: 'Last 30 days' }))

    await waitFor(() => expect(container.querySelector('[aria-busy="true"]')).toBeInTheDocument())
    expect(container.querySelector('.opacity-50')).toBeInTheDocument()
    expect(within(screen.getByRole('group', { name: 'Total orders' })).getByText('8')).toBeInTheDocument()
    expect(screen.queryByRole('status', { name: 'Loading dashboard' })).not.toBeInTheDocument()
  })

  it('is not dimmed or busy once the data has arrived', async () => {
    const { container } = await renderLoaded()

    expect(container.querySelector('[aria-busy="true"]')).not.toBeInTheDocument()
    expect(container.querySelector('.opacity-50')).not.toBeInTheDocument()
  })
})

describe('DashboardPage provider table sorting', () => {
  const providerRows = () =>
    within(screen.getByRole('region', { name: 'Providers' })).getAllByRole('row').slice(1)
      .map((row) => within(row).getAllByRole('cell')[0].textContent)
  // scoped to the visible table: the hidden chart tables have "Orders" headers too
  const header = (name: string) =>
    within(screen.getByRole('region', { name: 'Providers' })).getByRole('columnheader', { name })

  it('starts sorted by orders, highest first, and marks only that column', async () => {
    await renderLoaded()

    expect(providerRows()).toEqual(['Dr. Beta', 'Dr. Alpha', 'Dr. Gamma'])
    expect(header('Orders')).toHaveAttribute('aria-sort', 'descending')
    expect(header('Provider')).toHaveAttribute('aria-sort', 'none')
    expect(header('Failed')).toHaveAttribute('aria-sort', 'none')
    expect(header('Avg time')).toHaveAttribute('aria-sort', 'none')
  })

  it('a click on "Provider" sorts A-Z, a second click reverses it', async () => {
    await renderLoaded()

    await userEvent.click(screen.getByRole('button', { name: 'Provider' }))
    expect(providerRows()).toEqual(['Dr. Alpha', 'Dr. Beta', 'Dr. Gamma'])
    expect(header('Provider')).toHaveAttribute('aria-sort', 'ascending')
    expect(header('Orders')).toHaveAttribute('aria-sort', 'none')

    await userEvent.click(screen.getByRole('button', { name: 'Provider' }))
    expect(providerRows()).toEqual(['Dr. Gamma', 'Dr. Beta', 'Dr. Alpha'])
    expect(header('Provider')).toHaveAttribute('aria-sort', 'descending')
  })

  it('a click on "Orders" while it is already descending flips it to ascending', async () => {
    await renderLoaded()

    await userEvent.click(screen.getByRole('button', { name: 'Orders' }))

    expect(providerRows()).toEqual(['Dr. Gamma', 'Dr. Alpha', 'Dr. Beta'])
    expect(header('Orders')).toHaveAttribute('aria-sort', 'ascending')
  })

  it('"Failed" starts with the most failures first', async () => {
    await renderLoaded()

    await userEvent.click(screen.getByRole('button', { name: 'Failed' }))

    expect(providerRows()[0]).toBe('Dr. Alpha')
    expect(header('Failed')).toHaveAttribute('aria-sort', 'descending')
  })

  it('"Avg time" sorts by time and keeps the provider without data last in both directions', async () => {
    await renderLoaded()

    await userEvent.click(screen.getByRole('button', { name: 'Avg time' }))
    expect(providerRows()).toEqual(['Dr. Beta', 'Dr. Alpha', 'Dr. Gamma']) // slowest first, no data last

    await userEvent.click(screen.getByRole('button', { name: 'Avg time' }))
    expect(providerRows()).toEqual(['Dr. Alpha', 'Dr. Beta', 'Dr. Gamma']) // fastest first, no data still last
  })

  it('shows an arrow for the sorted column only (for sighted users; the state is also in aria-sort)', async () => {
    await renderLoaded()

    expect(within(header('Orders')).getByText('▼')).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: 'Orders' }))
    expect(within(header('Orders')).getByText('▲')).toBeInTheDocument()
    expect(within(header('Provider')).queryByText(/[▲▼]/)).not.toBeInTheDocument()
  })

  it('sorting does not call the backend again', async () => {
    await renderLoaded()

    await userEvent.click(screen.getByRole('button', { name: 'Provider' }))

    expect(getStats).toHaveBeenCalledTimes(1)
  })
})
