<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { useAuthStore } from '../../stores/auth'
import { useSettingsStore } from '../../stores/settings'
import { getMyCode, resetCode } from '../../api/invite'

const router = useRouter()
const auth = useAuthStore()
const settings = useSettingsStore()

const inviteCode = ref('')
const inviteLink = ref('')
const loading = ref(false)

// E2EE phrase 表单状态
const phraseInput = ref('')
const savingPhrase = ref(false)
const phraseError = ref('')

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

// 指纹 hex 转成 a1:b2:c3... 展示
function formatFingerprint(hex: string): string {
  if (!hex) return ''
  const pairs: string[] = []
  for (let i = 0; i < hex.length; i += 2) pairs.push(hex.slice(i, i + 2))
  return pairs.join(':')
}

const fingerprintPretty = computed(() => formatFingerprint(settings.fingerprintHex))

async function handleSavePhrase() {
  phraseError.value = ''
  const val = phraseInput.value.trim()
  if (!val) {
    phraseError.value = '请输入加密助记词'
    return
  }
  savingPhrase.value = true
  try {
    await settings.setPhrase(val)
    phraseInput.value = ''
  } catch (e: unknown) {
    phraseError.value = e instanceof Error ? e.message : String(e)
  } finally {
    savingPhrase.value = false
  }
}

async function handleResetPhrase() {
  if (!window.confirm('确定要重置加密助记词吗？之前加密的消息可能无法再解密。')) return
  await settings.clear()
  phraseInput.value = ''
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

    <!-- E2EE Phrase -->
    <section class="space-y-4">
      <div class="flex items-center justify-between">
        <h2 class="section-title">加密助记词</h2>
        <span
          v-if="settings.hasE2EE"
          class="inline-flex items-center gap-1 px-2 py-0.5 rounded-md bg-tertiary-container text-on-tertiary-container text-[10px] font-bold tracking-wide"
        >
          <span class="material-symbols-outlined text-xs">verified</span>
          已配置
        </span>
        <span
          v-else
          class="inline-flex items-center gap-1 px-2 py-0.5 rounded-md bg-error-container text-on-error-container text-[10px] font-bold tracking-wide"
        >
          <span class="material-symbols-outlined text-xs">warning</span>
          未配置
        </span>
      </div>
      <div class="bg-surface-container rounded-xl p-6 space-y-4">
        <p class="text-sm text-on-surface-variant leading-relaxed">
          助记词仅保存在本机,用于派生端到端加密密钥。请告知联系人以便互相解密消息。
        </p>
        <div class="space-y-2">
          <input
            v-model="phraseInput"
            type="text"
            class="input-field"
            placeholder="输入任意助记词,例如：sunrise-river-42"
            :disabled="savingPhrase"
            @keyup.enter="handleSavePhrase"
          />
          <p v-if="phraseError" class="text-xs text-error">{{ phraseError }}</p>
        </div>
        <div class="flex items-center gap-3">
          <button
            class="btn-primary text-sm"
            :disabled="savingPhrase || !phraseInput.trim()"
            @click="handleSavePhrase"
          >
            <span class="material-symbols-outlined text-sm mr-1">save</span>
            {{ savingPhrase ? '保存中...' : '保存' }}
          </button>
          <button
            v-if="settings.hasE2EE"
            class="btn-ghost text-sm"
            :disabled="savingPhrase"
            @click="handleResetPhrase"
          >
            重置
          </button>
        </div>
        <div v-if="settings.hasE2EE" class="pt-3 border-t border-outline-variant/10 space-y-1">
          <p class="text-[10px] uppercase tracking-widest text-on-surface-variant font-bold">
            指纹 (分享给对方核对)
          </p>
          <code
            class="block font-mono text-xs text-tertiary tracking-widest break-all bg-surface-container-lowest px-3 py-2 rounded-lg"
          >{{ fingerprintPretty }}</code>
        </div>
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
