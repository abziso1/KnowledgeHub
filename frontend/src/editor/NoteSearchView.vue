<script setup lang="ts">
import { ref, watch } from 'vue'
import { api, errorText } from '../api/client'
import type { Page, SearchHit } from '../api/contracts'
import { libraryPath } from '../router'

const props = defineProps<{ libraryId: string }>()
const term = ref('')
const submitted = ref('')
const result = ref<Page<SearchHit>>()
const loading = ref(false)
const error = ref('')
let latest = 0

watch(() => props.libraryId, () => {
  latest++
  term.value = ''
  submitted.value = ''
  result.value = undefined
  error.value = ''
  loading.value = false
})

function query() {
  if (!term.value.trim()) return
  submitted.value = term.value
  result.value = undefined
  void load(0)
}

async function load(page: number) {
  const request = ++latest
  const query = new URLSearchParams({ q: submitted.value, page: String(page) })
  loading.value = true
  error.value = ''
  try {
    const items = await api<Page<SearchHit>>('/api/libraries/' + props.libraryId + '/search?' + query)
    if (latest === request) result.value = items
  } catch (failure) {
    if (latest === request) error.value = errorText(failure)
  } finally {
    if (latest === request) loading.value = false
  }
}
</script>

<template>
  <section>
    <header class="pane-header"><h1>搜索笔记</h1></header>
    <form class="search-form" @submit.prevent="query">
      <el-input v-model="term" aria-label="搜索关键词" placeholder="搜索标题或已保存的正文" />
      <el-button type="primary" native-type="submit" :loading="loading" :disabled="!term.trim()">搜索</el-button>
    </form>
    <el-alert v-if="error" type="error" :title="error" :closable="false" />
    <p v-if="result && !result.items.length" class="muted list-empty">当前知识库没有符合条件的笔记。</p>
    <ul v-else-if="result" class="record-list" aria-label="搜索结果">
      <li v-for="note in result.items" :key="note.id">
        <div>
          <RouterLink class="record-title" :to="libraryPath(libraryId) + '/notes/' + note.id">{{ note.title }}</RouterLink>
          <span class="record-meta">{{ note.folderPath.length ? note.folderPath.join(' / ') : '根目录' }}</span>
        </div>
        <time class="record-meta" :datetime="note.updatedAt">{{ new Date(note.updatedAt).toLocaleString('zh-CN') }}</time>
      </li>
    </ul>
    <nav v-if="result && (result.page || result.hasNext)" class="pagination" aria-label="搜索分页">
      <el-button :disabled="loading || result.page === 0" @click="load(result.page - 1)">上一页</el-button>
      <span>第 {{ result.page + 1 }} 页</span>
      <el-button :disabled="loading || !result.hasNext" @click="load(result.page + 1)">下一页</el-button>
    </nav>
  </section>
</template>
