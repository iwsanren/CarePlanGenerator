import axios from 'axios'

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
    const message =
      error.response?.data?.message ||
      error.response?.data?.detail ||
      error.message ||
      'An error occurred'

    const customError = new Error(message) as Error & {
      code?: string
      details?: unknown
      status?: number
    }

    customError.code = error.response?.data?.code
    customError.details = error.response?.data?.details
    customError.status = error.response?.status

    throw customError
  }
)

export default api
