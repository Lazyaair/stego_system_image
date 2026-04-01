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
const contact = computed(() =>
  contactsStore.contacts.find((c) => c.user_id === contactId.value)
)
const messages = computed(() => chatStore.getMessages(contactId.value))
const messagesContainer = ref<HTMLElement | null>(null)
const stegoLoading = ref(false)

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
})

watch(messages, () => nextTick(scrollToBottom), { deep: true })

function scrollToBottom() {
  if (messagesContainer.value) {
    messagesContainer.value.scrollTop = messagesContainer.value.scrollHeight
  }
}

async function handleSend(content: string, isStegoMode: boolean) {
  if (isStegoMode) {
    stegoLoading.value = true
    try {
      const result = await stegoApi.embed(content, 'default-key', 'celebahq')
      if (result.stego_image) {
        await chatStore.sendStegoMessage(contactId.value, content, result.stego_image)
      }
    } catch (e: any) {
      alert('Stego embed failed: ' + (e.response?.data?.detail || e.message))
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
    <div class="chat-header">
      <button class="back-btn" @click="router.push('/chats')">&#8592;</button>
      <span class="chat-name">{{ contact?.nickname || contact?.username || 'Chat' }}</span>
    </div>
    <div class="messages" ref="messagesContainer">
      <MessageBubble v-for="msg in messages" :key="msg.id" :message="msg" />
      <div v-if="stegoLoading" class="stego-loading">Generating stego image...</div>
    </div>
    <MessageInput @send="handleSend" />
  </div>
</template>

<style scoped>
.chat-view {
  display: flex;
  flex-direction: column;
  height: 100vh;
}
.chat-header {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 12px 16px;
  border-bottom: 1px solid #e0e0e0;
  background: white;
}
.back-btn {
  background: none;
  border: none;
  font-size: 20px;
  cursor: pointer;
  padding: 4px 8px;
}
.chat-name {
  font-weight: 600;
  font-size: 16px;
}
.messages {
  flex: 1;
  overflow-y: auto;
  padding: 16px;
  display: flex;
  flex-direction: column;
  background: #f5f5f5;
}
.stego-loading {
  text-align: center;
  color: #888;
  padding: 8px;
  font-style: italic;
}
</style>
