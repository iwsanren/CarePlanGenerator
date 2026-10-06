import axios from 'axios'
import { authorization, currentGeneration, expireSession } from '@/auth/auth'
import type { ApiError, ApiErrorBody } from '@/types'

const api = axios.create({
  baseURL: import.meta.env.VITE_API_URL || '/api/v1',
  headers: {
    'Content-Type': 'application/json',
  },
})

api.interceptors.request.use(async (config) => {
  const auth = await authorization(config.signal as AbortSignal | undefined)
  config.headers.Authorization = `Bearer ${auth.token}`
  config.signal = auth.signal
  ;(config as typeof config & { authGeneration: number }).authGeneration = auth.generation
  return config
})

// Response interceptor for error handling
api.interceptors.response.use(
  (response) => {
    const epoch = (response.config as typeof response.config & { authGeneration: number }).authGeneration
    if (epoch !== currentGeneration()) throw new axios.CanceledError('Session changed')
    return response
  },
  async (error) => {
    if (axios.isCancel(error)) throw error
    const requestGeneration = error.config?.authGeneration
    if (error.response?.status === 401 && requestGeneration === currentGeneration()) expireSession()
    let body = error.response?.data as ApiErrorBody | undefined
    // File requests still receive the same JSON errors as ordinary API requests.
    if (error.response?.data instanceof Blob) {
      try { body = JSON.parse(await error.response.data.text()) as ApiErrorBody }
      catch { body = undefined }
    }
    const message = body?.message || error.message || 'An error occurred'

    const customError = new Error(message) as ApiError
    customError.type = body?.type
    customError.code = body?.code
    customError.detail = body?.detail
    customError.status = error.response?.status // transport-level status, not body.httpStatus

    throw customError
  }
)

export default api
