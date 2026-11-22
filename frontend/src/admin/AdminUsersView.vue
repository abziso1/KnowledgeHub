<script setup lang="ts">
import { ElMessageBox } from 'element-plus'
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { api, errorText } from '../api/client'
import type { AdminUser, Page } from '../api/contracts'
import { activeViewer, logout } from '../auth/session'

const router = useRouter()
const result = ref<Page<AdminUser>>()
const busy = ref(false)
const error = ref('')

async function load(page = 0) {
  busy.value = true
  error.value = ''
  try {
    result.value = await api<Page<AdminUser>>('/api/admin/users?page=' + page)
  } catch (failure) {
    error.value = errorText(failure)
  } finally {
    busy.value = false
  }
}

async function changeStatus(user: AdminUser) {
  const action = user.enabled ? '停用' : '恢复'
  try {
    await ElMessageBox.confirm(
      user.enabled ? `停用 ${user.username} 后，其在线会话会失效。` : `恢复后，${user.username} 可以重新登录。`,
      action + '账号', { type: 'warning', confirmButtonText: action, cancelButtonText: '取消' },
    )
  } catch {
    return
  }
  busy.value = true
  error.value = ''
  try {
    await api('/api/admin/users/' + user.id + '/status', {
      method: 'PUT', body: JSON.stringify({ enabled: !user.enabled }),
    })
    await load(result.value?.page)
  } catch (failure) {
    error.value = errorText(failure)
  } finally {
    busy.value = false
  }
}

async function signOut() {
  try {
    await logout()
    await router.replace('/login')
  } catch (failure) {
    error.value = errorText(failure)
  }
}

onMounted(() => load())
</script>

<template>
  <div class="admin-shell">
    <header class="frame-header">
      <span class="wordmark">KnowledgeHub</span>
      <div class="account-controls">
        <span>{{ activeViewer?.username }}</span>
        <el-button text @click="signOut">退出登录</el-button>
      </div>
    </header>
    <main class="admin-main">
      <header class="pane-header"><h1>账号管理</h1></header>
      <el-alert v-if="error" type="error" :closable="false" :title="error" />
      <p v-if="busy && !result" role="status">正在加载账号</p>
      <p v-else-if="result && !result.items.length" class="muted">暂无普通用户。</p>
      <div v-else-if="result" class="table-wrap">
        <table class="data-table">
          <thead><tr><th scope="col">用户名</th><th scope="col">状态</th><th scope="col">注册时间</th><th scope="col">操作</th></tr></thead>
          <tbody>
            <tr v-for="user in result.items" :key="user.id">
              <th scope="row">{{ user.username }}</th>
              <td><el-tag :type="user.enabled ? 'success' : 'info'">{{ user.enabled ? '已启用' : '已停用' }}</el-tag></td>
              <td>{{ new Date(user.createdAt).toLocaleString('zh-CN') }}</td>
              <td><el-button :type="user.enabled ? 'danger' : 'primary'" text :disabled="busy" @click="changeStatus(user)">
                {{ user.enabled ? '停用账号' : '恢复账号' }}
              </el-button></td>
            </tr>
          </tbody>
        </table>
      </div>
      <nav v-if="result && (result.hasNext || result.page)" class="pagination" aria-label="账号分页">
        <el-button :disabled="busy || result.page === 0" @click="load(result.page - 1)">上一页</el-button>
        <span>第 {{ result.page + 1 }} 页</span>
        <el-button :disabled="busy || !result.hasNext" @click="load(result.page + 1)">下一页</el-button>
      </nav>
    </main>
  </div>
</template>
