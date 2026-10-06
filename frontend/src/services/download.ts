import api from './api'

/** Authenticated downloads never put bearer credentials in URLs. */
export async function downloadFile(path: string, fallbackName: string) {
  const response = await api.get<Blob>(path, { responseType: 'blob' })
  const disposition = String(response.headers['content-disposition'] || '')
  const filename = disposition.match(/filename="?([^";]+)"?/)?.[1] || fallbackName
  const url = URL.createObjectURL(response.data)
  const link = document.createElement('a')
  link.href = url
  link.download = filename
  document.body.appendChild(link)
  link.click()
  link.remove()
  setTimeout(() => URL.revokeObjectURL(url), 1000)
}
