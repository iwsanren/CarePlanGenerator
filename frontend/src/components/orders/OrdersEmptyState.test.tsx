import { describe, expect, it, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { OrdersEmptyState, type EmptyKind } from './OrdersEmptyState'

function renderEmpty(kind: EmptyKind) {
  const onClearFilters = vi.fn()
  const onFirstPage = vi.fn()
  render(
    <MemoryRouter>
      <OrdersEmptyState kind={kind} onClearFilters={onClearFilters} onFirstPage={onFirstPage} />
    </MemoryRouter>,
  )
  return { onClearFilters, onFirstPage }
}

describe('OrdersEmptyState', () => {
  it('"none": there are no orders at all -> "No orders yet" and a link to the new-order form', () => {
    renderEmpty('none')

    expect(screen.getByText('No orders yet')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Create your first order' })).toHaveAttribute('href', '/')
    expect(screen.queryByRole('button')).not.toBeInTheDocument()
  })

  it('"no-match": the filters exclude everything -> says so and offers "Clear filters"', async () => {
    const { onClearFilters, onFirstPage } = renderEmpty('no-match')

    expect(screen.getByText('No orders match your filters')).toBeInTheDocument()
    expect(screen.queryByText('No orders yet')).not.toBeInTheDocument()
    expect(screen.queryByRole('link')).not.toBeInTheDocument()

    await userEvent.click(screen.getByRole('button', { name: 'Clear filters' }))

    expect(onClearFilters).toHaveBeenCalledTimes(1)
    expect(onFirstPage).not.toHaveBeenCalled()
  })

  it('"empty-page": orders match but this page is past the end -> "Go to the first page"', async () => {
    const { onClearFilters, onFirstPage } = renderEmpty('empty-page')

    expect(screen.getByText('There are no results on this page.')).toBeInTheDocument()
    expect(screen.queryByText('No orders yet')).not.toBeInTheDocument()

    await userEvent.click(screen.getByRole('button', { name: 'Go to the first page' }))

    expect(onFirstPage).toHaveBeenCalledTimes(1)
    expect(onClearFilters).not.toHaveBeenCalled()
  })
})
