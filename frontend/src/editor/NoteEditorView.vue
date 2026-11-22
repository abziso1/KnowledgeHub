<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, ref, shallowRef, watch } from 'vue'
import { onBeforeRouteLeave, onBeforeRouteUpdate } from 'vue-router'
import { api, ApiError, errorText } from '../api/client'
import type { ImageView, NoteView } from '../api/contracts'
import { activeViewer } from '../auth/session'
import { libraryPath } from '../router'
import MarkdownPreview from './MarkdownPreview.vue'
import { useNoteDraft } from './useNoteDraft'

const props = defineProps<{ libraryId: string; noteId: string }>()
const draft = shallowRef<ReturnType<typeof useNoteDraft>>()
const error = ref('')
const notice = ref('')
const loading = ref(false)
const uploading = ref(false)
const editor = ref<HTMLTextAreaElement>()
const imageInput = ref<HTMLInputElement>()
const base = computed(() => '/api/libraries/' + props.libraryId)
const path = computed(() => base.value + '/notes/' + props.noteId)

watch(path, async (target, _before, onCleanup) => {
  const controller = new AbortController()
  onCleanup(() => controller.abort())
  draft.value = undefined
  error.value = ''
  notice.value = ''
  loading.value = true
  try {
    const note = await api<NoteView>(target, { signal: controller.signal })
    if (!controller.signal.aborted) draft.value = useNoteDraft(note)
  } catch (failure) {
    if (!controller.signal.aborted) error.value = errorText(failure)
  } finally {
    if (!controller.signal.aborted) loading.value = false
  }
}, { immediate: true })

function allowNavigation() {
  return !draft.value?.dirty.value || window.confirm('有未保存修改。确定离开笔记？')
}

onBeforeRouteLeave(to => to.name === 'login' && !activeViewer.value || allowNavigation())
onBeforeRouteUpdate((to, from) => to.params.noteId === from.params.noteId
  && to.params.libraryId === from.params.libraryId || allowNavigation())

function beforeUnload(event: BeforeUnloadEvent) {
  if (!draft.value?.dirty.value) return
  event.preventDefault()
  event.returnValue = ''
}

onMounted(() => window.addEventListener('beforeunload', beforeUnload))
onUnmounted(() => window.removeEventListener('beforeunload', beforeUnload))

async function saveNote() {
  const current = draft.value
  if (!current) return
  const target = path.value
  error.value = ''
  notice.value = ''
  try {
    await current.save(input => api<NoteView>(target, { method: 'PUT', body: JSON.stringify(input) }))
    if (current === draft.value) notice.value = current.dirty.value ? '已保存上一版，仍有未保存修改。' : '已保存'
  } catch (failure) {
    if (current !== draft.value) return
    error.value = failure instanceof ApiError && failure.code === 'VERSION_CONFLICT'
      ? '笔记已有更新。当前草稿已保留，可先复制内容，再重新打开笔记处理冲突。'
      : errorText(failure)
  }
}

async function uploadImage(event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  input.value = ''
  const current = draft.value
  if (!file || !current || uploading.value) return
  const body = new FormData()
  body.set('file', file)
  error.value = ''
  notice.value = ''
  uploading.value = true

  try {
    const image = await api<ImageView>(base.value + '/images', { method: 'POST', body })
    if (current !== draft.value || !editor.value) return
    const text = current.draftMarkdown.value
    const start = editor.value.selectionStart
    const end = editor.value.selectionEnd
    const alt = file.name.replace(/\.[^.]+$/, '').replace(/[\[\]\\\r\n]/g, ' ').trim() || '图片'
    const markup = '![' + alt + '](' + image.url + ')'
    current.draftMarkdown.value = text.slice(0, start) + markup + text.slice(end)
    await nextTick()
    editor.value?.focus()
    editor.value?.setSelectionRange(start + markup.length, start + markup.length)
  } catch (failure) {
    if (current === draft.value) error.value = errorText(failure)
  } finally {
    uploading.value = false
  }
}
</script>

<template>
  <section class="note-editor">
    <p v-if="loading" role="status" class="muted">正在加载笔记</p>
    <el-alert v-if="error" type="error" :title="error" :closable="false" />
    <template v-if="draft">
      <header class="pane-header">
        <div class="editor-heading">
          <RouterLink :to="libraryPath(libraryId, draft.savedNote.value.folderId ?? undefined)">返回笔记列表</RouterLink>
          <h1>{{ draft.savedNote.value.title }}</h1>
        </div>
        <div class="actions">
          <span v-if="draft.dirty.value" class="draft-status" role="status">未保存修改</span>
          <span v-else-if="notice" class="muted" role="status">{{ notice }}</span>
          <el-button @click="imageInput?.click()" :loading="uploading">上传图片</el-button>
          <el-button type="primary" :disabled="!draft.dirty.value" :loading="draft.saving.value" @click="saveNote">
            保存
          </el-button>
          <input ref="imageInput" type="file" hidden aria-label="上传笔记图片"
                 accept="image/png,image/jpeg,image/gif,image/webp" @change="uploadImage" />
        </div>
      </header>
      <label for="note-title" class="field-label">标题</label>
      <el-input id="note-title" v-model="draft.draftTitle.value" maxlength="120" />
      <div class="editor-split">
        <div class="editor-source">
          <label for="note-markdown" class="editor-pane-label">Markdown 正文</label>
          <textarea id="note-markdown" ref="editor" v-model="draft.draftMarkdown.value" spellcheck="false" />
        </div>
        <MarkdownPreview :markdown="draft.draftMarkdown.value" :library-id="libraryId" />
      </div>
    </template>
  </section>
</template>
