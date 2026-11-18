import { describe, expect, it, vi } from 'vitest'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom'
import { makeListItem } from '@/test/fixtures'
import type { OrderListItem } from '@/types'
import type { OrderSort } from '@/utils/orderSearch'
import { OrdersTable } from './OrdersTable'

function Where() {
  return <div data-testid="where">{useLocation().pathname}</div>
}

function renderTable(orders: OrderListItem[], sort: OrderSort | null = null, dimmed = false, onSort = vi.fn()) {
  const view = render(
    <MemoryRouter initialEntries={['/orders']}>
      <Routes>
        <Route path="/orders" element={<OrdersTable orders={orders} sort={sort} onSort={onSort} dimmed={dimmed} />} />
        <Route path="*" element={<Where />} />
      </Routes>
    </MemoryRouter>,
  )
  return { onSort, ...view }
}

describe('OrdersTable: headers', () => {
  it('the five sortable columns are buttons, "Care Plan" is a plain header', () => {
    renderTable([makeListItem()])

    for (const name of ['Patient', 'Provider', 'Medication', 'Status', 'Created']) {
      expect(within(screen.getByRole('columnheader', { name })).getByRole('button')).toBeInTheDocument()
    }
    const carePlan = screen.getByRole('columnheader', { name: 'Care Plan' })
    expect(within(carePlan).queryByRole('button')).not.toBeInTheDocument()
    expect(carePlan).not.toHaveAttribute('aria-sort')
  })

  it('each header sorts by its own column', async () => {
    const { onSort } = renderTable([makeListItem()])

    for (const [name] of [['Patient'], ['Provider'], ['Medication'], ['Status'], ['Created']]) {
      await userEvent.click(within(screen.getByRole('columnheader', { name })).getByRole('button'))
    }

    expect(onSort.mock.calls.map(([column]) => column))
      .toEqual(['patient_name', 'provider_name', 'medication', 'status', 'created_at'])
  })

  it('says in words how the list is ordered', () => {
    const { rerender } = renderTable([makeListItem()])
    expect(screen.getByText('Sorted by: newest first (default)')).toBeInTheDocument()

    rerender(
      <MemoryRouter>
        <OrdersTable orders={[makeListItem()]} sort={{ column: 'status', direction: 'desc' }} onSort={vi.fn()} dimmed={false} />
      </MemoryRouter>,
    )
    expect(screen.getByText('Sorted by: Status (descending)')).toBeInTheDocument()
    expect(screen.getByRole('columnheader', { name: 'Status' })).toHaveAttribute('aria-sort', 'descending')
  })
})

describe('OrdersTable: rows', () => {
  it('shows one row per order with the patient, MRN, provider, medication, status and care plan', () => {
    renderTable([makeListItem({
      id: 7, patientName: 'Jane Doe', patientMrn: '111111', providerName: 'Dr. Smith', medicationName: 'IVIG',
      status: 'processing',
    })])

    const row = within(screen.getAllByRole('row')[1])
    expect(row.getByText('Jane Doe')).toBeInTheDocument()
    expect(row.getByText('MRN: 111111')).toBeInTheDocument()
    expect(row.getByText('Dr. Smith')).toBeInTheDocument()
    expect(row.getByText('IVIG')).toBeInTheDocument()
    expect(row.getByText('processing')).toBeInTheDocument() // the status is written, not only coloured
    expect(row.getByText('Generating…')).toBeInTheDocument()
  })

  it.each([
    ['completed', '✓ Ready'],
    ['failed', 'Failed'],
    ['pending', 'Pending'],
  ] as const)('the care plan column for "%s" reads "%s"', (status, label) => {
    renderTable([makeListItem({ status })])

    expect(within(screen.getAllByRole('row')[1]).getByText(label)).toBeInTheDocument()
  })

  it('a click on the row, or on the patient link, opens the order', async () => {
    renderTable([makeListItem({ id: 42, patientName: 'Jane Doe', medicationName: 'IVIG' })])

    await userEvent.click(screen.getByText('IVIG'))
    expect(screen.getByTestId('where')).toHaveTextContent('/orders/42')
  })

  it('the patient link goes to the order too', async () => {
    renderTable([makeListItem({ id: 9, patientName: 'Jane Doe' })])

    expect(screen.getByRole('link', { name: 'Jane Doe' })).toHaveAttribute('href', '/orders/9')
    await userEvent.click(screen.getByRole('link', { name: 'Jane Doe' }))
    expect(screen.getByTestId('where')).toHaveTextContent('/orders/9')
  })

  describe('the order remembers the list it was opened from', () => {
    function SavedQuery() {
      const state = useLocation().state as { ordersSearch?: string } | null
      return <div data-testid="saved">{state === null ? 'no state' : String(state.ordersSearch)}</div>
    }

    function renderFrom(route: string) {
      render(
        <MemoryRouter initialEntries={[route]}>
          <Routes>
            <Route
              path="/orders"
              element={<OrdersTable orders={[makeListItem({ id: 5, patientName: 'Jane Doe' })]} sort={null} onSort={vi.fn()} dimmed={false} />}
            />
            <Route path="/orders/:id" element={<SavedQuery />} />
          </Routes>
        </MemoryRouter>,
      )
    }

    it('a click on the row passes the list\'s query on', async () => {
      renderFrom('/orders?status=failed&page=2')

      await userEvent.click(screen.getByText('IVIG'))

      expect(screen.getByTestId('saved')).toHaveTextContent('?status=failed&page=2')
    })

    it('a click on the patient link passes it on too', async () => {
      renderFrom('/orders?medication=ivig&sort=status%2Casc')

      await userEvent.click(screen.getByRole('link', { name: 'Jane Doe' }))

      expect(screen.getByTestId('saved')).toHaveTextContent('?medication=ivig&sort=status%2Casc')
    })

    it('a list without parameters passes an empty query', async () => {
      renderFrom('/orders')

      await userEvent.click(screen.getByRole('link', { name: 'Jane Doe' }))

      expect(screen.getByTestId('saved').textContent).toBe('')
    })
  })

  it('the table scrolls sideways on a narrow screen instead of being cut off', () => {
    const { container } = renderTable([makeListItem()])

    expect(container.querySelector('.overflow-x-auto')).toContainElement(screen.getByRole('table'))
  })

  it('is dimmed only while it shows the previous search', () => {
    const idle = renderTable([makeListItem()], null, false)
    expect(idle.container.querySelector('.opacity-50')).not.toBeInTheDocument()
    idle.unmount()

    const busy = renderTable([makeListItem()], null, true)
    expect(busy.container.querySelector('.opacity-50')).toBeInTheDocument()
  })
})
