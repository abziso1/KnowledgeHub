import { defineComponent } from 'vue'
import { createRouter, createWebHistory } from 'vue-router'
import type { RouteLocationNormalized } from 'vue-router'
import { onUnauthorized } from './api/client'
import { getViewer } from './auth/session'
import type { LibraryView, Role, Viewer } from './api/contracts'

export function resolveHome(viewer: Pick<Viewer, 'role'>, libraries: Pick<LibraryView, 'id'>[]): string {
  if (viewer.role === 'ADMIN') return '/admin/users'
  return libraries.length ? libraryPath(libraries[0].id) : '/app'
}

export function libraryPath(libraryId: string, folderId?: string) {
  const base = '/kb/' + encodeURIComponent(libraryId)
  return folderId ? base + '/folders/' + encodeURIComponent(folderId) : base
}

export function switchLibrary(current: string | undefined, folder: string | undefined, selection: string) {
  return libraryPath(selection, selection === current ? folder : undefined)
}

export function matchesLibrary(requested: string, current: string | undefined) {
  return requested === current
}

export function destinationForRole(viewer: Pick<Viewer, 'role'> | null, target: Role | undefined) {
  if (!target) return viewer ? resolveHome(viewer, []) : undefined
  if (!viewer) return '/login'
  return target === viewer.role ? undefined : resolveHome(viewer, [])
}

declare module 'vue-router' {
  interface RouteMeta {
    role?: Role
  }
}

export function makeRouter() {
  const emptyPane = defineComponent({ render: () => null })
  const auth = () => import('./auth/AuthView.vue')
  const notes = () => import('./editor/NoteList.vue')
  const trash = () => import('./trash/TrashView.vue')
  const noteListProps = (route: RouteLocationNormalized) => ({
    libraryId: route.params.libraryId, folderId: route.params.folderId || null,
  })
  const router = createRouter({
    history: createWebHistory(),
    routes: [
      { path: '/login', name: 'login', component: auth },
      { path: '/register', name: 'register', component: auth },
      { path: '/admin/users', name: 'admin-users', component: () => import('./admin/AdminUsersView.vue'),
        meta: { role: 'ADMIN' } },
      {
        path: '/',
        component: () => import('./workspace/WorkspaceView.vue'),
        meta: { role: 'USER' },
        children: [
          { path: '', redirect: '/app' },
          { path: '/app', name: 'workspace-home', component: emptyPane },
          { path: '/kb/:libraryId', name: 'library', component: notes, props: noteListProps },
          { path: '/kb/:libraryId/folders/:folderId', name: 'folder', component: notes, props: noteListProps },
          { path: '/kb/:libraryId/notes/:noteId', name: 'note', component: () => import('./editor/NoteEditorView.vue'),
            props: true },
          { path: '/kb/:libraryId/search', name: 'search', component: () => import('./editor/NoteSearchView.vue'),
            props: true },
          { path: '/kb/:libraryId/content-trash', name: 'content-trash', component: trash,
            props: route => ({ scope: 'CONTENT', libraryId: route.params.libraryId }) },
          { path: '/library-trash', name: 'library-trash', component: trash, props: { scope: 'LIBRARY' } },
        ],
      },
      { path: '/:pathMatch(.*)*', redirect: '/app' },
    ],
  })
  router.beforeEach(async to => {
    try {
      return destinationForRole(await getViewer(), to.meta.role)
    } catch {
      return to.meta.role ? { name: 'login', query: { unavailable: '1' } } : undefined
    }
  })
  onUnauthorized(() => {
    if (router.currentRoute.value.meta.role) void router.replace({ name: 'login' })
  })
  return router
}
