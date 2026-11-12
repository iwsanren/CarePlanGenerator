import { beforeEach, describe, expect, it, vi } from 'vitest'
import { fireEvent, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { orderService } from '@/services/orderService'
import { renderPage } from '@/test/utils'
import { makeApiError, makeOrder } from '@/test/fixtures'
import { NewOrderPage } from './NewOrderPage'

// The page uses the real react-hook-form, zod resolver and useCreateOrder hook;
// only the HTTP boundary (orderService) is replaced. The page never touches auth.
vi.mock('@/services/orderService', () => ({ orderService: { createOrder: vi.fn() } }))
const createOrder = vi.mocked(orderService.createOrder)

// delay: null removes the artificial pause between keystrokes (a full form would take ~1s per test).
const user = userEvent.setup({ delay: null })

function renderForm() {
  return renderPage(<NewOrderPage />, { route: '/', path: '/' })
}

/** FormField's <label> is not linked to its input, so inputs are located by their name attribute. */
function field(container: HTMLElement, name: string) {
  const element = container.querySelector<HTMLInputElement | HTMLTextAreaElement | HTMLSelectElement>(`[name="${name}"]`)
  if (!element) throw new Error(`No form control named ${name}`)
  return element
}

async function fillRequired(container: HTMLElement) {
  await user.type(field(container, 'patientFirstName'), 'Jane')
  await user.type(field(container, 'patientLastName'), 'Doe')
  await user.type(field(container, 'patientMrn'), '123456')
  await user.type(field(container, 'providerName'), 'Dr. Smith')
  await user.type(field(container, 'providerNpi'), '1234567890')
  await user.type(field(container, 'medicationName'), 'IVIG')
  await user.type(field(container, 'primaryDiagnosis'), 'G70.00')
}

const submitButton = () => screen.getByRole('button', { name: 'Create Order' })

const duplicateWarning = makeApiError('Please confirm', {
  type: 'warning',
  code: 'CONFIRMATION_REQUIRED',
  detail: {
    requiresConfirm: true,
    warnings: [
      { code: 'DUP_PATIENT', message: 'A patient with this MRN already exists', actionRequired: true },
      { code: 'INFO', message: 'Provider was matched by NPI', actionRequired: false },
    ],
  },
})

beforeEach(() => {
  vi.resetAllMocks()
})

describe('NewOrderPage layout', () => {
  it('shows the form sections and the submit button', () => {
    renderForm()

    expect(screen.getByRole('heading', { name: 'New Order' })).toBeInTheDocument()
    for (const section of ['Patient Information', 'Referring Provider', 'Order Details']) {
      expect(screen.getByRole('heading', { name: section })).toBeInTheDocument()
    }
    expect(submitButton()).toBeInTheDocument()
    expect(screen.queryByText('This order cannot be created')).not.toBeInTheDocument()
  })

  it('limits MRN to 6 characters and NPI to 10 characters while typing', async () => {
    const { container } = renderForm()

    await user.type(field(container, 'patientMrn'), '12345678')
    await user.type(field(container, 'providerNpi'), '123456789012')

    expect(field(container, 'patientMrn')).toHaveValue('123456')
    expect(field(container, 'providerNpi')).toHaveValue('1234567890')
  })
})

describe('NewOrderPage validation', () => {
  it('blocks an empty submit, lists every required field and never calls the server', async () => {
    renderForm()

    await user.click(submitButton())

    for (const message of [
      'First name is required',
      'Last name is required',
      'MRN must be exactly 6 digits',
      'Provider name is required',
      'NPI must be exactly 10 digits',
      'Medication name is required',
      'Must be a valid ICD-10 code (e.g. E11.9)',
    ]) {
      expect(await screen.findByText(message)).toBeInTheDocument()
    }
    expect(createOrder).not.toHaveBeenCalled()
  })

  it('validates a field as soon as the user leaves it (onBlur), before any submit', async () => {
    const { container } = renderForm()

    await user.type(field(container, 'patientMrn'), '123')
    expect(screen.queryByText('MRN must be exactly 6 digits')).not.toBeInTheDocument() // still typing
    await user.tab()

    expect(await screen.findByText('MRN must be exactly 6 digits')).toBeInTheDocument()
  })

  it('clears the error once the value is corrected and the field is left again', async () => {
    const { container } = renderForm()
    await user.type(field(container, 'patientMrn'), '123')
    await user.tab()
    await screen.findByText('MRN must be exactly 6 digits')

    await user.type(field(container, 'patientMrn'), '456')
    // react-hook-form's re-validate-on-change only starts after the first submit, so
    // before any submit the old error stays until the field is blurred again.
    expect(screen.getByText('MRN must be exactly 6 digits')).toBeInTheDocument()
    await user.tab()

    await waitFor(() => expect(screen.queryByText('MRN must be exactly 6 digits')).not.toBeInTheDocument())
  })

  it('after a failed submit, re-validates on every change so the error disappears while typing', async () => {
    const { container } = renderForm()
    await user.click(submitButton())
    await screen.findByText('MRN must be exactly 6 digits')

    await user.type(field(container, 'patientMrn'), '123456')

    await waitFor(() => expect(screen.queryByText('MRN must be exactly 6 digits')).not.toBeInTheDocument())
  })

  it('rejects a malformed NPI and a reserved ICD-10 category on blur', async () => {
    const { container } = renderForm()

    await user.type(field(container, 'providerNpi'), '12345')
    await user.tab()
    await user.type(field(container, 'primaryDiagnosis'), 'U07.1')
    await user.tab()

    expect(await screen.findByText('NPI must be exactly 10 digits')).toBeInTheDocument()
    expect(await screen.findByText('Must be a valid ICD-10 code (e.g. E11.9)')).toBeInTheDocument()
  })

  it('rejects a date of birth in the future', async () => {
    const { container } = renderForm()
    await fillRequired(container)

    fireEvent.change(field(container, 'patientDateOfBirth'), { target: { value: '2999-01-01' } })
    await user.click(submitButton())

    expect(await screen.findByText('Date of birth cannot be in the future')).toBeInTheDocument()
    expect(createOrder).not.toHaveBeenCalled()
  })

  it.each(['0', '501'])('rejects a weight of %s kg', async (weight) => {
    const { container } = renderForm()
    await fillRequired(container)

    await user.type(field(container, 'patientWeightKg'), weight)
    await user.click(submitButton())

    await waitFor(() => expect(container.querySelector('p.text-red-500')).toBeInTheDocument())
    expect(createOrder).not.toHaveBeenCalled()
  })

  it('shows a green tick for a valid NPI and a red cross for an invalid one', async () => {
    const { container } = renderForm()
    const icons = () => within(field(container, 'providerNpi').parentElement as HTMLElement)

    expect(icons().queryByRole('img', { hidden: true })).not.toBeInTheDocument() // untouched: no icon
    await user.type(field(container, 'providerNpi'), '12345')
    await user.tab()
    await waitFor(() => expect(field(container, 'providerNpi').parentElement?.querySelector('svg.text-red-500')).toBeInTheDocument())

    await user.type(field(container, 'providerNpi'), '67890')
    await waitFor(() => expect(field(container, 'providerNpi').parentElement?.querySelector('svg.text-green-500')).toBeInTheDocument())
  })

  it('shows a green tick for a valid ICD-10 code while typing and a red cross for a bad one', async () => {
    const { container } = renderForm()
    const wrapper = () => field(container, 'primaryDiagnosis').parentElement as HTMLElement

    await user.type(field(container, 'primaryDiagnosis'), 'X')
    expect(wrapper().querySelector('svg.text-red-500')).toBeInTheDocument()

    await user.clear(field(container, 'primaryDiagnosis'))
    await user.type(field(container, 'primaryDiagnosis'), 'E11.9')
    expect(wrapper().querySelector('svg.text-green-500')).toBeInTheDocument()
  })
})

describe('NewOrderPage submit', () => {
  it('sends the minimal order with confirm:false and opens the created order', async () => {
    createOrder.mockResolvedValue(makeOrder({ id: 9 }))
    const { container } = renderForm()
    await fillRequired(container)

    await user.click(submitButton())

    await waitFor(() => expect(createOrder).toHaveBeenCalledTimes(1))
    expect(createOrder).toHaveBeenCalledWith({
      patientFirstName: 'Jane',
      patientLastName: 'Doe',
      patientMrn: '123456',
      providerName: 'Dr. Smith',
      providerNpi: '1234567890',
      medicationName: 'IVIG',
      primaryDiagnosis: 'G70.00',
      additionalDiagnoses: [],
      medicationHistory: [],
      confirm: false,
    })
    // Empty optional fields must be absent (undefined), not empty strings / 0.
    const sent = createOrder.mock.calls[0][0]
    for (const optional of ['patientDateOfBirth', 'patientSex', 'patientWeightKg', 'patientAllergies', 'patientRecords']) {
      expect(sent[optional as keyof typeof sent]).toBeUndefined()
    }
    expect(await screen.findByTestId('location')).toHaveTextContent('/orders/9')
  })

  it('sends every optional field in its API shape', async () => {
    createOrder.mockResolvedValue(makeOrder({ id: 9 }))
    const { container } = renderForm()
    await fillRequired(container)
    fireEvent.change(field(container, 'patientDateOfBirth'), { target: { value: '1980-01-31' } })
    await user.selectOptions(field(container, 'patientSex'), 'Female')
    await user.type(field(container, 'patientWeightKg'), '70.5')
    await user.type(field(container, 'patientAllergies'), 'Penicillin')
    await user.type(field(container, 'additionalDiagnosesText'), 'I10{Enter}  N18.3  {Enter}{Enter}E11.9')
    await user.type(field(container, 'medicationHistoryText'), 'Metformin{Enter}Lisinopril')
    await user.type(field(container, 'patientRecords'), 'Stable on current therapy')

    await user.click(submitButton())

    await waitFor(() => expect(createOrder).toHaveBeenCalledTimes(1))
    expect(createOrder).toHaveBeenCalledWith({
      patientFirstName: 'Jane',
      patientLastName: 'Doe',
      patientMrn: '123456',
      patientDateOfBirth: '1980-01-31',
      patientSex: 'Female',
      patientWeightKg: 70.5, // a real number, not the string "70.5"
      patientAllergies: 'Penicillin',
      providerName: 'Dr. Smith',
      providerNpi: '1234567890',
      medicationName: 'IVIG',
      primaryDiagnosis: 'G70.00',
      additionalDiagnoses: ['I10', 'N18.3', 'E11.9'], // trimmed, blank lines dropped
      medicationHistory: ['Metformin', 'Lisinopril'],
      patientRecords: 'Stable on current therapy',
      confirm: false,
    })
  })

  it('shows a generic server error inline, without a modal, and stays on the form', async () => {
    createOrder.mockRejectedValue(makeApiError('Server exploded', { status: 500 }))
    const { container } = renderForm()
    await fillRequired(container)

    await user.click(submitButton())

    expect(await screen.findByRole('alert')).toHaveTextContent('Server exploded')
    expect(screen.queryByText('Possible duplicate detected')).not.toBeInTheDocument()
    expect(screen.queryByText('This order cannot be created')).not.toBeInTheDocument()
    expect(screen.queryByTestId('location')).not.toBeInTheDocument()
  })
})

describe('NewOrderPage duplicate handling', () => {
  it('shows the warnings and lets the user confirm; the retry carries confirm:true', async () => {
    createOrder.mockRejectedValueOnce(duplicateWarning).mockResolvedValueOnce(makeOrder({ id: 11 }))
    const { container } = renderForm()
    await fillRequired(container)

    await user.click(submitButton())

    expect(await screen.findByText('Possible duplicate detected')).toBeInTheDocument()
    expect(screen.getByText('A patient with this MRN already exists')).toBeInTheDocument()
    expect(screen.getByText('Provider was matched by NPI')).toBeInTheDocument()
    expect(screen.queryByRole('alert')).not.toBeInTheDocument() // handled by the modal, not the inline banner
    expect(createOrder).toHaveBeenCalledTimes(1)
    expect(createOrder.mock.calls[0][0].confirm).toBe(false)

    await user.click(screen.getByRole('button', { name: 'Continue Anyway' }))

    await waitFor(() => expect(createOrder).toHaveBeenCalledTimes(2))
    expect(createOrder.mock.calls[1][0]).toEqual({ ...createOrder.mock.calls[0][0], confirm: true })
    expect(await screen.findByTestId('location')).toHaveTextContent('/orders/11')
  })

  it('Cancel closes the warning without re-sending and keeps what the user typed', async () => {
    createOrder.mockRejectedValue(duplicateWarning)
    const { container } = renderForm()
    await fillRequired(container)
    await user.click(submitButton())
    await screen.findByText('Possible duplicate detected')

    await user.click(screen.getByRole('button', { name: 'Cancel' }))

    expect(screen.queryByText('Possible duplicate detected')).not.toBeInTheDocument()
    expect(createOrder).toHaveBeenCalledTimes(1)
    expect(field(container, 'patientMrn')).toHaveValue('123456')
  })

  it('shows a hard block with only a Close button (no way to continue)', async () => {
    createOrder.mockRejectedValue(
      makeApiError('This patient already has this order within 30 days', { type: 'block', code: 'DUPLICATE_ORDER', status: 409 }),
    )
    const { container } = renderForm()
    await fillRequired(container)

    await user.click(submitButton())

    expect(await screen.findByText('This order cannot be created')).toBeInTheDocument()
    expect(screen.getByText('This patient already has this order within 30 days')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Close' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Continue Anyway' })).not.toBeInTheDocument()
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: 'Close' }))
    expect(screen.queryByText('This order cannot be created')).not.toBeInTheDocument()
  })

  it('does not re-send a blocked order when the modal is closed', async () => {
    createOrder.mockRejectedValue(makeApiError('Duplicate', { type: 'block' }))
    const { container } = renderForm()
    await fillRequired(container)
    await user.click(submitButton())
    await user.click(await screen.findByRole('button', { name: 'Close' }))

    expect(createOrder).toHaveBeenCalledTimes(1)
  })
})
