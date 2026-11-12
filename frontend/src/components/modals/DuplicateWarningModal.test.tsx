import { describe, expect, it, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { DuplicateWarningModal } from './DuplicateWarningModal'

const warnings = [
  { code: 'A', message: 'Action required warning', actionRequired: true },
  { code: 'B', message: 'Informational warning', actionRequired: false },
]

describe('DuplicateWarningModal', () => {
  it('renders nothing while closed', () => {
    const { container } = render(<DuplicateWarningModal open={false} warnings={warnings} onClose={vi.fn()} />)

    expect(container).toBeEmptyDOMElement()
  })

  describe('confirmable variant (warnings)', () => {
    it('lists each warning, coloured by whether action is required', () => {
      render(<DuplicateWarningModal open warnings={warnings} onClose={vi.fn()} onConfirm={vi.fn()} />)

      expect(screen.getByRole('heading', { name: 'Possible duplicate detected' })).toBeInTheDocument()
      expect(screen.getByText('Action required warning')).toHaveClass('bg-amber-50')
      expect(screen.getByText('Informational warning')).toHaveClass('bg-blue-50')
    })

    it('Cancel calls onClose, Continue Anyway calls onConfirm', async () => {
      const onClose = vi.fn()
      const onConfirm = vi.fn()
      render(<DuplicateWarningModal open warnings={warnings} onClose={onClose} onConfirm={onConfirm} />)

      await userEvent.click(screen.getByRole('button', { name: 'Cancel' }))
      expect(onClose).toHaveBeenCalledTimes(1)
      expect(onConfirm).not.toHaveBeenCalled()

      await userEvent.click(screen.getByRole('button', { name: 'Continue Anyway' }))
      expect(onConfirm).toHaveBeenCalledTimes(1)
    })

    it('tolerates a missing warnings list (empty list, still confirmable)', () => {
      render(<DuplicateWarningModal open warnings={null} onClose={vi.fn()} />)

      expect(screen.queryAllByRole('listitem')).toHaveLength(0)
      expect(screen.getByRole('button', { name: 'Continue Anyway' })).toBeInTheDocument()
    })
  })

  describe('blocking variant (blockingMessage)', () => {
    it('shows the message and only a Close button', async () => {
      const onClose = vi.fn()
      render(<DuplicateWarningModal open blockingMessage="Duplicate within 30 days" warnings={warnings} onClose={onClose} />)

      expect(screen.getByRole('heading', { name: 'This order cannot be created' })).toBeInTheDocument()
      expect(screen.getByText('Duplicate within 30 days')).toBeInTheDocument()
      expect(screen.queryByText('Action required warning')).not.toBeInTheDocument() // block wins over warnings
      expect(screen.queryByRole('button', { name: 'Continue Anyway' })).not.toBeInTheDocument()
      expect(screen.queryByRole('button', { name: 'Cancel' })).not.toBeInTheDocument()

      await userEvent.click(screen.getByRole('button', { name: 'Close' }))
      expect(onClose).toHaveBeenCalledTimes(1)
    })

    it('treats an empty blockingMessage as "not blocking"', () => {
      render(<DuplicateWarningModal open blockingMessage="" warnings={warnings} onClose={vi.fn()} />)

      expect(screen.getByRole('heading', { name: 'Possible duplicate detected' })).toBeInTheDocument()
    })
  })
})
