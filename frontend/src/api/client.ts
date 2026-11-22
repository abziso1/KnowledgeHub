import type { CsrfToken } from './contracts'

export class ApiError extends Error {
  constructor(readonly status: number, readonly code: string, message: string) {
    super(message)
    this.name = 'ApiError'
  }
}

const unauthorized = new Set<() => void>()
let csrfRequest: Promise<CsrfToken> | undefined
let sessionVersion = 0

export function resetCsrfToken() {
  csrfRequest = undefined
  sessionVersion++
}

export function onUnauthorized(listener: () => void) {
  unauthorized.add(listener)
  return () => unauthorized.delete(listener)
}

async function send(path: string, init: RequestInit): Promise<Response> {
  try {
    return await fetch(path, { ...init, credentials: 'same-origin' })
  } catch (error) {
    if (error instanceof Error && error.name === 'AbortError') {
      throw error
    }
    throw new ApiError(0, 'NETWORK_ERROR', '无法连接服务，请稍后重试')
  }
}

async function check(response: Response, version: number) {
  if (response.ok) return
  const body = await response.json().catch(() => ({}))
  if (response.status === 401 && sessionVersion === version) {
    resetCsrfToken()
    unauthorized.forEach(listener => listener())
  }
  throw new ApiError(response.status, body?.code || 'REQUEST_FAILED',
    body?.message || `请求失败（${response.status}）`)
}

function csrf(): Promise<CsrfToken> {
  if (!csrfRequest) {
    const version = sessionVersion
    const request = send('/api/auth/csrf', { method: 'GET', cache: 'no-store' }).then(async response => {
      await check(response, version)
      return response.json() as Promise<CsrfToken>
    })
    csrfRequest = request
    void request.catch(() => {
      if (csrfRequest === request) csrfRequest = undefined
    })
  }
  return csrfRequest
}

export async function api<T>(path: string, init: RequestInit = {}): Promise<T> {
  const version = sessionVersion
  const method = (init.method || 'GET').toUpperCase()
  const headers = new Headers(init.headers)
  if (typeof init.body === 'string' && !headers.has('Content-Type')) {
    headers.set('Content-Type', 'application/json')
  }
  if (method !== 'GET' && method !== 'HEAD' && method !== 'OPTIONS') {
    const token = await csrf()
    headers.set(token.headerName, token.token)
  }
  const response = await send(path, { ...init, method, headers })
  await check(response, version)
  return response.status === 204 ? undefined as T : response.json() as Promise<T>
}

export function errorText(error: unknown, fallback = '操作失败，请稍后重试') {
  return error instanceof ApiError ? error.message : fallback
}
