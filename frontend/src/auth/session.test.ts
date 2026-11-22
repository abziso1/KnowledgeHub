import { afterEach, describe, expect, it, vi } from 'vitest'

const viewer = { id: 'user', username: 'reader', role: 'USER' }

function response(data: unknown, status = 200) {
  return new Response(JSON.stringify(data), { status, headers: { 'Content-Type': 'application/json' } })
}

afterEach(() => {
  vi.unstubAllGlobals()
  vi.resetModules()
})

describe('same-origin sessions', () => {
  it('uses the server CSRF header and fetches a fresh token after login', async () => {
    const fetch = vi.fn()
      .mockResolvedValueOnce(response({ headerName: 'X-KH-Token', token: 'before-login' }))
      .mockResolvedValueOnce(response(viewer))
      .mockResolvedValueOnce(response({ headerName: 'X-KH-Token', token: 'after-login' }))
      .mockResolvedValueOnce(response({ id: 'library' }, 201))
    vi.stubGlobal('fetch', fetch)
    const { login } = await import('./session')
    const { api } = await import('../api/client')

    await expect(login('reader', 'password')).resolves.toEqual(viewer)
    await api('/api/libraries', { method: 'POST', body: JSON.stringify({ name: '学习' }) })

    expect(fetch.mock.calls.map(([path]) => path)).toEqual([
      '/api/auth/csrf', '/api/auth/login', '/api/auth/csrf', '/api/libraries',
    ])
    expect(fetch.mock.calls[1][1].credentials).toBe('same-origin')
    expect(new Headers(fetch.mock.calls[1][1].headers).get('X-KH-Token')).toBe('before-login')
    expect(new Headers(fetch.mock.calls[3][1].headers).get('X-KH-Token')).toBe('after-login')
  })

  it('returns no viewer after a 401 and never retries the refused request', async () => {
    const fetch = vi.fn()
      .mockResolvedValueOnce(response({ code: 'AUTH_REQUIRED', message: '请登录' }, 401))
    vi.stubGlobal('fetch', fetch)
    const { getViewer } = await import('./session')
    await expect(getViewer()).resolves.toBeNull()
    expect(fetch).toHaveBeenCalledTimes(1)
  })

  it('authenticates a FormData write without setting its content boundary', async () => {
    const fetch = vi.fn()
      .mockResolvedValueOnce(response({ headerName: 'X-CSRF-TOKEN', token: 'token' }))
      .mockResolvedValueOnce(response({ id: 'image' }, 201))
    vi.stubGlobal('fetch', fetch)
    const { api } = await import('../api/client')
    const file = new FormData()
    file.set('file', new Blob(['data']), 'sample.md')
    await api('/api/libraries/library/notes/import', { method: 'POST', body: file })
    expect(new Headers(fetch.mock.calls[1][1].headers).has('Content-Type')).toBe(false)
    expect(new Headers(fetch.mock.calls[1][1].headers).get('X-CSRF-TOKEN')).toBe('token')
  })
})
