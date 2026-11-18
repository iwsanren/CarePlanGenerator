import { describe, expect, it, vi } from 'vitest'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import type { StatusFilter as StatusValue } from '@/utils/orderSearch'
import { StatusFilter } from './StatusFilter'

function renderFilter(value: StatusValue, onChange = vi.fn()) {
  render(<StatusFilter value={value} onChange={onChange} />)
  return { onChange, group: screen.getByRole('group', { name: 'Status' }) }
}

describe('StatusFilter', () => {
  it('has the five buttons in the agreed order', () => {
    const { group } = renderFilter('all')

    expect(within(group).getAllByRole('button').map((button) => button.textContent?.replace('✓ ', '')))
      .toEqual(['All', 'Completed', 'Pending', 'Processing', 'Failed'])
  })

  it.each<StatusValue>(['all', 'completed', 'pending', 'processing', 'failed'])(
    'when "%s" is chosen, only that button is pressed',
    (value) => {
      const { group } = renderFilter(value)

      const pressed = within(group).getAllByRole('button', { pressed: true })

      expect(pressed).toHaveLength(1)
      expect(pressed[0].textContent).toContain(value === 'all' ? 'All' : value[0].toUpperCase() + value.slice(1))
    },
  )

  it('the chosen button also has a check mark, so colour is not the only cue; the others do not', () => {
    const { group } = renderFilter('failed')

    expect(within(group).getByRole('button', { name: 'Failed' }).textContent).toContain('✓')
    expect(within(group).getByRole('button', { name: 'All' }).textContent).not.toContain('✓')
    expect(within(group).getByRole('button', { name: 'Failed' }).querySelector('[aria-hidden="true"]'))
      .toHaveTextContent('✓') // the mark is decoration; the name stays "Failed"
  })

  it('a click reports the status that was clicked', async () => {
    const { onChange, group } = renderFilter('all')

    await userEvent.click(within(group).getByRole('button', { name: 'Processing' }))
    await userEvent.click(within(group).getByRole('button', { name: 'All' }))

    expect(onChange).toHaveBeenNthCalledWith(1, 'processing')
    expect(onChange).toHaveBeenNthCalledWith(2, 'all')
  })
})
