<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { useAuthStore } from '../../stores/auth'
import { getMyCode, resetCode } from '../../api/invite'

const router = useRouter()
const auth = useAuthStore()

const inviteCode = ref('')
const inviteLink = ref('')
const loading = ref(false)

onMounted(async () => {
  try {
    const res = await getMyCode()
    inviteCode.value = res.code
    inviteLink.value = res.link
  } catch {
    // ignore
  }
})

async function handleResetCode() {
  loading.value = true
  try {
    const res = await resetCode()
    inviteCode.value = res.code
    inviteLink.value = res.link
  } finally {
    loading.value = false
  }
}

function copyCode() {
  navigator.clipboard.writeText(inviteCode.value)
}

async function handleLogout() {
  await auth.logout()
  router.push('/login')
}
</script>

<template>
  <div class="max-w-2xl mx-auto py-8 px-6 space-y-8">
    <!-- Page Header -->
    <div class="mb-6">
      <h1 class="text-3xl font-extrabold tracking-tight text-on-surface mb-1">设置</h1>
      <p class="text-on-surface-variant text-sm font-medium opacity-70">管理您的账号与通信安全</p>
    </div>

    <!-- Profile Section -->
    <section class="space-y-4">
      <h2 class="section-title">个人资料</h2>
      <div class="bg-surface-container rounded-xl p-8 transition-all hover:bg-surface-container-high">
        <div class="flex items-center gap-6">
          <div class="w-20 h-20 rounded-2xl bg-primary-container flex items-center justify-center text-on-primary-container text-3xl font-bold shadow-lg">
            {{ auth.user?.username?.[0]?.toUpperCase() }}
          </div>
          <div class="flex-1 space-y-1">
            <h3 class="text-xl font-bold text-on-surface">{{ auth.user?.username }}</h3>
            <div class="pt-2 flex flex-wrap gap-2">
              <div class="flex items-center gap-3 bg-surface-container-lowest px-4 py-2 rounded-lg border border-outline-variant/10">
                <span class="text-[10px] uppercase tracking-tighter text-on-surface-variant font-bold">邀请码</span>
                <span class="font-mono text-tertiary font-bold tracking-widest">{{ inviteCode }}</span>
                <button @click="copyCode" class="hover:text-primary transition-colors" title="复制">
                  <span class="material-symbols-outlined text-lg">content_copy</span>
                </button>
              </div>
              <button
                @click="handleResetCode"
                :disabled="loading"
                class="flex items-center gap-1 px-3 py-2 rounded-lg bg-surface-container-lowest border border-outline-variant/10 text-xs font-medium hover:bg-surface-variant transition-colors disabled:opacity-40"
              >
                <span class="material-symbols-outlined text-sm">refresh</span>
                {{ loading ? '重置中...' : '重置邀请码' }}
              </button>
            </div>
          </div>
        </div>
      </div>
    </section>

    <!-- Account Security -->
    <section class="space-y-4">
      <h2 class="section-title">账号安全</h2>
      <div class="bg-surface-container rounded-xl overflow-hidden">
        <button class="w-full flex items-center justify-between px-6 py-5 hover:bg-surface-container-high transition-colors group">
          <div class="flex items-center gap-4">
            <div class="w-10 h-10 rounded-lg bg-surface-container-low flex items-center justify-center text-primary">
              <span class="material-symbols-outlined">lock_reset</span>
            </div>
            <div class="text-left">
              <p class="font-semibold text-on-surface">修改密码</p>
              <p class="text-xs text-on-surface-variant">定期更换密码以确保账号安全</p>
            </div>
          </div>
          <span class="material-symbols-outlined text-on-surface-variant group-hover:translate-x-1 transition-transform">chevron_right</span>
        </button>
      </div>
    </section>

    <!-- About -->
    <section class="space-y-4">
      <h2 class="section-title">关于</h2>
      <div class="bg-surface-container rounded-xl p-6 space-y-4">
        <div class="flex items-start gap-4">
          <div class="w-12 h-12 bg-primary rounded-xl flex items-center justify-center shadow-lg shadow-primary/20">
            <span class="material-symbols-outlined filled text-on-primary text-2xl">shield</span>
          </div>
          <div>
            <div class="flex items-center gap-2 mb-1">
              <h3 class="font-bold text-lg text-on-surface">StegoCrypt</h3>
              <span class="text-xs bg-surface-container-highest px-2 py-0.5 rounded text-secondary font-mono">v1.0.0</span>
            </div>
            <p class="text-on-surface-variant text-sm leading-relaxed">
              可证安全图像隐写通信系统，基于 Pulsar 算法实现高安全性与不可感知性的信息隐藏。
            </p>
          </div>
        </div>
      </div>
    </section>

    <!-- Logout -->
    <section class="pt-4">
      <button
        @click="handleLogout"
        class="w-full flex items-center justify-center gap-3 bg-surface-container-lowest text-error border border-error/20 py-4 rounded-xl font-bold hover:bg-error/5 active:scale-[0.98] transition-all group"
      >
        <span class="material-symbols-outlined group-hover:rotate-12 transition-transform">logout</span>
        退出登录
      </button>
      <div class="mt-6 text-center">
        <p class="text-[10px] text-on-surface-variant font-bold tracking-[0.2em] opacity-30 uppercase">StegoCrypt © 2025</p>
      </div>
    </section>
  </div>
</template>
