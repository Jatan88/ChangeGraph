import { afterEach, describe, expect, it, vi } from 'vitest'
import { api, ApiError } from './client'

afterEach(() => {
  vi.unstubAllGlobals()
  document.cookie = 'XSRF-TOKEN=; Max-Age=0'
})

describe('api client', () => {
  it('sends the CSRF header on state-changing requests', async () => {
    document.cookie = 'XSRF-TOKEN=token%2Bvalue'
    const fetchMock = vi.fn(async (_input: RequestInfo | URL, init?: RequestInit) => {
      const headers = new Headers(init?.headers)
      expect(headers.get('X-XSRF-TOKEN')).toBe('token+value')
      expect(init?.credentials).toBe('include')
      return new Response(null, { status: 204 })
    })
    vi.stubGlobal('fetch', fetchMock)

    await api('/api/auth/logout', { method: 'POST' })
    expect(fetchMock).toHaveBeenCalledOnce()
  })

  it('exposes status, code, and field errors', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(async () =>
        new Response(JSON.stringify({ code: 'VALIDATION_ERROR', message: 'Invalid', fieldErrors: [{ field: 'email', message: 'must be an email' }] }), {
          status: 400,
          headers: { 'Content-Type': 'application/json' },
        }),
      ),
    )

    await expect(api('/api/auth/register', { method: 'POST', body: '{}' })).rejects.toMatchObject({
      status: 400,
      code: 'VALIDATION_ERROR',
      fieldErrors: [{ field: 'email', message: 'must be an email' }],
    })
    await expect(api('/api/auth/register', { method: 'POST', body: '{}' })).rejects.toBeInstanceOf(ApiError)
  })
})
