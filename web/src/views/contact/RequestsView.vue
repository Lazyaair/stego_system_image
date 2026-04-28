<script setup lang="ts">
import { useRouter } from 'vue-router'
import { useChatStore } from '../../stores/chat'
import { useContactsStore } from '../../stores/contacts'
import { saveMessage } from '../../db'
import type { Message } from '../../db'

const router = useRouter()
const chatStore = useChatStore()
const contactsStore = useContactsStore()

async function accept(index: number) {
  const req = chatStore.pendingRequests[index]

  // Save contact
  await contactsStore.acceptContact(req.userId, req.username)

  // Save all pending messages into DB
  for (const msg of req.messages) {
    const payload = msg.payload
    const message: Message = {
      id: msg.id,
      contact_id: payload.from_user_id,
      direction: 'received',
      content: payload.content,
      content_type: payload.content_type || 'text',
      stego_image: payload.stego_image,
      status: 'delivered',
      burn_after: payload.burn_after || 0,
      burned: false,
      revoked: false,
      created_at: new Date(msg.timestamp * 1000).toISOString(),
    }
    await saveMessage(message)
  }
  await chatStore.loadMessages(req.userId)

  // Remove from pending
  chatStore.pendingRequests = chatStore.pendingRequests.filter(
    (_: any, i: number) => i !== index
  )

  // Navigate to chat
  router.push(`/chat/${req.userId}`)
}

async function reject(index: number) {
  chatStore.pendingRequests = chatStore.pendingRequests.filter(
    (_: any, i: number) => i !== index
  )
}

async function block(index: number) {
  const req = chatStore.pendingRequests[index]
  await contactsStore.blockUser(req.userId, req.username)
  chatStore.pendingRequests = chatStore.pendingRequests.filter(
    (_: any, i: number) => i !== index
  )
}
</script>

<template>
  <div class="h-full flex flex-col">
    <!-- Header -->
    <div class="flex items-center gap-3 px-6 py-5 border-b border-outline-variant/10">
      <button @click="router.back()" class="text-on-surface-variant hover:text-on-surface transition-colors">
        <span class="material-symbols-outlined">arrow_back</span>
      </button>
      <h2 class="text-xl font-bold text-on-surface">好友请求</h2>
    </div>

    <!-- Empty State -->
    <div v-if="chatStore.pendingRequests.length === 0" class="flex-1 flex flex-col items-center justify-center text-on-surface-variant gap-4">
      <span class="material-symbols-outlined text-6xl opacity-30">mail</span>
      <p class="text-sm">暂无待处理的请求</p>
    </div>

    <!-- Request List -->
    <div v-else class="flex-1 overflow-y-auto">
      <div
        v-for="(req, index) in chatStore.pendingRequests"
        :key="req.userId"
        class="flex items-center gap-4 px-6 py-4 border-b border-outline-variant/5"
      >
        <div class="w-11 h-11 rounded-full bg-tertiary-container text-on-tertiary-container flex items-center justify-center font-bold text-lg flex-shrink-0">
          {{ req.username[0].toUpperCase() }}
        </div>
        <div class="flex-1 min-w-0">
          <div class="font-semibold text-on-surface text-sm">{{ req.username }}</div>
          <div class="text-xs text-on-surface-variant mt-0.5">请求添加为好友</div>
        </div>
        <div class="flex gap-2 flex-shrink-0">
          <button
            @click="accept(index)"
            class="px-3 py-1.5 rounded-lg bg-primary text-on-primary text-xs font-bold hover:bg-primary/80 transition-colors"
          >接受</button>
          <button
            @click="reject(index)"
            class="px-3 py-1.5 rounded-lg bg-surface-container-high text-on-surface text-xs font-medium hover:bg-surface-variant transition-colors"
          >忽略</button>
          <button
            @click="block(index)"
            class="px-3 py-1.5 rounded-lg border border-error/30 text-error text-xs font-medium hover:bg-error/5 transition-colors"
          >拉黑</button>
        </div>
      </div>
    </div>
  </div>
</template>
