import { createRef } from 'react'
import { describe, expect, it, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { Button } from './Button'
import { Input } from './Input'
import { Select } from './Select'
import { Textarea } from './Textarea'
import { FormField } from '../forms/FormField'

describe('Input, Select and Textarea', () => {
  it.each([
    ['Input', () => <Input aria-label="field" />],
    ['Select', () => <Select aria-label="field"><option>a</option></Select>],
    ['Textarea', () => <Textarea aria-label="field" />],
  ])('%s has the normal border by default and the red one with error', (_name, build) => {
    const { unmount } = render(build())
    expect(screen.getByLabelText('field')).toHaveClass('border-gray-300')
    expect(screen.getByLabelText('field')).not.toHaveClass('border-red-500')
    unmount()
  })

  it('Input switches to the red border with error', () => {
    render(<Input aria-label="field" error />)
    expect(screen.getByLabelText('field')).toHaveClass('border-red-500')
  })

  it('Select switches to the red border with error', () => {
    render(<Select aria-label="field" error><option>a</option></Select>)
    expect(screen.getByLabelText('field')).toHaveClass('border-red-500')
  })

  it('Textarea switches to the red border with error', () => {
    render(<Textarea aria-label="field" error />)
    expect(screen.getByLabelText('field')).toHaveClass('border-red-500')
  })

  it('forwards a ref to the underlying element (react-hook-form register depends on this)', () => {
    const ref = createRef<HTMLInputElement>()
    render(<Input ref={ref} aria-label="field" />)

    expect(ref.current).toBe(screen.getByLabelText('field'))
  })

  it('lets a caller add classes and attributes', () => {
    render(<Input aria-label="field" className="extra" maxLength={4} placeholder="p" />)

    expect(screen.getByLabelText('field')).toHaveClass('extra')
    expect(screen.getByLabelText('field')).toHaveAttribute('maxlength', '4')
  })
})

describe('Button', () => {
  it('calls onClick, and not when disabled', async () => {
    const onClick = vi.fn()
    const { rerender } = render(<Button onClick={onClick}>Go</Button>)
    await userEvent.click(screen.getByRole('button', { name: 'Go' }))
    expect(onClick).toHaveBeenCalledTimes(1)

    rerender(<Button onClick={onClick} disabled>Go</Button>)
    await userEvent.click(screen.getByRole('button', { name: 'Go' }))
    expect(onClick).toHaveBeenCalledTimes(1)
  })
})

describe('FormField', () => {
  it('shows the label, a required marker and an error message when given', () => {
    render(<FormField label="MRN" required error="MRN must be exactly 6 digits"><input /></FormField>)

    expect(screen.getByText('MRN')).toBeInTheDocument()
    expect(screen.getByText('*')).toBeInTheDocument()
    expect(screen.getByText('MRN must be exactly 6 digits')).toBeInTheDocument()
  })

  it('shows neither marker nor error by default', () => {
    render(<FormField label="Notes"><input /></FormField>)

    expect(screen.queryByText('*')).not.toBeInTheDocument()
    expect(document.querySelector('p')).toBeNull()
  })
})
