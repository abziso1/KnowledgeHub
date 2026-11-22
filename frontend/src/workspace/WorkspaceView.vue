<script setup lang="ts">
import { ElMessageBox } from 'element-plus'
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { api, errorText } from '../api/client'
import type { FolderView, LibraryView } from '../api/contracts'
import { activeViewer, logout } from '../auth/session'
import { libraryPath, matchesLibrary, resolveHome, switchLibrary } from '../router'
import FolderTree from './FolderTree.vue'
import LibrarySwitcher from './LibrarySwitcher.vue'

const route = useRoute()
const router = useRouter()
const libraries = ref<LibraryView[]>([])
const folders = ref<FolderView[]>([])
const loadingLibraries = ref(true)
const loadingFolders = ref(false)
const error = ref('')
const movingId = ref<string>()
const destination = ref('')
const savingMove = ref(false)
const libraryId = computed(() => typeof route.params.libraryId === 'string' ? route.params.libraryId : undefined)
const folderId = computed(() => typeof route.params.folderId === 'string' ? route.params.folderId : undefined)
const library = computed(() => libraries.value.find(item => item.id === libraryId.value))
const folder = computed(() => folders.value.find(item => item.id === folderId.value))
const libraryTrash = computed(() => route.name === 'library-trash')
const listingNotes = computed(() => route.name === 'library' || route.name === 'folder')
const libraryApi = (id: string) => '/api/libraries/' + encodeURIComponent(id)

const moveTargets = computed(() => {
  const byId = new Map(folders.value.map(item => [item.id, item]))
  return folders.value.filter(item => {
    let ancestor: FolderView | undefined = item
    while (ancestor) {
      if (ancestor.id === movingId.value) return false
      ancestor = ancestor.parentId ? byId.get(ancestor.parentId) : undefined
    }
    return true
  })
})

async function reloadLibraries() {
  loadingLibraries.value = true
  try {
    libraries.value = await api<LibraryView[]>('/api/libraries')
    if (route.name === 'workspace-home' && activeViewer.value && libraries.value.length) {
      await router.replace(resolveHome(activeViewer.value, libraries.value))
    }
  } catch (failure) {
    error.value = errorText(failure)
  } finally {
    loadingLibraries.value = false
  }
}

let folderLoad = 0
async function reloadFolders(id: string) {
  const request = ++folderLoad
  loadingFolders.value = true
  try {
    const items = await api<FolderView[]>(libraryApi(id) + '/folders')
    if (request === folderLoad && matchesLibrary(id, libraryId.value)) folders.value = items
  } catch (failure) {
    if (request === folderLoad && matchesLibrary(id, libraryId.value)) error.value = errorText(failure)
  } finally {
    if (request === folderLoad) loadingFolders.value = false
  }
}

watch(libraryId, id => {
  error.value = ''
  movingId.value = undefined
  folders.value = []
  if (id) void reloadFolders(id)
  else {
    folderLoad++
    loadingFolders.value = false
  }
}, { immediate: true })
onMounted(reloadLibraries)

async function askName(title: string, initial = '') {
  try {
    const result = await ElMessageBox.prompt('名称', title, {
      inputValue: initial, confirmButtonText: '确定', cancelButtonText: '取消',
      inputValidator: input => Boolean(input.trim()) || '请输入名称',
    })
    return (result as unknown as { value: string }).value.trim()
  } catch {
    return undefined
  }
}

async function createLibrary() {
  const name = await askName('新建知识库')
  if (!name) return
  error.value = ''
  try {
    const created = await api<LibraryView>('/api/libraries', { method: 'POST', body: JSON.stringify({ name }) })
    await router.push(libraryPath(created.id))
    await reloadLibraries()
  } catch (failure) {
    error.value = errorText(failure)
  }
}

async function renameLibrary() {
  const current = library.value
  if (!current) return
  const name = await askName('重命名知识库', current.name)
  if (!name) return
  error.value = ''
  try {
    const updated = await api<LibraryView>(libraryApi(current.id), { method: 'PATCH', body: JSON.stringify({ name }) })
    libraries.value = libraries.value.map(item => item.id === updated.id ? updated : item)
  } catch (failure) {
    error.value = errorText(failure)
  }
}

function selectLibrary(id: string) {
  if (id !== libraryId.value) void router.push(switchLibrary(libraryId.value, folderId.value, id))
  else if (route.name !== 'folder' && route.name !== 'library') void router.push(libraryPath(id))
}

function selectFolder(id?: string) {
  if (libraryId.value) void router.push(libraryPath(libraryId.value, id))
}

async function createFolder() {
  const id = libraryId.value
  const parentId = folderId.value ?? null
  if (!id) return
  const name = await askName('新建文件夹')
  if (!name) return
  error.value = ''
  try {
    const created = await api<FolderView>(libraryApi(id) + '/folders', {
      method: 'POST', body: JSON.stringify({ name, parentId }),
    })
    if (matchesLibrary(id, libraryId.value)) {
      await reloadFolders(id)
      if (matchesLibrary(id, libraryId.value)) selectFolder(created.id)
    }
  } catch (failure) {
    if (matchesLibrary(id, libraryId.value)) error.value = errorText(failure)
  }
}

async function renameFolder() {
  const id = libraryId.value
  const current = folder.value
  if (!id || !current) return
  const name = await askName('重命名文件夹', current.name)
  if (!name) return
  error.value = ''
  try {
    await api<FolderView>(libraryApi(id) + '/folders/' + current.id, {
      method: 'PATCH', body: JSON.stringify({ name }),
    })
    if (matchesLibrary(id, libraryId.value)) await reloadFolders(id)
  } catch (failure) {
    if (matchesLibrary(id, libraryId.value)) error.value = errorText(failure)
  }
}

function onMoveVisibility(visible: boolean) {
  if (!visible) movingId.value = undefined
}

function startMove() {
  if (!folder.value) return
  movingId.value = folder.value.id
  destination.value = folder.value.parentId ?? ''
}

async function moveFolder() {
  const id = libraryId.value
  if (!id || !movingId.value || savingMove.value) return
  savingMove.value = true
  error.value = ''
  try {
    await api<FolderView>(libraryApi(id) + '/folders/' + movingId.value + '/parent', {
      method: 'PUT', body: JSON.stringify({ parentId: destination.value || null }),
    })
    movingId.value = undefined
    if (matchesLibrary(id, libraryId.value)) await reloadFolders(id)
  } catch (failure) {
    if (matchesLibrary(id, libraryId.value)) error.value = errorText(failure)
  } finally {
    savingMove.value = false
  }
}

async function trashCurrentLibrary() {
  const current = library.value
  if (!current) return
  try {
    await ElMessageBox.confirm(`将「${current.name}」移入知识库回收站？库内笔记和图片可以随知识库恢复。`,
      '移入知识库回收站', { confirmButtonText: '移入回收站', cancelButtonText: '取消', type: 'warning' })
  } catch {
    return
  }
  try {
    await api(libraryApi(current.id), { method: 'DELETE' })
    libraries.value = libraries.value.filter(item => item.id !== current.id)
    if (matchesLibrary(current.id, libraryId.value) && activeViewer.value) {
      await router.replace(resolveHome(activeViewer.value, libraries.value))
    }
    await reloadLibraries()
  } catch (failure) {
    error.value = errorText(failure)
  }
}

async function trashCurrentFolder() {
  const id = libraryId.value
  const current = folder.value
  if (!id || !current) return
  try {
    await ElMessageBox.confirm(`将「${current.name}」及其中的内容移入内容回收站？`,
      '移入内容回收站', { confirmButtonText: '移入回收站', cancelButtonText: '取消', type: 'warning' })
  } catch {
    return
  }
  try {
    await api(libraryApi(id) + '/folders/' + current.id, { method: 'DELETE' })
    if (matchesLibrary(id, libraryId.value)) {
      if (folderId.value === current.id) await router.replace(libraryPath(id, current.parentId ?? undefined))
      await reloadFolders(id)
    }
  } catch (failure) {
    if (matchesLibrary(id, libraryId.value)) error.value = errorText(failure)
  }
}

function refreshFolderTree() {
  if (libraryId.value) void reloadFolders(libraryId.value)
}

const noteDirty = ref(false)
const leavingAfterLogout = ref(false)

async function signOut() {
  if (leavingAfterLogout.value) return
  if (route.name === 'note' && noteDirty.value && !window.confirm('有未保存修改。确定退出登录？')) return
  leavingAfterLogout.value = true
  try {
    await logout()
    await router.replace('/login')
  } catch (failure) {
    error.value = errorText(failure)
  } finally {
    leavingAfterLogout.value = false
  }
}
</script>

<template>
  <div class="workspace-shell">
    <header class="frame-header">
      <RouterLink to="/app" class="wordmark">KnowledgeHub</RouterLink>
      <div class="account-controls">
        <span>{{ activeViewer?.username }}</span>
        <el-button text @click="signOut">退出登录</el-button>
      </div>
    </header>
    <aside class="workspace-rail" aria-label="知识库导航">
      <LibrarySwitcher :libraries="libraries" :selected-id="libraryId" @select="selectLibrary" @create="createLibrary" />
      <FolderTree v-if="library" :folders="folders" :selected-id="folderId" :loading="loadingFolders"
                  @select="selectFolder" />
      <nav class="rail-footer" aria-label="知识库工具">
        <template v-if="library">
          <RouterLink :to="libraryPath(library.id) + '/search'">搜索笔记</RouterLink>
          <RouterLink :to="libraryPath(library.id) + '/content-trash'">内容回收站</RouterLink>
        </template>
        <RouterLink to="/library-trash">知识库回收站</RouterLink>
      </nav>
    </aside>
    <main class="workspace-main">
      <el-alert v-if="error" type="error" :closable="false" :title="error" />
      <RouterView v-if="libraryTrash" v-slot="{ Component }">
        <component :is="Component" @changed="reloadLibraries" />
      </RouterView>
      <template v-else-if="library">
        <nav class="breadcrumbs" aria-label="当前位置">
          <RouterLink :to="libraryPath(library.id)">{{ library.name }}</RouterLink>
          <span v-if="folder" aria-hidden="true">/</span>
          <span v-if="folder">{{ folder.name }}</span>
        </nav>
        <header v-if="listingNotes" class="pane-header">
          <h1>{{ folder?.name ?? library.name }}</h1>
          <div class="actions">
            <el-button @click="createFolder">新建文件夹</el-button>
            <el-button v-if="folder" @click="renameFolder">重命名文件夹</el-button>
            <el-button v-if="folder" @click="startMove">移动文件夹</el-button>
            <el-button v-if="folder" type="danger" plain @click="trashCurrentFolder">回收文件夹</el-button>
            <el-button v-if="!folder" @click="renameLibrary">重命名知识库</el-button>
            <el-button v-if="!folder" type="danger" plain @click="trashCurrentLibrary">回收知识库</el-button>
          </div>
        </header>
        <RouterView v-slot="{ Component }">
          <component :is="Component" v-if="listingNotes" :folders="folders" />
          <component :is="Component" v-else-if="route.name === 'content-trash'" @changed="refreshFolderTree" />
          <component :is="Component" v-else-if="route.name === 'note'" :leaving-after-logout="leavingAfterLogout"
                     @dirty-change="noteDirty = $event" />
          <component :is="Component" v-else />
        </RouterView>
      </template>
      <p v-else-if="loadingLibraries" role="status" class="muted">正在加载知识库</p>
      <div v-else class="empty-state">
        <h1>{{ libraries.length ? '选择一个知识库' : '还没有知识库' }}</h1>
        <p v-if="!libraries.length">从侧栏新建知识库，或到回收站恢复已有的知识库。</p>
      </div>
    </main>
    <el-dialog :model-value="Boolean(movingId)" title="移动文件夹" width="min(420px, 92vw)"
               @update:model-value="onMoveVisibility">
      <label for="folder-destination">目标文件夹</label>
      <el-select id="folder-destination" v-model="destination" style="width: 100%; margin-top: 8px">
        <el-option value="" label="根目录" />
        <el-option v-for="item in moveTargets" :key="item.id" :value="item.id" :label="item.name" />
      </el-select>
      <template #footer>
        <el-button @click="movingId = undefined">取消</el-button>
        <el-button type="primary" :loading="savingMove" @click="moveFolder">移动</el-button>
      </template>
    </el-dialog>
  </div>
</template>
