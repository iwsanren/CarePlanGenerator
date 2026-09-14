import axios from 'axios'
import type { ApiError, ApiErrorBody } from '@/types'

const api = axios.create({
  baseURL: import.meta.env.VITE_API_URL || '/api/v1',
  headers: {
    'Content-Type': 'application/json',
  },
})

// Response interceptor for error handling
api.interceptors.response.use(
  (response) => response,
  (error) => {
    const body = error.response?.data as ApiErrorBody | undefined
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
