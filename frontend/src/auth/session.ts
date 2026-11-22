import { readonly, shallowRef } from 'vue'
import { api, ApiError, onUnauthorized, resetCsrfToken } from '../api/client'
import type { Viewer } from '../api/contracts'

const viewer = shallowRef<Viewer | null | undefined>(undefined)
export const activeViewer = readonly(viewer)
let viewerRequest: Promise<Viewer | null> | undefined

onUnauthorized(() => {
  viewerRequest = undefined
  viewer.value = null
})

export function getViewer(): Promise<Viewer | null> {
  if (viewer.value !== undefined) return Promise.resolve(viewer.value)
  if (!viewerRequest) {
    const request = api<Viewer>('/api/auth/me').then(result => {
      if (viewerRequest === request) viewer.value = result
      return viewer.value ?? null
    }).catch(error => {
      if (error instanceof ApiError && error.status === 401) return viewer.value ?? null
      throw error
    }).finally(() => {
      if (viewerRequest === request) viewerRequest = undefined
    })
    viewerRequest = request
  }
  return viewerRequest
}

export function register(username: string, password: string): Promise<Viewer> {
  return api('/api/auth/register', { method: 'POST', body: JSON.stringify({ username, password }) })
}

export async function login(username: string, password: string): Promise<Viewer> {
  const result = await api<Viewer>('/api/auth/login', { method: 'POST', body: JSON.stringify({ username, password }) })
  viewerRequest = undefined
  resetCsrfToken()
  viewer.value = result
  return result
}

export async function logout(): Promise<void> {
  try {
    await api<void>('/api/auth/logout', { method: 'POST' })
  } catch (error) {
    if (!(error instanceof ApiError) || error.status !== 401) throw error
  }
  viewerRequest = undefined
  viewer.value = null
  resetCsrfToken()
}
