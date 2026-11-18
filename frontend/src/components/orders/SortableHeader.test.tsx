import { describe, expect, it, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import type { OrderSort } from '@/utils/orderSearch'
import { SortableHeader } from './SortableHeader'

function renderHeader(sort: OrderSort | null, onSort = vi.fn()) {
  render(
    <table>
      <thead>
        <tr>
          <SortableHeader label="Patient" column="patient_name" sort={sort} onSort={onSort} />
          <SortableHeader label="Created" column="created_at" sort={sort} onSort={onSort} />
        </tr>
      </thead>
    </table>,
  )
  return onSort
}

describe('SortableHeader', () => {
  it('with the default order, no column is sorted: aria-sort is "none" everywhere', () => {
    renderHeader(null)

    expect(screen.getByRole('columnheader', { name: 'Patient' })).toHaveAttribute('aria-sort', 'none')
    expect(screen.getByRole('columnheader', { name: 'Created' })).toHaveAttribute('aria-sort', 'none')
  })

  it('the sorted column says ascending, and only that column', () => {
    renderHeader({ column: 'patient_name', direction: 'asc' })

    expect(screen.getByRole('columnheader', { name: 'Patient' })).toHaveAttribute('aria-sort', 'ascending')
    expect(screen.getByRole('columnheader', { name: 'Created' })).toHaveAttribute('aria-sort', 'none')
  })

  it('the sorted column says descending', () => {
    renderHeader({ column: 'created_at', direction: 'desc' })

    expect(screen.getByRole('columnheader', { name: 'Created' })).toHaveAttribute('aria-sort', 'descending')
    expect(screen.getByRole('columnheader', { name: 'Patient' })).toHaveAttribute('aria-sort', 'none')
  })

  it('shows an arrow for each state, but the arrow is not part of the name', () => {
    const { unmount } = render(
      <table><thead><tr>
        <SortableHeader label="Patient" column="patient_name" sort={null} onSort={vi.fn()} />
      </tr></thead></table>,
    )
    expect(screen.getByText('↕')).toHaveAttribute('aria-hidden', 'true')
    unmount()

    for (const [direction, arrow] of [['asc', '▲'], ['desc', '▼']] as const) {
      const view = render(
        <table><thead><tr>
          <SortableHeader label="Patient" column="patient_name" sort={{ column: 'patient_name', direction }} onSort={vi.fn()} />
        </tr></thead></table>,
      )
      expect(screen.getByText(arrow)).toHaveAttribute('aria-hidden', 'true')
      expect(screen.getByRole('columnheader', { name: 'Patient' })).toBeInTheDocument() // no arrow in the name
      view.unmount()
    }
  })

  it('a click on the button asks for that column to be sorted', async () => {
    const onSort = renderHeader(null)

    await userEvent.click(screen.getByRole('button', { name: 'Created' }))

    expect(onSort).toHaveBeenCalledTimes(1)
    expect(onSort).toHaveBeenCalledWith('created_at')
  })

  it('can be used from the keyboard', async () => {
    const onSort = renderHeader(null)

    screen.getByRole('button', { name: 'Patient' }).focus()
    await userEvent.keyboard('{Enter}')

    expect(onSort).toHaveBeenCalledWith('patient_name')
  })
})
