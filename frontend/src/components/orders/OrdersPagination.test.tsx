import { describe, expect, it, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { OrdersPagination } from './OrdersPagination'

function renderPagination(props: Partial<React.ComponentProps<typeof OrdersPagination>> = {}) {
  const onPageChange = vi.fn()
  const { container } = render(
    <OrdersPagination page={1} totalPages={1} count={5} loading={false} onPageChange={onPageChange} {...props} />,
  )
  return { onPageChange, container }
}

describe('OrdersPagination: the total', () => {
  it('says how many results there are, in a polite live region', () => {
    renderPagination({ count: 45, totalPages: 3 })

    expect(screen.getByRole('status')).toHaveTextContent('45 results')
  })

  it('one result is singular, none is "0 results"', () => {
    const { container } = renderPagination({ count: 1 })
    expect(screen.getByRole('status')).toHaveTextContent('1 result')
    expect(screen.getByRole('status')).not.toHaveTextContent('1 results')
    container.remove()
  })

  it('shows the total even when everything fits on one page, and then has no page controls', () => {
    renderPagination({ count: 5, totalPages: 1 })

    expect(screen.getByRole('status')).toHaveTextContent('5 results')
    expect(screen.queryByText(/Page \d+ of/)).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Next' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Previous' })).not.toBeInTheDocument()
  })
})

describe('OrdersPagination: several pages', () => {
  it('shows the position and the total', () => {
    renderPagination({ page: 2, totalPages: 3, count: 45 })

    expect(screen.getByText('Page 2 of 3 · 45 orders')).toBeInTheDocument()
  })

  it('Previous is disabled on the first page, Next on the last', () => {
    const { container } = renderPagination({ page: 1, totalPages: 3, count: 45 })
    expect(screen.getByRole('button', { name: 'Previous' })).toBeDisabled()
    expect(screen.getByRole('button', { name: 'Next' })).toBeEnabled()
    container.remove()
  })

  it('Next is disabled on the last page', () => {
    renderPagination({ page: 3, totalPages: 3, count: 45 })

    expect(screen.getByRole('button', { name: 'Next' })).toBeDisabled()
    expect(screen.getByRole('button', { name: 'Previous' })).toBeEnabled()
  })

  it('Next and Previous ask for the page after and before', async () => {
    const { onPageChange } = renderPagination({ page: 2, totalPages: 3, count: 45 })

    await userEvent.click(screen.getByRole('button', { name: 'Next' }))
    await userEvent.click(screen.getByRole('button', { name: 'Previous' }))

    expect(onPageChange).toHaveBeenNthCalledWith(1, 3)
    expect(onPageChange).toHaveBeenNthCalledWith(2, 1)
  })

  it('shows a spinner only while something is loading', () => {
    const idle = renderPagination({ page: 1, totalPages: 3, count: 45, loading: false })
    expect(idle.container.querySelector('.animate-spin')).not.toBeInTheDocument()
    idle.container.remove()

    const busy = renderPagination({ page: 1, totalPages: 3, count: 45, loading: true })
    expect(busy.container.querySelector('.animate-spin')).toBeInTheDocument()
  })
})
