export type Role = 'USER' | 'ADMIN'

export interface Viewer {
  id: string
  username: string
  role: Role
}

export interface CsrfToken {
  headerName: string
  token: string
}

export interface Page<T> {
  items: T[]
  page: number
  size: number
  hasNext: boolean
}

export interface AdminUser {
  id: string
  username: string
  enabled: boolean
  createdAt: string
}

export interface LibraryView {
  id: string
  name: string
  updatedAt: string
}

export interface FolderView {
  id: string
  parentId: string | null
  name: string
  updatedAt: string
}

export interface NoteSummary {
  id: string
  folderId: string | null
  title: string
  version: number
  updatedAt: string
}

export interface NoteView extends NoteSummary {
  markdown: string
}

export interface SaveNoteInput {
  version: number
  title: string
  markdown: string
}

export interface SearchHit {
  id: string
  title: string
  folderPath: string[]
  updatedAt: string
}

export interface ImageView {
  id: string
  url: string
}

export type ContentKind = 'NOTE' | 'FOLDER'

export interface ContentTrashItem {
  id: string
  kind: ContentKind
  targetId: string
  name: string
  trashedAt: string
}

export interface RestoredContent {
  id: string
  kind: ContentKind
  parentId: string | null
  name: string
}

export interface TrashedLibrary {
  id: string
  name: string
  trashedAt: string
}
