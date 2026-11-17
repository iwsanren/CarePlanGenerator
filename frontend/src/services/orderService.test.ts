import { beforeEach, describe, expect, it, vi, type Mock } from 'vitest'
import axios from 'axios'
import api from '@/services/api'
import { downloadFile } from './download'
import { orderService } from './orderService'
import { makeListItem, makeOrder, makePage } from '@/test/fixtures'
import type { ApiError, CreateOrderRequest } from '@/types'

// orderService only does "call endpoint, normalize result", so the axios instance
// and the download helper are the boundary we replace.
vi.mock('@/services/api', () => ({ default: { get: vi.fn(), post: vi.fn() } }))
vi.mock('./download', () => ({ downloadFile: vi.fn() }))

// AxiosInstance.get/post are generic overloads that vi.mocked() cannot type, so view
// the mocked instance as the two spies it really is.
const http = api as unknown as { get: Mock; post: Mock }

/** The backend historically sends status in UPPER CASE. */
const upper = (status: string) => status.toUpperCase() as never

const request: CreateOrderRequest = {
  patientFirstName: 'Jane',
  patientLastName: 'Doe',
  patientMrn: '123456',
  providerName: 'Dr. Smith',
  providerNpi: '1234567890',
  medicationName: 'IVIG',
  primaryDiagnosis: 'G70.00',
  additionalDiagnoses: [],
  medicationHistory: [],
}

beforeEach(() => {
  vi.resetAllMocks()
})

describe('getOrders', () => {
  it('GETs /orders with the params and returns the body untouched', async () => {
    const page = makePage([makeListItem()])
    http.get.mockResolvedValue({ data: page })

    const result = await orderService.getOrders({ page: 2, page_size: 20, status: 'failed' })

    expect(http.get).toHaveBeenCalledWith('/orders', { params: { page: 2, page_size: 20, status: 'failed' } })
    expect(result).toBe(page)
  })

  it('defaults to empty params', async () => {
    http.get.mockResolvedValue({ data: makePage([]) })
    await orderService.getOrders()

    expect(http.get).toHaveBeenCalledWith('/orders', { params: {} })
  })

  it('passes every search parameter through unchanged, with sort as ONE string', async () => {
    http.get.mockResolvedValue({ data: makePage([]) })
    const params = {
      page: 2, page_size: 20, status: 'failed' as const, patient_name: 'Jane', mrn: '123456', medication: 'IVIG',
      provider_name: 'Dr. Green', created_from: '2026-11-01', created_to: '2026-11-30', sort: 'created_at,desc',
    }

    await orderService.getOrders(params)

    expect(http.get).toHaveBeenCalledWith('/orders', { params })
  })
})

// services/api.ts is a plain axios.create() with no custom paramsSerializer, so the default serializer of plain
// axios is the one that writes the real query string. getUri builds the URL without sending anything.
describe('how axios writes the search parameters into the URL', () => {
  it('writes sort as sort=created_at,desc, which is what the backend reads', () => {
    const url = axios.getUri({
      url: '/orders',
      params: { page: 1, page_size: 20, status: 'failed', sort: 'created_at,desc' },
    })

    expect(url).toBe('/orders?page=1&page_size=20&status=failed&sort=created_at,desc')
  })

  it('would write an array as sort[]=... (brackets URL-encoded), which the backend does NOT read', () => {
    const url = axios.getUri({ url: '/orders', params: { sort: ['status,asc', 'created_at,desc'] } })

    // This is why the page sends ONE sort string: the backend reads the name "sort", not "sort[]".
    expect(url).toBe('/orders?sort%5B%5D=status,asc&sort%5B%5D=created_at,desc')
    expect(url).not.toMatch(/[?&]sort=/)
  })

  it('encodes spaces and punctuation in the texts, and leaves a missing parameter out', () => {
    const url = axios.getUri({
      url: '/orders',
      params: { medication: 'Gamunex-C', provider_name: 'Dr. Green & Co', created_from: undefined },
    })

    expect(url).toBe('/orders?medication=Gamunex-C&provider_name=Dr.+Green+%26+Co')
  })
})

describe('getOrder', () => {
  it('GETs /orders/{id} and folds the status to lower case', async () => {
    http.get.mockResolvedValue({ data: makeOrder({ id: 5, status: upper('COMPLETED') }) })

    const order = await orderService.getOrder(5)

    expect(http.get).toHaveBeenCalledWith('/orders/5')
    expect(order.status).toBe('completed')
    expect(order.id).toBe(5)
    expect(order.carePlanContent).toBe('Problem list\nGoals') // other fields are preserved
  })

  it('leaves an already lower-case status alone', async () => {
    http.get.mockResolvedValue({ data: makeOrder({ status: 'failed' }) })

    expect((await orderService.getOrder(1)).status).toBe('failed')
  })
})

describe('createOrder', () => {
  it('POSTs the payload to /orders and lower-cases the returned status', async () => {
    http.post.mockResolvedValue({ status: 201, data: makeOrder({ id: 9, status: upper('PENDING') }) })

    const order = await orderService.createOrder(request)

    expect(http.post).toHaveBeenCalledWith('/orders', request)
    expect(order.id).toBe(9)
    expect(order.status).toBe('pending')
  })

  it('turns a 200 "warning" body into a thrown error the type guards recognise', async () => {
    const detail = { requiresConfirm: true, warnings: [{ code: 'DUP_MRN', message: 'Same MRN', actionRequired: true }] }
    http.post.mockResolvedValue({
      status: 200,
      data: { type: 'warning', code: 'CONFIRMATION_REQUIRED', message: 'Please confirm', detail },
    })

    const error = (await orderService.createOrder(request).catch((e: unknown) => e)) as ApiError

    expect(error).toBeInstanceOf(Error)
    expect(error).toMatchObject({
      message: 'Please confirm',
      type: 'warning',
      code: 'CONFIRMATION_REQUIRED',
      detail,
      status: 200, // status of the response that carried it, even though it is not a transport failure
    })
  })

  it('turns a "block" body into a thrown error too', async () => {
    http.post.mockResolvedValue({ status: 200, data: { type: 'block', code: 'DUPLICATE_ORDER', message: 'Duplicate' } })

    await expect(orderService.createOrder(request)).rejects.toMatchObject({ type: 'block', code: 'DUPLICATE_ORDER' })
  })

  it('uses a default message when the error body has none', async () => {
    http.post.mockResolvedValue({ status: 200, data: { type: 'block' } })

    await expect(orderService.createOrder(request)).rejects.toThrow('Order could not be created')
  })

  it('propagates transport errors thrown by the axios layer', async () => {
    http.post.mockRejectedValue(new Error('Network Error'))

    await expect(orderService.createOrder(request)).rejects.toThrow('Network Error')
  })
})

describe('getCarePlanStatus', () => {
  it('GETs /orders/{id}/status and folds the status to lower case', async () => {
    http.get.mockResolvedValue({ data: { orderId: 5, status: 'PROCESSING', carePlanPreview: 'Draft…' } })

    const status = await orderService.getCarePlanStatus(5)

    expect(http.get).toHaveBeenCalledWith('/orders/5/status')
    expect(status).toEqual({ orderId: 5, status: 'processing', carePlanPreview: 'Draft…' })
  })
})

describe('regenerateCarePlan', () => {
  it('POSTs to /orders/{id}/regenerate and lower-cases the status', async () => {
    http.post.mockResolvedValue({ data: makeOrder({ id: 5, status: upper('PENDING') }) })

    const order = await orderService.regenerateCarePlan(5)

    expect(http.post).toHaveBeenCalledWith('/orders/5/regenerate')
    expect(order.status).toBe('pending')
  })
})

describe('uploadCarePlan', () => {
  /** The FormData handed to api.post (second argument of the first call). */
  const sentForm = () => http.post.mock.calls[0][1] as FormData

  beforeEach(() => {
    http.post.mockResolvedValue({ data: makeOrder({ status: upper('COMPLETED') }) })
  })

  it('sends pasted text as the "content" field with a multipart header', async () => {
    const order = await orderService.uploadCarePlan(5, { text: 'new plan' })

    expect(http.post).toHaveBeenCalledWith('/orders/5/careplan/upload', expect.any(FormData), {
      headers: { 'Content-Type': 'multipart/form-data' },
    })
    expect(sentForm().get('content')).toBe('new plan')
    expect(sentForm().has('file')).toBe(false)
    expect(order.status).toBe('completed')
  })

  it('sends a file as the "file" field', async () => {
    const file = new File(['plan text'], 'plan.txt', { type: 'text/plain' })
    await orderService.uploadCarePlan(5, { file })

    expect((sentForm().get('file') as File).name).toBe('plan.txt')
    expect(sentForm().has('content')).toBe(false)
  })

  // Characterization: an empty string is falsy, so it is not appended. The modal
  // already blocks empty submissions; this pins what the service itself does.
  it('does not append an empty text value', async () => {
    await orderService.uploadCarePlan(5, { text: '' })

    expect(sentForm().has('content')).toBe(false)
  })
})

describe('downloadCarePlan', () => {
  it('asks the download helper for the care plan with a per-order fallback name', async () => {
    await orderService.downloadCarePlan(5)

    expect(downloadFile).toHaveBeenCalledWith('/orders/5/careplan/download', 'careplan-5.txt')
  })

  it('propagates a download failure to the caller', async () => {
    vi.mocked(downloadFile).mockRejectedValue(new Error('Permission denied'))

    await expect(orderService.downloadCarePlan(5)).rejects.toThrow('Permission denied')
  })
})
