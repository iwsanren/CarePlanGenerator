import { describe, expect, it, vi } from 'vitest'
import { fireEvent, render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { EMPTY_FILTERS, type FilterErrors, type OrderFilters } from '@/utils/orderSearch'
import { OrderFilterPanel } from './OrderFilterPanel'

function renderPanel(filters: Partial<OrderFilters> = {}, errors: FilterErrors = {}) {
  const handlers = {
    onTextChange: vi.fn(),
    onStatusChange: vi.fn(),
    onDateChange: vi.fn(),
    onClear: vi.fn(),
  }
  render(<OrderFilterPanel filters={{ ...EMPTY_FILTERS, ...filters }} errors={errors} {...handlers} />)
  return handlers
}

describe('OrderFilterPanel: the boxes', () => {
  it('every box has a label that names it', () => {
    renderPanel()

    for (const label of ['Patient name', 'MRN', 'Medication', 'Provider name', 'Created from (UTC)', 'Created to (UTC)']) {
      expect(screen.getByLabelText(label)).toBeInTheDocument()
    }
  })

  it('the date labels say UTC, and a hint explains it', () => {
    renderPanel()

    expect(screen.getByText(/Whole UTC calendar days/)).toBeInTheDocument()
    expect(screen.getByLabelText('Created from (UTC)')).toHaveAccessibleDescription(/Whole UTC calendar days/)
    expect(screen.getByLabelText('Created to (UTC)')).toHaveAccessibleDescription(/Whole UTC calendar days/)
  })

  it('date boxes are date inputs limited to the range the backend accepts', () => {
    renderPanel()

    for (const label of ['Created from (UTC)', 'Created to (UTC)']) {
      const input = screen.getByLabelText(label)
      expect(input).toHaveAttribute('type', 'date')
      expect(input).toHaveAttribute('min', '1900-01-01')
      expect(input).toHaveAttribute('max', '2999-12-31')
    }
  })

  it('shows the values it is given', () => {
    renderPanel({
      patientName: 'Jane', mrn: '123456', medication: 'IVIG', providerName: 'Dr. Green',
      createdFrom: '2026-11-01', createdTo: '2026-11-30',
    })

    expect(screen.getByLabelText('Patient name')).toHaveValue('Jane')
    expect(screen.getByLabelText('MRN')).toHaveValue('123456')
    expect(screen.getByLabelText('Medication')).toHaveValue('IVIG')
    expect(screen.getByLabelText('Provider name')).toHaveValue('Dr. Green')
    expect(screen.getByLabelText('Created from (UTC)')).toHaveValue('2026-11-01')
    expect(screen.getByLabelText('Created to (UTC)')).toHaveValue('2026-11-30')
  })

  it.each([
    ['Patient name', 'patientName'],
    ['MRN', 'mrn'],
    ['Medication', 'medication'],
    ['Provider name', 'providerName'],
  ])('typing in "%s" reports the field and the new text', async (label, field) => {
    const { onTextChange } = renderPanel()

    await userEvent.type(screen.getByLabelText(label), 'x')

    expect(onTextChange).toHaveBeenCalledWith(field, 'x')
  })

  it.each([
    ['Created from (UTC)', 'createdFrom'],
    ['Created to (UTC)', 'createdTo'],
  ])('choosing a date in "%s" reports the field and the date', (label, field) => {
    const { onDateChange } = renderPanel()

    fireEvent.change(screen.getByLabelText(label), { target: { value: '2026-11-05' } })

    expect(onDateChange).toHaveBeenCalledWith(field, '2026-11-05')
  })

  it('the name and MRN boxes ask the browser not to remember what is typed (patient data)', () => {
    renderPanel()

    expect(screen.getByLabelText('Patient name')).toHaveAttribute('autocomplete', 'off')
    expect(screen.getByLabelText('MRN')).toHaveAttribute('autocomplete', 'off')
  })

  it('the MRN box brings up the number keyboard on a phone', () => {
    renderPanel()

    expect(screen.getByLabelText('MRN')).toHaveAttribute('inputmode', 'numeric')
    expect(screen.getByLabelText('Patient name')).not.toHaveAttribute('inputmode')
  })
})

describe('OrderFilterPanel: messages next to the boxes', () => {
  it('with no errors there are no alerts and no box is marked invalid', () => {
    renderPanel()

    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
    expect(screen.getByLabelText('MRN')).toHaveAttribute('aria-invalid', 'false')
  })

  it('a message appears next to its box, is announced, and is tied to the box', () => {
    renderPanel({ mrn: '12' }, { mrn: 'MRN must be exactly 6 digits' })

    const alert = screen.getByRole('alert')
    expect(alert).toHaveTextContent('MRN must be exactly 6 digits')
    const box = screen.getByLabelText('MRN')
    expect(box).toHaveAttribute('aria-invalid', 'true')
    expect(box).toHaveAccessibleDescription('MRN must be exactly 6 digits')
  })

  it('an error on a date box is tied to it together with the UTC hint', () => {
    renderPanel(
      { createdFrom: '2026-11-16', createdTo: '2026-11-15' },
      { createdTo: 'The end date must not be before the start date' },
    )

    const end = screen.getByLabelText('Created to (UTC)')
    expect(end).toHaveAttribute('aria-invalid', 'true')
    expect(end).toHaveAccessibleDescription(/The end date must not be before the start date.*Whole UTC/)
    expect(screen.getByLabelText('Created from (UTC)')).toHaveAttribute('aria-invalid', 'false')
  })

  it('a start-date error and the other text errors are shown at the same time, each by its box', () => {
    renderPanel({}, { createdFrom: 'bad date', patientName: 'too long', medication: 'also too long', providerName: 'and this' })

    expect(screen.getAllByRole('alert')).toHaveLength(4)
    expect(screen.getByLabelText('Created from (UTC)')).toHaveAccessibleDescription(/bad date/)
    expect(screen.getByLabelText('Provider name')).toHaveAccessibleDescription('and this')
  })
})

describe('OrderFilterPanel: status and clear', () => {
  it('has the status buttons, with the current status pressed', () => {
    renderPanel({ status: 'processing' })

    const group = screen.getByRole('group', { name: 'Status' })
    expect(within(group).getByRole('button', { name: 'Processing' })).toHaveAttribute('aria-pressed', 'true')
    expect(within(group).getByRole('button', { name: 'All' })).toHaveAttribute('aria-pressed', 'false')
  })

  it('a click on a status button reports it', async () => {
    const { onStatusChange } = renderPanel()

    await userEvent.click(screen.getByRole('button', { name: 'Failed' }))

    expect(onStatusChange).toHaveBeenCalledWith('failed')
  })

  it('"Clear all filters" is disabled when no filter is set', () => {
    renderPanel()

    expect(screen.getByRole('button', { name: 'Clear all filters' })).toBeDisabled()
  })

  it.each<[string, Partial<OrderFilters>]>([
    ['a status', { status: 'failed' }],
    ['a text', { medication: 'ivig' }],
    ['a date', { createdTo: '2026-11-01' }],
  ])('"Clear all filters" works when there is %s, and reports the click', async (_label, filters) => {
    const { onClear } = renderPanel(filters)
    const button = screen.getByRole('button', { name: 'Clear all filters' })

    expect(button).toBeEnabled()
    await userEvent.click(button)

    expect(onClear).toHaveBeenCalledTimes(1)
  })
})

describe('OrderFilterPanel: folding on a narrow screen', () => {
  it('has a "Filters" button that starts closed (aria-expanded false) and the panel is hidden on small screens', () => {
    renderPanel()

    const toggle = screen.getByRole('button', { name: 'Filters' })
    expect(toggle).toHaveAttribute('aria-expanded', 'false')
    expect(toggle).toHaveAttribute('aria-controls', 'order-filter-panel')
    // Tailwind: hidden below md, shown from md up. jsdom does not apply CSS, so only the classes can be checked.
    expect(document.getElementById('order-filter-panel')).toHaveClass('hidden', 'md:block')
  })

  it('a click opens it (aria-expanded true) and a second click closes it again', async () => {
    renderPanel()
    const toggle = screen.getByRole('button', { name: 'Filters' })
    const panel = document.getElementById('order-filter-panel')

    await userEvent.click(toggle)
    expect(toggle).toHaveAttribute('aria-expanded', 'true')
    expect(panel).toHaveClass('block')
    expect(panel).not.toHaveClass('hidden')

    await userEvent.click(toggle)
    expect(toggle).toHaveAttribute('aria-expanded', 'false')
    expect(panel).toHaveClass('hidden')
  })

  it('the button shows how many filters are set, so a closed panel is not a hidden filter', () => {
    renderPanel({ status: 'failed', medication: 'ivig', createdFrom: '2026-11-01' })

    expect(screen.getByRole('button', { name: 'Filters (3)' })).toBeInTheDocument()
  })

  it('the count follows the typed texts too', () => {
    renderPanel({ patientName: 'Jane' })

    expect(screen.getByRole('button', { name: 'Filters (1)' })).toBeInTheDocument()
  })
})
