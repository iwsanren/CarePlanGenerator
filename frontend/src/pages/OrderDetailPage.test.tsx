import { beforeEach, describe, expect, it, vi } from 'vitest'
import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { orderService } from '@/services/orderService'
import { renderPage } from '@/test/utils'
import { makeApiError, makeOrder } from '@/test/fixtures'
import type { OrderResponse } from '@/types'
import { OrderDetailPage } from './OrderDetailPage'

// Mock boundaries (see OrdersPage.test.tsx for the reasoning): '@/auth/auth' is replaced
// by a stub so keycloak-js is never loaded, and orderService is the HTTP boundary.
// The page, its hooks and react-query run for real.
const auth = vi.hoisted(() => ({ state: { pharmacist: false }, useAuth: vi.fn() }))
vi.mock('@/auth/auth', () => ({ useAuth: auth.useAuth }))
vi.mock('@/services/orderService', () => ({
  orderService: {
    getOrder: vi.fn(),
    getCarePlanStatus: vi.fn(),
    regenerateCarePlan: vi.fn(),
    uploadCarePlan: vi.fn(),
    downloadCarePlan: vi.fn(),
  },
}))
const service = vi.mocked(orderService)

function renderDetail(id: string | number = 7) {
  return renderPage(<OrderDetailPage />, { route: `/orders/${id}`, path: '/orders/:id' })
}

/** Serve an order, and a status endpoint that agrees with it. */
function serve(order: OrderResponse, preview?: string) {
  service.getOrder.mockResolvedValue(order)
  service.getCarePlanStatus.mockResolvedValue({ orderId: order.id, status: order.status, carePlanPreview: preview })
}

/** The white card with the given title ("Order", "Care Plan", "Patient", "Provider"). */
function card(title: string) {
  return within(screen.getByRole('heading', { name: title }).closest('section') as HTMLElement)
}

const pharmacist = () => { auth.state = { pharmacist: true } }

beforeEach(() => {
  vi.resetAllMocks()
  auth.state = { pharmacist: false }
  auth.useAuth.mockImplementation(() => auth.state)
})

describe('OrderDetailPage: loading and failures', () => {
  it('shows "Order not found" without calling the server when the id is not a number', () => {
    renderDetail('abc')

    expect(screen.getByText('Order not found')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: '← Back to orders' })).toHaveAttribute('href', '/orders')
    expect(service.getOrder).not.toHaveBeenCalled()
  })

  it('shows a skeleton while the order loads', () => {
    service.getOrder.mockReturnValue(new Promise(() => {}))
    service.getCarePlanStatus.mockReturnValue(new Promise(() => {}))
    const { container } = renderDetail()

    expect(container.querySelector('.animate-pulse')).toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: /^Order #/ })).not.toBeInTheDocument()
  })

  it('shows "Order not found" when the backend says the order does not exist', async () => {
    service.getOrder.mockRejectedValue(makeApiError('missing', { code: 'ORDER_NOT_FOUND', status: 404 }))
    service.getCarePlanStatus.mockRejectedValue(makeApiError('missing', { code: 'ORDER_NOT_FOUND', status: 404 }))
    renderDetail(999)

    expect(await screen.findByText('Order not found')).toBeInTheDocument()
    expect(screen.queryByText(/Something went wrong/)).not.toBeInTheDocument()
  })

  it('shows the error message and a way back for any other failure', async () => {
    service.getOrder.mockRejectedValue(makeApiError('Database unavailable', { status: 500 }))
    service.getCarePlanStatus.mockRejectedValue(makeApiError('Database unavailable', { status: 500 }))
    renderDetail()

    expect(await screen.findByText('Something went wrong loading this order: Database unavailable')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: '← Back to orders' })).toHaveAttribute('href', '/orders')
  })
})

describe('OrderDetailPage: order details', () => {
  it('shows the header, the order card, the patient card and the provider card', async () => {
    serve(makeOrder({ id: 7, status: 'completed', resultType: 'SUCCESS' }))
    renderDetail()

    expect(await screen.findByRole('heading', { name: 'Order #7' })).toBeInTheDocument()
    expect(screen.getByText('completed')).toBeInTheDocument() // status badge
    expect(screen.getByRole('link', { name: '← Back to orders' })).toHaveAttribute('href', '/orders')

    expect(card('Order').getByText('IVIG')).toBeInTheDocument()
    expect(card('Order').getByText('SUCCESS')).toBeInTheDocument()
    expect(card('Patient').getByText('Jane Doe')).toBeInTheDocument()
    expect(card('Patient').getByText('123456')).toBeInTheDocument()
    expect(card('Patient').getByText('1980-01-31')).toBeInTheDocument()
    expect(card('Provider').getByText('Dr. Smith')).toBeInTheDocument()
    expect(card('Provider').getByText('1234567890')).toBeInTheDocument()
  })

  it('shows "Not provided" when there is no date of birth', async () => {
    serve(makeOrder({ patientDateOfBirth: undefined }))
    renderDetail()

    await screen.findByRole('heading', { name: 'Order #7' })
    expect(card('Patient').getByText('Not provided')).toBeInTheDocument()
  })

  it('shows the order message only when there is one', async () => {
    serve(makeOrder({ message: 'Order accepted with warnings' }))
    renderDetail()

    await screen.findByRole('heading', { name: 'Order #7' })
    expect(card('Order').getByText('Order accepted with warnings')).toBeInTheDocument()
  })

  it('lists warnings, amber when action is required and blue when informational', async () => {
    serve(makeOrder({
      warnings: [
        { code: 'A', message: 'Needs a pharmacist decision', actionRequired: true },
        { code: 'B', message: 'For your information', actionRequired: false },
      ],
    }))
    renderDetail()

    expect(await screen.findByText('Warnings')).toBeInTheDocument()
    expect(screen.getByText('Needs a pharmacist decision')).toHaveClass('text-amber-700')
    expect(screen.getByText('For your information')).toHaveClass('text-blue-700')
  })

  it('copes with warnings: null (what GET /orders/{id} really returns)', async () => {
    serve(makeOrder({ warnings: null as unknown as OrderResponse['warnings'] }))
    renderDetail()

    await screen.findByRole('heading', { name: 'Order #7' })
    expect(screen.queryByText('Warnings')).not.toBeInTheDocument()
  })
})

describe('OrderDetailPage: care plan by status', () => {
  it('pending: says the plan is queued and offers no actions', async () => {
    serve(makeOrder({ status: 'pending', carePlanContent: null }))
    pharmacist()
    renderDetail()

    expect(await screen.findByText('Queued — the care plan has not been generated yet.')).toBeInTheDocument()
    expect(card('Care Plan').queryByRole('button')).not.toBeInTheDocument()
  })

  it('processing: shows progress and the live preview from the status endpoint', async () => {
    serve(makeOrder({ status: 'processing', carePlanContent: null }), 'Draft: problem list…')
    renderDetail()

    expect(await screen.findByText('Generating care plan…')).toBeInTheDocument()
    expect(await screen.findByText('Draft: problem list…')).toBeInTheDocument()
  })

  it('completed: shows the content', async () => {
    serve(makeOrder({ status: 'completed', carePlanContent: 'Problem list\nGoals' }))
    renderDetail()

    await screen.findByRole('heading', { name: 'Care Plan' })
    expect(card('Care Plan').getByText(/Problem list\s+Goals/)).toBeInTheDocument()
  })

  it('completed without content: says so instead of showing an empty box', async () => {
    serve(makeOrder({ status: 'completed', carePlanContent: null }))
    renderDetail()

    expect(await screen.findByText('Marked complete, but no content was returned.')).toBeInTheDocument()
  })

  it('completed, assistant: can Download but not Regenerate or Upload', async () => {
    serve(makeOrder({ status: 'completed' }))
    renderDetail()

    expect(await screen.findByRole('button', { name: 'Download' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /Regenerate/ })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Upload New' })).not.toBeInTheDocument()
  })

  it('completed, pharmacist: can Download, Regenerate and Upload New', async () => {
    serve(makeOrder({ status: 'completed' }))
    pharmacist()
    renderDetail()

    expect(await screen.findByRole('button', { name: 'Download' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Regenerate' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Upload New' })).toBeInTheDocument()
  })

  it('failed: shows the failure message, and the pharmacist can Retry or Upload Manual', async () => {
    serve(makeOrder({ status: 'failed', carePlanContent: null, message: 'The LLM timed out' }))
    pharmacist()
    renderDetail()

    await screen.findByRole('heading', { name: 'Care Plan' })
    // The same message is also listed in the Order card, so look inside the Care Plan card.
    expect(card('Care Plan').getByText('The LLM timed out')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Retry' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Upload Manual' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Download' })).not.toBeInTheDocument()
  })

  it('failed: uses a default message, and an assistant gets no actions', async () => {
    serve(makeOrder({ status: 'failed', carePlanContent: null, message: null }))
    renderDetail()

    expect(await screen.findByText('Care plan generation failed.')).toBeInTheDocument()
    expect(card('Care Plan').queryByRole('button')).not.toBeInTheDocument()
  })

  it('an unknown status renders no care plan body but the page still works', async () => {
    serve(makeOrder({ status: 'archived' as OrderResponse['status'] }))
    renderDetail()

    expect(await screen.findByRole('heading', { name: 'Order #7' })).toBeInTheDocument()
    expect(card('Care Plan').queryByRole('button')).not.toBeInTheDocument()
  })

  it('refetches the order when polling shows it finished, so the content appears without a reload', async () => {
    service.getOrder
      .mockResolvedValueOnce(makeOrder({ status: 'processing', carePlanContent: null }))
      .mockResolvedValue(makeOrder({ status: 'completed', carePlanContent: 'Freshly generated plan' }))
    service.getCarePlanStatus.mockResolvedValue({ orderId: 7, status: 'completed' })
    renderDetail()

    expect(await screen.findByText('Freshly generated plan')).toBeInTheDocument()
    expect(service.getOrder).toHaveBeenCalledTimes(2)
  })
})

describe('OrderDetailPage: download', () => {
  it('downloads this order\'s care plan', async () => {
    serve(makeOrder({ id: 7, status: 'completed' }))
    renderDetail()

    await userEvent.click(await screen.findByRole('button', { name: 'Download' }))

    expect(service.downloadCarePlan).toHaveBeenCalledWith(7)
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  })

  it('shows the error when the download fails', async () => {
    serve(makeOrder({ status: 'completed' }))
    service.downloadCarePlan.mockRejectedValue(new Error('Permission denied'))
    renderDetail()

    await userEvent.click(await screen.findByRole('button', { name: 'Download' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('Permission denied')
  })

  it('falls back to a generic message when the failure has none', async () => {
    serve(makeOrder({ status: 'completed' }))
    service.downloadCarePlan.mockRejectedValue(new Error(''))
    renderDetail()

    await userEvent.click(await screen.findByRole('button', { name: 'Download' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('Download failed. Please try again.')
  })
})

describe('OrderDetailPage: regenerate', () => {
  it('regenerates this order and shows a busy label while it runs', async () => {
    serve(makeOrder({ id: 7, status: 'completed' }))
    pharmacist()
    let finish: (order: OrderResponse) => void = () => {}
    service.regenerateCarePlan.mockReturnValue(new Promise((resolve) => { finish = resolve }))
    renderDetail()

    await userEvent.click(await screen.findByRole('button', { name: 'Regenerate' }))

    expect(service.regenerateCarePlan).toHaveBeenCalledWith(7)
    expect(await screen.findByRole('button', { name: 'Regenerating…' })).toBeDisabled()
    finish(makeOrder({ status: 'pending' }))
    await waitFor(() => expect(screen.queryByRole('button', { name: 'Regenerating…' })).not.toBeInTheDocument())
  })

  it('failed orders use the "Retry" label, then "Retrying…"', async () => {
    serve(makeOrder({ status: 'failed', carePlanContent: null }))
    pharmacist()
    service.regenerateCarePlan.mockReturnValue(new Promise(() => {}))
    renderDetail()

    await userEvent.click(await screen.findByRole('button', { name: 'Retry' }))

    expect(await screen.findByRole('button', { name: 'Retrying…' })).toBeDisabled()
  })

  it('shows the error when regeneration fails', async () => {
    serve(makeOrder({ status: 'completed' }))
    pharmacist()
    service.regenerateCarePlan.mockRejectedValue(makeApiError('Regeneration is not allowed right now', { status: 409 }))
    renderDetail()

    await userEvent.click(await screen.findByRole('button', { name: 'Regenerate' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('Regeneration is not allowed right now')
  })
})

describe('OrderDetailPage: upload', () => {
  async function openUpload(buttonName = 'Upload New') {
    serve(makeOrder({ id: 7, status: 'completed' }))
    pharmacist()
    renderDetail()
    await userEvent.click(await screen.findByRole('button', { name: buttonName }))
    return screen.findByPlaceholderText('Paste the full care plan content here')
  }

  it('opens the upload dialog, and Cancel closes it without uploading', async () => {
    await openUpload()
    expect(screen.getByRole('heading', { name: 'Upload Care Plan' })).toBeInTheDocument()

    await userEvent.click(screen.getByRole('button', { name: 'Cancel' }))

    expect(screen.queryByRole('heading', { name: 'Upload Care Plan' })).not.toBeInTheDocument()
    expect(service.uploadCarePlan).not.toHaveBeenCalled()
  })

  it('uploads pasted text for this order and closes the dialog on success', async () => {
    service.uploadCarePlan.mockResolvedValue(makeOrder({ id: 7 }))
    const textarea = await openUpload()
    await userEvent.type(textarea, 'Manual care plan')

    await userEvent.click(screen.getByRole('button', { name: 'Upload' }))

    expect(service.uploadCarePlan).toHaveBeenCalledWith(7, { text: 'Manual care plan' })
    await waitFor(() => expect(screen.queryByRole('heading', { name: 'Upload Care Plan' })).not.toBeInTheDocument())
  })

  it('keeps the dialog open and shows the error when the upload fails', async () => {
    service.uploadCarePlan.mockRejectedValue(makeApiError('Care plan is too short', { status: 400 }))
    const textarea = await openUpload()
    await userEvent.type(textarea, 'x')

    await userEvent.click(screen.getByRole('button', { name: 'Upload' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('Care plan is too short')
    expect(screen.getByRole('heading', { name: 'Upload Care Plan' })).toBeInTheDocument()
  })

  it('is also reachable from a failed order as "Upload Manual"', async () => {
    serve(makeOrder({ status: 'failed', carePlanContent: null }))
    pharmacist()
    renderDetail()

    await userEvent.click(await screen.findByRole('button', { name: 'Upload Manual' }))

    expect(screen.getByRole('heading', { name: 'Upload Care Plan' })).toBeInTheDocument()
  })
})
