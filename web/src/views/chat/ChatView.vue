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
      // Strip data:image/png;base64, prefix if present
      let base64 = res.stego_image
      if (base64.startsWith('data:')) {
        base64 = base64.split(',')[1]
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
  <div class="chat-view">
    <div class="header">
      <button class="back-btn" @click="router.back()">←</button>
      <span class="name">{{ contact?.username || contactId }}</span>
    </div>

    <div class="messages" ref="messagesContainer">
      <MessageBubble
        v-for="msg in messages"
        :key="msg.id"
        :message="msg"
        :stego-key="getStegoKeyForMessage(msg)"
      />
    </div>

    <div v-if="stegoLoading" class="loading">正在生成隐写图像...</div>

    <MessageInput
      @send="handleSend"
      :stego-max-capacity="stegoMaxCapacity"
      :stego-mode-disabled="!chatStore.inviteCodesLoaded"
    />
  </div>
</template>

<style scoped>
.chat-view {
  display: flex;
  flex-direction: column;
  height: 100vh;
}
.header {
  display: flex;
  align-items: center;
  padding: 12px 16px;
  background: #075e54;
  color: #fff;
  gap: 12px;
}
.back-btn {
  background: none;
  border: none;
  color: #fff;
  font-size: 20px;
  cursor: pointer;
}
.name {
  font-size: 16px;
  font-weight: 500;
}
.messages {
  flex: 1;
  overflow-y: auto;
  padding: 12px 0;
  display: flex;
  flex-direction: column;
  background: #e5ddd5;
}
.loading {
  text-align: center;
  padding: 8px;
  color: #666;
  font-size: 13px;
  background: #fff3cd;
}
</style>
