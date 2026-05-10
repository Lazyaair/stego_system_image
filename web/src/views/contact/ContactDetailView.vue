<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useContactsStore } from '../../stores/contacts'

const route = useRoute()
const router = useRouter()
const contactsStore = useContactsStore()

const contactId = computed(() => String(route.params.id ?? ''))
const phraseInput = ref('')
const saving = ref(false)
const errorMsg = ref('')

onMounted(async () => {
  if (contactsStore.contacts.length === 0) {
    await contactsStore.loadContacts()
  }
})

const contact = computed(() => contactsStore.getContactById(contactId.value))

function formatFingerprint(hex: string | undefined): string {
  if (!hex) return ''
  const pairs: string[] = []
  for (let i = 0; i < hex.length; i += 2) pairs.push(hex.slice(i, i + 2))
  return pairs.join(':')
}

const peerFingerprintPretty = computed(() =>
  formatFingerprint(contact.value?.peerFingerprintHex),
)

const hasPeerE2EE = computed(
  () => !!contact.value && !!contact.value.peerUserKeyHex,
)

async function handleSavePhrase() {
  errorMsg.value = ''
  const val = phraseInput.value.trim()
  if (!val) {
    errorMsg.value = '请输入对方的加密助记词'
    return
  }
  saving.value = true
  try {
    await contactsStore.setPeerPhrase(contactId.value, val)
    phraseInput.value = ''
  } catch (e: unknown) {
    errorMsg.value = e instanceof Error ? e.message : String(e)
  } finally {
    saving.value = false
  }
}

function goBack() {
  router.push('/contacts')
}

function openChat() {
  router.push(`/chat/${contactId.value}`)
}
</script>

<template>
  <div class="max-w-2xl mx-auto py-8 px-6 space-y-8">
    <!-- Header -->
    <div class="flex items-center gap-3 mb-2">
      <button
        class="w-10 h-10 rounded-xl bg-surface-container hover:bg-surface-container-high transition-colors flex items-center justify-center"
        @click="goBack"
        aria-label="返回"
      >
        <span class="material-symbols-outlined">arrow_back</span>
      </button>
      <div class="flex-1">
        <h1 class="text-2xl font-extrabold tracking-tight text-on-surface">联系人详情</h1>
        <p class="text-on-surface-variant text-sm opacity-70">管理与此联系人的加密通信</p>
      </div>
    </div>

    <div v-if="!contact" class="bg-surface-container rounded-xl p-8 text-center text-on-surface-variant">
      未找到该联系人
    </div>

    <template v-else>
      <!-- Profile card -->
      <section class="space-y-4">
        <h2 class="section-title">基本信息</h2>
        <div class="bg-surface-container rounded-xl p-6 flex items-center gap-5">
          <div class="w-16 h-16 rounded-2xl bg-secondary-container text-on-secondary-container flex items-center justify-center text-2xl font-bold">
            {{ (contact.nickname || contact.username)[0].toUpperCase() }}
          </div>
          <div class="flex-1 min-w-0">
            <h3 class="text-lg font-bold text-on-surface truncate">
              {{ contact.nickname || contact.username }}
            </h3>
            <p class="text-sm text-on-surface-variant">@{{ contact.username }}</p>
          </div>
          <button
            class="btn-ghost text-sm"
            @click="openChat"
          >
            <span class="material-symbols-outlined text-sm mr-1">chat</span>
            打开聊天
          </button>
        </div>
      </section>

      <!-- Peer phrase -->
      <section class="space-y-4">
        <div class="flex items-center justify-between">
          <h2 class="section-title">对方的加密助记词</h2>
          <span
            v-if="hasPeerE2EE"
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
            输入对方告知你的助记词,用于派生对方的公开 key 以完成端到端加密握手。
          </p>
          <div class="space-y-2">
            <input
              v-model="phraseInput"
              type="text"
              class="input-field"
              placeholder="例如：sunset-ocean-7"
              :disabled="saving"
              @keyup.enter="handleSavePhrase"
            />
            <p v-if="errorMsg" class="text-xs text-error">{{ errorMsg }}</p>
          </div>
          <div class="flex items-center gap-3">
            <button
              class="btn-primary text-sm"
              :disabled="saving || !phraseInput.trim()"
              @click="handleSavePhrase"
            >
              <span class="material-symbols-outlined text-sm mr-1">save</span>
              {{ saving ? '保存中...' : '保存' }}
            </button>
          </div>
          <div
            v-if="hasPeerE2EE"
            class="pt-3 border-t border-outline-variant/10 space-y-1"
          >
            <p class="text-[10px] uppercase tracking-widest text-on-surface-variant font-bold">
              对方指纹
            </p>
            <code
              class="block font-mono text-xs text-tertiary tracking-widest break-all bg-surface-container-lowest px-3 py-2 rounded-lg"
            >{{ peerFingerprintPretty }}</code>
          </div>
        </div>
      </section>
    </template>
  </div>
</template>
