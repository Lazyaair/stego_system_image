<script setup lang="ts">
import { ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useAuthStore } from '../../stores/auth'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()

const activeTab = ref<'login' | 'register'>(route.path === '/register' ? 'register' : 'login')
const username = ref('')
const password = ref('')
const confirmPassword = ref('')
const error = ref('')
const loading = ref(false)

function switchTab(tab: 'login' | 'register') {
  activeTab.value = tab
  error.value = ''
}

async function handleLogin() {
  if (!username.value.trim() || !password.value) return
  error.value = ''
  loading.value = true
  try {
    await auth.login(username.value.trim(), password.value)
    router.push('/chats')
  } catch (e: any) {
    error.value = e.response?.data?.detail || '登录失败，请检查用户名和密码'
  } finally {
    loading.value = false
  }
}

async function handleRegister() {
  if (!username.value.trim() || !password.value) return
  if (password.value !== confirmPassword.value) {
    error.value = '两次输入的密码不一致'
    return
  }
  error.value = ''
  loading.value = true
  try {
    await auth.register(username.value.trim(), password.value)
    router.push('/chats')
  } catch (e: any) {
    error.value = e.response?.data?.detail || '注册失败'
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="min-h-screen flex flex-col bg-surface text-on-surface font-sans overflow-x-hidden">
    <!-- Atmospheric Background -->
    <div class="fixed inset-0 z-0 pointer-events-none overflow-hidden">
      <div class="absolute top-1/4 -left-20 w-[500px] h-[500px] bg-primary/10 blur-[120px] rounded-full"></div>
      <div class="absolute bottom-1/4 -right-20 w-[400px] h-[400px] bg-tertiary/5 blur-[100px] rounded-full"></div>
      <div class="absolute inset-0 opacity-20" style="background-image: radial-gradient(#424752 0.5px, transparent 0.5px); background-size: 32px 32px;"></div>
    </div>

    <!-- Header -->
    <header class="relative z-50 flex items-center justify-between px-8 h-20">
      <div class="flex items-center gap-2">
        <span class="material-symbols-outlined filled text-primary text-3xl">shield_lock</span>
        <span class="text-2xl font-bold tracking-tight">StegoCrypt</span>
      </div>
    </header>

    <!-- Main -->
    <main class="relative z-10 flex-grow flex items-center justify-center px-4 py-12">
      <div class="w-full max-w-xl glass-card rounded-3xl shadow-2xl overflow-hidden">
        <!-- Brand -->
        <div class="pt-10 pb-6 text-center">
          <div class="inline-flex items-center justify-center p-3 rounded-2xl bg-primary/10 mb-4">
            <span class="material-symbols-outlined text-primary text-4xl">enhanced_encryption</span>
          </div>
          <h1 class="text-3xl font-extrabold tracking-tight mb-2">StegoCrypt</h1>
          <p class="text-on-surface-variant text-sm font-medium tracking-widest uppercase">可证安全图像隐写通信系统</p>
        </div>

        <!-- Tabs -->
        <div class="flex border-b border-outline-variant/20">
          <button
            class="flex-1 py-4 text-sm font-bold uppercase tracking-wider transition-all"
            :class="activeTab === 'login' ? 'text-primary border-b-2 border-primary' : 'text-on-surface-variant hover:text-on-surface'"
            @click="switchTab('login')"
          >登录</button>
          <button
            class="flex-1 py-4 text-sm font-bold uppercase tracking-wider transition-all"
            :class="activeTab === 'register' ? 'text-primary border-b-2 border-primary' : 'text-on-surface-variant hover:text-on-surface'"
            @click="switchTab('register')"
          >注册</button>
        </div>

        <!-- Forms -->
        <div class="p-8 md:p-10">
          <!-- Login Form -->
          <form v-if="activeTab === 'login'" class="space-y-6" @submit.prevent="handleLogin">
            <div class="space-y-2">
              <label class="text-xs font-bold text-on-surface-variant uppercase ml-1">用户名</label>
              <div class="relative group">
                <span class="material-symbols-outlined absolute left-4 top-1/2 -translate-y-1/2 text-on-surface-variant/50 group-focus-within:text-primary transition-colors">person</span>
                <input
                  v-model="username"
                  type="text"
                  placeholder="请输入用户名"
                  class="w-full bg-surface-container/50 border border-outline-variant/20 rounded-xl py-4 pl-12 pr-4 text-on-surface focus:ring-2 focus:ring-primary/40 focus:border-primary transition-all outline-none"
                />
              </div>
            </div>
            <div class="space-y-2">
              <label class="text-xs font-bold text-on-surface-variant uppercase ml-1">密码</label>
              <div class="relative group">
                <span class="material-symbols-outlined absolute left-4 top-1/2 -translate-y-1/2 text-on-surface-variant/50 group-focus-within:text-primary transition-colors">lock</span>
                <input
                  v-model="password"
                  type="password"
                  placeholder="请输入密码"
                  class="w-full bg-surface-container/50 border border-outline-variant/20 rounded-xl py-4 pl-12 pr-4 text-on-surface focus:ring-2 focus:ring-primary/40 focus:border-primary transition-all outline-none"
                />
              </div>
            </div>
            <p v-if="error" class="text-error text-sm">{{ error }}</p>
            <button type="submit" :disabled="loading" class="w-full py-4 bg-primary text-on-primary font-bold rounded-xl shadow-lg shadow-primary/30 hover:bg-primary/90 active:scale-[0.98] transition-all flex items-center justify-center gap-2 disabled:opacity-50">
              <span>{{ loading ? '登录中...' : '立即登录' }}</span>
              <span v-if="!loading" class="material-symbols-outlined text-xl">login</span>
            </button>
            <p class="text-center text-sm text-on-surface-variant">
              没有账号？<button type="button" class="text-primary font-bold hover:underline" @click="switchTab('register')">立即注册</button>
            </p>
          </form>

          <!-- Register Form -->
          <form v-else class="space-y-4" @submit.prevent="handleRegister">
            <div class="space-y-2">
              <label class="text-xs font-bold text-on-surface-variant uppercase ml-1">用户名</label>
              <input
                v-model="username"
                type="text"
                placeholder="请输入用户名"
                class="input-field"
                required
              />
            </div>
            <div class="space-y-2">
              <label class="text-xs font-bold text-on-surface-variant uppercase ml-1">密码</label>
              <input
                v-model="password"
                type="password"
                placeholder="请输入密码"
                class="input-field"
                required
              />
            </div>
            <div class="space-y-2">
              <label class="text-xs font-bold text-on-surface-variant uppercase ml-1">确认密码</label>
              <input
                v-model="confirmPassword"
                type="password"
                placeholder="请再次输入密码"
                class="input-field"
                required
              />
            </div>
            <p v-if="error" class="text-error text-sm">{{ error }}</p>
            <button type="submit" :disabled="loading" class="w-full py-4 mt-2 bg-primary text-on-primary font-bold rounded-xl shadow-lg shadow-primary/30 hover:bg-primary/90 active:scale-[0.98] transition-all flex items-center justify-center gap-2 disabled:opacity-50">
              <span>{{ loading ? '注册中...' : '创建账号' }}</span>
              <span v-if="!loading" class="material-symbols-outlined text-xl">verified_user</span>
            </button>
            <p class="text-center text-sm text-on-surface-variant">
              已有账号？<button type="button" class="text-primary font-bold hover:underline" @click="switchTab('login')">立即登录</button>
            </p>
          </form>
        </div>
      </div>
    </main>

    <!-- Footer -->
    <footer class="relative z-10 w-full py-8 px-8 border-t border-outline-variant/10">
      <div class="max-w-7xl mx-auto flex justify-center">
        <p class="text-[10px] uppercase tracking-[0.2em] font-medium text-on-surface-variant/50">
          © 2025 StegoCrypt. 端到端隐写安全通信协议.
        </p>
      </div>
    </footer>
  </div>
</template>
