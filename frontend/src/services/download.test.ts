import { afterEach, beforeEach, describe, expect, it, vi, type Mock } from 'vitest'
import api from './api'
import { downloadFile } from './download'

vi.mock('./api', () => ({ default: { get: vi.fn() } }))
// AxiosInstance.get is a generic overload that vi.mocked() cannot type; it is a plain spy here.
const http = api as unknown as { get: Mock }

interface ClickedLink { href: string; download: string; attached: boolean }
let clicked: ClickedLink[]
let createObjectURL: ReturnType<typeof vi.fn>
let revokeObjectURL: ReturnType<typeof vi.fn>

beforeEach(() => {
  vi.resetAllMocks()
  vi.useFakeTimers()
  clicked = []
  // jsdom has no object-URL support, so stub it.
  createObjectURL = vi.fn(() => 'blob:fake-url')
  revokeObjectURL = vi.fn()
  URL.createObjectURL = createObjectURL as unknown as typeof URL.createObjectURL
  URL.revokeObjectURL = revokeObjectURL as unknown as typeof URL.revokeObjectURL
  // jsdom would try to navigate on click; record what the link looked like instead.
  vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(function (this: HTMLAnchorElement) {
    clicked.push({ href: this.getAttribute('href') ?? '', download: this.download, attached: document.body.contains(this) })
  })
})

afterEach(() => {
  vi.useRealTimers()
  vi.restoreAllMocks()
})

function respond(headers: Record<string, string> = {}) {
  http.get.mockResolvedValue({ data: new Blob(['content']), headers })
}

describe('downloadFile', () => {
  it('requests the path as a blob through the authenticated axios instance', async () => {
    respond()
    await downloadFile('/orders/5/careplan/download', 'fallback.txt')

    expect(http.get).toHaveBeenCalledWith('/orders/5/careplan/download', { responseType: 'blob' })
  })

  it('clicks a temporary link to the blob URL, then removes it', async () => {
    respond()
    await downloadFile('/x', 'fallback.txt')

    expect(clicked).toEqual([{ href: 'blob:fake-url', download: 'fallback.txt', attached: true }])
    expect(document.querySelector('a[download]')).toBeNull() // cleaned up after the click
  })

  it('uses the filename from a quoted Content-Disposition header', async () => {
    respond({ 'content-disposition': 'attachment; filename="plan-7.txt"' })
    await downloadFile('/x', 'fallback.txt')

    expect(clicked[0].download).toBe('plan-7.txt')
  })

  it('uses the filename from an unquoted Content-Disposition header', async () => {
    respond({ 'content-disposition': 'attachment; filename=orders.csv' })
    await downloadFile('/x', 'fallback.txt')

    expect(clicked[0].download).toBe('orders.csv')
  })

  it.each([
    ['no header', {}],
    ['a header without a filename', { 'content-disposition': 'attachment' }],
  ])('falls back to the given name with %s', async (_name, headers) => {
    respond(headers)
    await downloadFile('/x', 'fallback.txt')

    expect(clicked[0].download).toBe('fallback.txt')
  })

  it('releases the blob URL one second later, not immediately', async () => {
    respond()
    await downloadFile('/x', 'fallback.txt')

    expect(revokeObjectURL).not.toHaveBeenCalled()
    vi.advanceTimersByTime(999)
    expect(revokeObjectURL).not.toHaveBeenCalled()
    vi.advanceTimersByTime(1)
    expect(revokeObjectURL).toHaveBeenCalledWith('blob:fake-url')
  })

  it('creates no link when the request fails, and passes the error on', async () => {
    http.get.mockRejectedValue(new Error('Permission denied'))

    await expect(downloadFile('/x', 'fallback.txt')).rejects.toThrow('Permission denied')
    expect(clicked).toEqual([])
    expect(createObjectURL).not.toHaveBeenCalled()
  })
})
