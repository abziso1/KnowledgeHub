<script setup lang="ts">
import { ElMessageBox } from 'element-plus'
import { computed, ref, watch } from 'vue'
import { api, errorText } from '../api/client'
import type { ContentTrashItem, Page, TrashedLibrary } from '../api/contracts'

const props = defineProps<{ scope: 'LIBRARY' | 'CONTENT'; libraryId?: string }>()
const emit = defineEmits<{ changed: [] }>()
type TrashItem = ContentTrashItem | TrashedLibrary
const base = computed(() => props.scope === 'LIBRARY' ? '/api/library-trash'
  : '/api/libraries/' + props.libraryId + '/content-trash')
const page = ref<Page<TrashItem>>()
const loading = ref(false)
const applying = ref<string>()
const error = ref('')
let latest = 0

watch(base, () => {
  page.value = undefined
  void load()
}, { immediate: true })

async function load(index = 0) {
  const request = ++latest
  loading.value = true
  error.value = ''
  try {
    const result = await api<Page<TrashItem>>(base.value + '?page=' + index)
    if (request === latest) page.value = result
  } catch (failure) {
    if (request === latest) error.value = errorText(failure)
  } finally {
    if (request === latest) loading.value = false
  }
}

function label(item: TrashItem) {
  if (!('kind' in item)) return '知识库'
  return item.kind === 'FOLDER' ? '文件夹' : '笔记'
}

async function apply(item: TrashItem, action: 'restore' | 'purge') {
  if (applying.value) return
  const endpoint = base.value
  const permanent = action === 'purge'
  if (permanent) {
    let impact = '该笔记将无法恢复。'
    if (!('kind' in item)) impact = '其中的笔记、文件夹、图片和内容回收记录将无法恢复。'
    else if (item.kind === 'FOLDER') impact = '该文件夹及随它移入回收站的内容将无法恢复。'
    try {
      await ElMessageBox.confirm(`永久删除${label(item)}「${item.name}」？${impact}`, '永久删除',
        { confirmButtonText: '永久删除', cancelButtonText: '取消', type: 'warning' })
    } catch {
      return
    }
  }

  applying.value = item.id
  error.value = ''
  try {
    await api(endpoint + '/' + item.id + '/' + action,
      { method: 'POST', body: permanent ? JSON.stringify({ confirm: true }) : undefined })
    if (endpoint !== base.value) return
    emit('changed')
    const current = page.value?.page ?? 0
    await load(current > 0 && page.value?.items.length === 1 && !page.value.hasNext ? current - 1 : current)
  } catch (failure) {
    if (endpoint === base.value) error.value = errorText(failure)
  } finally {
    applying.value = undefined
  }
}
</script>

<template>
  <section class="trash-view">
    <header class="pane-header"><h1>{{ scope === 'LIBRARY' ? '知识库回收站' : '内容回收站' }}</h1></header>
    <el-alert v-if="error" type="error" :title="error" :closable="false" />
    <p v-if="loading && !page" class="muted" role="status">正在加载回收站</p>
    <p v-else-if="page && !page.items.length" class="muted list-empty">回收站为空。</p>
    <ul v-else-if="page" class="record-list" aria-label="回收条目">
      <li v-for="item in page.items" :key="item.id">
        <div>
          <span class="record-title">{{ item.name }}</span>
          <span class="record-meta">{{ label(item) }} · {{ new Date(item.trashedAt).toLocaleString('zh-CN') }}</span>
        </div>
        <div class="actions">
          <el-button text :disabled="Boolean(applying)" @click="apply(item, 'restore')">恢复</el-button>
          <el-button text type="danger" :disabled="Boolean(applying)" @click="apply(item, 'purge')">永久删除</el-button>
        </div>
      </li>
    </ul>
    <nav v-if="page && (page.page || page.hasNext)" class="pagination" aria-label="回收站分页">
      <el-button :disabled="loading || page.page === 0" @click="load(page.page - 1)">上一页</el-button>
      <span>第 {{ page.page + 1 }} 页</span>
      <el-button :disabled="loading || !page.hasNext" @click="load(page.page + 1)">下一页</el-button>
    </nav>
  </section>
</template>
