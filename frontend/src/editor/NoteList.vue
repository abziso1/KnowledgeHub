<script setup lang="ts">
import { ElMessageBox } from 'element-plus'
import { computed, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { api, errorText } from '../api/client'
import type { FolderView, NoteSummary, NoteView, Page } from '../api/contracts'
import { libraryPath } from '../router'

const props = defineProps<{ libraryId: string; folderId: string | null; folders: FolderView[] }>()
const router = useRouter()
const result = ref<Page<NoteSummary>>()
const error = ref('')
const loading = ref(false)
const creating = ref(false)
const importing = ref(false)
const importInput = ref<HTMLInputElement>()
const moveOpen = ref(false)
const moving = ref<NoteSummary>()
const destination = ref('')
const movingBusy = ref(false)
const base = computed(() => '/api/libraries/' + props.libraryId + '/notes')
const notePath = (id: string) => libraryPath(props.libraryId) + '/notes/' + id

let loadId = 0
async function load(page = 0) {
  const request = ++loadId
  loading.value = true
  error.value = ''
  const query = new URLSearchParams({ page: String(page) })
  if (props.folderId) query.set('folderId', props.folderId)
  try {
    const items = await api<Page<NoteSummary>>(base.value + '?' + query)
    if (request === loadId) result.value = items
  } catch (failure) {
    if (request === loadId) error.value = errorText(failure)
  } finally {
    if (request === loadId) loading.value = false
  }
}

watch(() => [props.libraryId, props.folderId], () => {
  result.value = undefined
  moveOpen.value = false
  void load()
}, { immediate: true })

async function createNote() {
  const currentBase = base.value
  const folderId = props.folderId
  let title: string
  try {
    const value = await ElMessageBox.prompt('标题', '新建笔记', {
      confirmButtonText: '确定', cancelButtonText: '取消',
      inputValidator: input => Boolean(input.trim()) || '请输入标题',
    })
    title = (value as unknown as { value: string }).value.trim()
  } catch {
    return
  }
  if (base.value !== currentBase || props.folderId !== folderId) return
  creating.value = true
  error.value = ''
  try {
    const note = await api<NoteView>(currentBase, { method: 'POST', body: JSON.stringify({ folderId, title }) })
    if (base.value === currentBase && props.folderId === folderId) await router.push(notePath(note.id))
  } catch (failure) {
    if (base.value === currentBase) error.value = errorText(failure)
  } finally {
    creating.value = false
  }
}

async function importNote(event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  input.value = ''
  if (!file || importing.value) return
  const currentBase = base.value
  const folderId = props.folderId
  const body = new FormData()
  body.set('file', file)
  if (folderId) body.set('folderId', folderId)
  importing.value = true
  error.value = ''
  try {
    const note = await api<NoteView>(currentBase + '/import', { method: 'POST', body })
    if (base.value === currentBase && props.folderId === folderId) await router.push(notePath(note.id))
  } catch (failure) {
    if (base.value === currentBase) error.value = errorText(failure)
  } finally {
    importing.value = false
  }
}

function beginMove(note: NoteSummary) {
  moving.value = note
  destination.value = note.folderId ?? ''
  moveOpen.value = true
}

async function moveNote() {
  const note = moving.value
  const currentBase = base.value
  const folderId = props.folderId
  if (!note || movingBusy.value) return
  movingBusy.value = true
  try {
    await api<NoteView>(currentBase + '/' + note.id + '/folder', {
      method: 'PUT', body: JSON.stringify({ version: note.version, folderId: destination.value || null }),
    })
    moveOpen.value = false
    if (base.value === currentBase && props.folderId === folderId) await reloadAfterChange()
  } catch (failure) {
    if (base.value === currentBase) error.value = errorText(failure)
  } finally {
    movingBusy.value = false
  }
}

async function trashNote(note: NoteSummary) {
  const currentBase = base.value
  const folderId = props.folderId
  try {
    await ElMessageBox.confirm(`将「${note.title}」移入内容回收站？`, '移入回收站',
      { confirmButtonText: '移入回收站', cancelButtonText: '取消', type: 'warning' })
  } catch {
    return
  }
  try {
    await api(currentBase + '/' + note.id, { method: 'DELETE' })
    if (base.value === currentBase && props.folderId === folderId) await reloadAfterChange()
  } catch (failure) {
    if (base.value === currentBase) error.value = errorText(failure)
  }
}

function reloadAfterChange() {
  const page = result.value?.page ?? 0
  return load(page > 0 && result.value?.items.length === 1 ? page - 1 : page)
}
</script>

<template>
  <section class="note-list" aria-label="笔记列表">
    <div class="list-toolbar">
      <h2>笔记</h2>
      <div class="actions">
        <el-button :loading="importing" @click="importInput?.click()">导入 Markdown</el-button>
        <el-button type="primary" :loading="creating" @click="createNote">新建笔记</el-button>
        <input ref="importInput" type="file" accept=".md,text/markdown" aria-label="导入 Markdown 文件"
               hidden @change="importNote" />
      </div>
    </div>
    <el-alert v-if="error" type="error" :title="error" :closable="false" />
    <p v-if="loading && !result" role="status" class="muted">正在加载笔记</p>
    <p v-else-if="result && !result.items.length" class="muted list-empty">此目录暂无笔记。</p>
    <ul v-else-if="result" class="record-list">
      <li v-for="note in result.items" :key="note.id">
        <div>
          <RouterLink class="record-title" :to="notePath(note.id)">{{ note.title }}</RouterLink>
          <time class="record-meta" :datetime="note.updatedAt">{{ new Date(note.updatedAt).toLocaleString('zh-CN') }}</time>
        </div>
        <div class="actions">
          <el-button text size="small" @click="beginMove(note)">移动笔记</el-button>
          <el-button text size="small" type="danger" @click="trashNote(note)">移入回收站</el-button>
        </div>
      </li>
    </ul>
    <nav v-if="result && (result.page || result.hasNext)" class="pagination" aria-label="笔记分页">
      <el-button :disabled="loading || result.page === 0" @click="load(result.page - 1)">上一页</el-button>
      <span>第 {{ result.page + 1 }} 页</span>
      <el-button :disabled="loading || !result.hasNext" @click="load(result.page + 1)">下一页</el-button>
    </nav>
    <el-dialog v-model="moveOpen" title="移动笔记" width="min(420px, 92vw)">
      <label class="field-label" for="note-destination">目标文件夹</label>
      <el-select id="note-destination" v-model="destination" style="width: 100%">
        <el-option value="" label="根目录" />
        <el-option v-for="folder in folders" :key="folder.id" :value="folder.id" :label="folder.name" />
      </el-select>
      <template #footer>
        <el-button @click="moveOpen = false">取消</el-button>
        <el-button type="primary" :loading="movingBusy" @click="moveNote">移动</el-button>
      </template>
    </el-dialog>
  </section>
</template>
