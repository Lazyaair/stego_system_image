<script setup lang="ts">
import { ref, computed, onMounted, onBeforeUnmount, nextTick, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useContactsStore } from '../../stores/contacts'
import { useChatStore } from '../../stores/chat'
import { useSettingsStore } from '../../stores/settings'
import { stegoApi } from '../../api/stego'
import MessageBubble from '../../components/MessageBubble.vue'
import MessageInput from '../../components/MessageInput.vue'

const route = useRoute()
const router = useRouter()
const contactsStore = useContactsStore()
const chatStore = useChatStore()
const settingsStore = useSettingsStore()

const contactId = computed(() => route.params.id as string)
const contact = computed(() => contactsStore.contacts.find((c) => c.user_id === contactId.value))
const messages = computed(() => chatStore.getMessages(contactId.value))
const messagesContainer = ref<HTMLElement>()
const stegoLoading = ref(false)
const stegoMaxCapacity = ref(0)
const sendError = ref('')

// E2EE gating: derive once settings + contact are both loaded.
const selfConfigured = computed(() => settingsStore.hasE2EE)
const peerConfigured = computed(() => !!contact.value?.peerUserKeyHex)
const e2eeReady = computed(() => selfConfigured.value && peerConfigured.value)

onMounted(async () => {
  await contactsStore.loadContacts()
  await chatStore.loadMessages(contactId.value)
  await chatStore.setActiveContact(contactId.value)
  scrollToBottom()

  // Mark unread messages as read
  const unread = messages.value.filter(
    (m) => m.direction === 'received' && m.status === 'delivered'
  )
  for (const msg of unread) {
    chatStore.sendReadReceipt(contactId.value, msg.id)
  }

  // Preload invite codes
  await chatStore.loadInviteCodes(contactId.value)
  // Fetch max capacity once codes are loaded
  if (chatStore.inviteCodesLoaded) {
    try {
      const key = chatStore.getStegoKey(true)
      const res = await stegoApi.getMaxCapacity(key)
      stegoMaxCapacity.value = res.max_capacity
    } catch (e) {
      console.error('Failed to fetch max capacity:', e)
    }
  }
})

onBeforeUnmount(() => {
  chatStore.setActiveContact(null)
})

// Recompute mkey when either key changes.
watch(
  () => [settingsStore.userKeyHex, contact.value?.peerUserKeyHex],
  async () => {
    await chatStore.refreshMkey()
  },
)

watch(messages, () => nextTick(scrollToBottom), { deep: true })

function scrollToBottom() {
  if (messagesContainer.value) {
    messagesContainer.value.scrollTop = messagesContainer.value.scrollHeight
  }
}

function getStegoKeyForMessage(msg: any): string {
  const isOutgoing = msg.direction === 'sent'
  return chatStore.getStegoKey(isOutgoing)
}

async function handleSend(content: string, isStegoMode: boolean) {
  sendError.value = ''
  if (isStegoMode) {
    stegoLoading.value = true
    try {
      const key = chatStore.getStegoKey(true)
      const res = await stegoApi.embed(content, key)
      if (!res.stego_image) {
        throw new Error(res.error || '隐写嵌入失败: 未返回载体图像')
      }
      // Strip data:image/png;base64, prefix if present
      let base64 = res.stego_image
      if (base64.startsWith('data:')) {
        base64 = base64.split(',')[1] ?? base64
      }
      await chatStore.sendStegoMessage(contactId.value, base64)
    } catch (e: any) {
      sendError.value = '隐写嵌入失败: ' + (e.response?.data?.detail || e.message)
    } finally {
      stegoLoading.value = false
    }
  } else {
    try {
      await chatStore.sendTextMessage(contactId.value, content)
    } catch (e: any) {
      if (e.message === 'E2EE_NOT_CONFIGURED') {
        sendError.value = '端到端加密未配置,无法发送文本消息。请先完成密钥设置。'
      } else if (e.message === 'E2EE_SEAL_FAILED') {
        sendError.value = '加密失败,消息未发送。'
      } else {
        sendError.value = e?.message || '发送失败'
      }
    }
  }
}

function goToProfile() {
  router.push('/profile')
}
function goToContactDetail() {
  router.push(`/contacts/${contactId.value}`)
}
</script>

<template>
  <div class="flex flex-col h-screen">
    <!-- Header -->
    <div class="flex items-center gap-3 px-6 py-4 bg-surface-container-low border-b border-outline-variant/10">
      <button @click="router.push('/chats')" class="text-on-surface-variant hover:text-on-surface transition-colors">
        <span class="material-symbols-outlined">arrow_back</span>
      </button>
      <div class="w-9 h-9 rounded-full bg-primary-container text-on-primary-container flex items-center justify-center font-bold text-sm">
        {{ (contact?.username || contactId)?.[0]?.toUpperCase() }}
      </div>
      <span class="font-semibold text-on-surface">{{ contact?.username || contactId }}</span>
    </div>

    <!-- E2EE banners / indicator -->
    <div v-if="!selfConfigured" class="px-4 py-3 bg-error-container/40 border-b border-outline-variant/10 flex items-center gap-3">
      <span class="material-symbols-outlined text-on-error-container">lock_open</span>
      <div class="flex-1 text-sm text-on-error-container">
        请先在「我的」页面配置你的助记词,否则无法发送加密消息。
      </div>
      <button class="btn-ghost text-xs" @click="goToProfile">去设置</button>
    </div>
    <div
      v-else-if="!peerConfigured"
      class="px-4 py-3 bg-tertiary-container/30 border-b border-outline-variant/10 flex items-center gap-3"
    >
      <span class="material-symbols-outlined text-on-tertiary-container">key</span>
      <div class="flex-1 text-sm text-on-tertiary-container">
        请为该联系人配置对方的助记词,才能与其进行端到端加密通信。
      </div>
      <button class="btn-ghost text-xs" @click="goToContactDetail">去配置</button>
    </div>
    <div
      v-else
      class="px-4 py-1.5 flex items-center gap-2 text-[11px] text-on-surface-variant/70 bg-surface-container-lowest border-b border-outline-variant/10"
    >
      <span class="material-symbols-outlined" style="font-size:14px">lock</span>
      <span>端到端加密已启用</span>
    </div>

    <!-- Messages -->
    <div ref="messagesContainer" class="flex-1 overflow-y-auto px-4 py-4 flex flex-col gap-1 bg-surface">
      <MessageBubble
        v-for="msg in messages"
        :key="msg.id"
        :message="msg"
        :stego-key="getStegoKeyForMessage(msg)"
      />
    </div>

    <!-- Loading indicator -->
    <div v-if="stegoLoading" class="text-center py-2 text-tertiary text-xs font-medium bg-surface-container-low border-t border-outline-variant/10">
      <span class="material-symbols-outlined text-sm animate-spin mr-1">progress_activity</span>
      正在生成隐写图像...
    </div>

    <!-- Inline error -->
    <div
      v-if="sendError"
      class="px-4 py-2 text-xs text-error bg-error-container/40 border-t border-outline-variant/10 flex items-center gap-2"
    >
      <span class="material-symbols-outlined text-base">error</span>
      <span class="flex-1">{{ sendError }}</span>
      <button class="text-on-error-container/80 hover:text-on-error-container" @click="sendError = ''">
        <span class="material-symbols-outlined text-base">close</span>
      </button>
    </div>

    <!-- Input -->
    <MessageInput
      @send="handleSend"
      :stego-max-capacity="stegoMaxCapacity"
      :stego-mode-disabled="!chatStore.inviteCodesLoaded"
      :disabled="!e2eeReady"
      :disabled-reason="!selfConfigured ? '请先配置你的助记词' : '请为对方配置助记词'"
    />
  </div>
</template>
