<script setup lang="ts">
import { ref, computed, onMounted, nextTick, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useContactsStore } from '../../stores/contacts'
import { useChatStore } from '../../stores/chat'
import { stegoApi } from '../../api/stego'
import MessageBubble from '../../components/MessageBubble.vue'
import MessageInput from '../../components/MessageInput.vue'

const route = useRoute()
const router = useRouter()
const contactsStore = useContactsStore()
const chatStore = useChatStore()

const contactId = computed(() => route.params.id as string)
const contact = computed(() => contactsStore.contacts.find((c) => c.user_id === contactId.value))
const messages = computed(() => chatStore.getMessages(contactId.value))
const messagesContainer = ref<HTMLElement>()
const stegoLoading = ref(false)
const stegoMaxCapacity = ref(0)

onMounted(async () => {
  await contactsStore.loadContacts()
  await chatStore.loadMessages(contactId.value)
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
  if (isStegoMode) {
    stegoLoading.value = true
    try {
      const key = chatStore.getStegoKey(true)
      const res = await stegoApi.embed(content, key, 'celebahq')
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
      alert('隐写嵌入失败: ' + (e.response?.data?.detail || e.message))
    } finally {
      stegoLoading.value = false
    }
  } else {
    await chatStore.sendTextMessage(contactId.value, content)
  }
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

    <!-- Input -->
    <MessageInput
      @send="handleSend"
      :stego-max-capacity="stegoMaxCapacity"
      :stego-mode-disabled="!chatStore.inviteCodesLoaded"
    />
  </div>
</template>
