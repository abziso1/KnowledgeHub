<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { errorText } from '../api/client'
import { resolveHome } from '../router'
import { login, register } from './session'

const route = useRoute()
const router = useRouter()
const isRegistration = computed(() => route.name === 'register')
const username = ref('')
const password = ref('')
const error = ref('')
const busy = ref(false)

watch(isRegistration, () => {
  error.value = ''
  password.value = ''
})

async function submit() {
  if (busy.value) return
  error.value = ''
  busy.value = true
  try {
    if (isRegistration.value) {
      await register(username.value, password.value)
      await router.replace({ name: 'login', query: { registered: '1' } })
    } else {
      const viewer = await login(username.value, password.value)
      await router.replace(resolveHome(viewer, []))
    }
  } catch (failure) {
    error.value = errorText(failure)
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <main class="auth-shell">
    <section class="auth-identity" aria-label="KnowledgeHub">
      <span class="wordmark">KnowledgeHub</span>
      <div class="identity-title">个人知识库</div>
      <p>整理 Markdown 笔记与资料。</p>
    </section>
    <section class="auth-panel">
      <div class="auth-form">
        <h1>{{ isRegistration ? '注册账号' : '登录' }}</h1>
        <el-alert v-if="route.query.registered === '1'" type="success" :closable="false" title="账号已创建，请登录。" />
        <el-alert v-if="route.query.unavailable === '1'" type="warning" :closable="false" title="暂时无法连接服务。" />
        <el-alert v-if="error" type="error" :closable="false" :title="error" />
        <el-form label-position="top" @submit.prevent="submit">
          <el-form-item label="用户名" for="username">
            <el-input id="username" v-model="username" autocomplete="username" required maxlength="80" />
          </el-form-item>
          <el-form-item label="密码" for="password">
            <el-input id="password" v-model="password" type="password"
                      :autocomplete="isRegistration ? 'new-password' : 'current-password'" required />
          </el-form-item>
          <el-button native-type="submit" type="primary" :loading="busy" class="auth-action">
            {{ isRegistration ? '注册' : '登录' }}
          </el-button>
        </el-form>
        <RouterLink :to="isRegistration ? '/login' : '/register'">
          {{ isRegistration ? '返回登录' : '注册账号' }}
        </RouterLink>
      </div>
    </section>
  </main>
</template>
