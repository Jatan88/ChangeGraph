import type { ApiErrorBody } from './types'

export class ApiError extends Error {
  status: number
  code: string
  fieldErrors: { field: string; message: string }[]

  constructor(status: number, body: ApiErrorBody) {
    super(body.message || 'Request failed')
    this.status = status
    this.code = body.code || 'REQUEST_FAILED'
    this.fieldErrors = body.fieldErrors ?? []
  }
}

function csrfToken(): string | null {
  const match = document.cookie.match(/(?:^|; )XSRF-TOKEN=([^;]*)/)
  return match ? decodeURIComponent(match[1]) : null
}

export async function api<T>(path: string, options: RequestInit = {}): Promise<T> {
  const headers = new Headers(options.headers)
  headers.set('Accept', 'application/json')
  const method = (options.method ?? 'GET').toUpperCase()
  if (options.body && !headers.has('Content-Type')) {
    headers.set('Content-Type', 'application/json')
  }
  if (method !== 'GET' && method !== 'HEAD') {
    const token = csrfToken()
    if (token) {
      headers.set('X-XSRF-TOKEN', token)
    }
  }
  const response = await fetch(path, { ...options, headers, credentials: 'include' })
  if (response.status === 204) {
    return undefined as T
  }
  const text = await response.text()
  const data = text ? (JSON.parse(text) as unknown) : null
  if (!response.ok) {
    const body = (data ?? {}) as ApiErrorBody
    throw new ApiError(response.status, {
      code: body.code ?? 'REQUEST_FAILED',
      message: body.message ?? response.statusText,
      fieldErrors: body.fieldErrors ?? [],
    })
  }
  return data as T
}
