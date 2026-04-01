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
  <div class="requests-view">
    <div class="header">
      <button class="back-btn" @click="router.back()">&#8592;</button>
      <h2>Friend Requests</h2>
    </div>
    <div v-if="chatStore.pendingRequests.length === 0" class="empty">
      <p>No pending requests</p>
    </div>
    <div
      v-for="(req, index) in chatStore.pendingRequests"
      :key="req.userId"
      class="request-item"
    >
      <div class="avatar">{{ req.username[0].toUpperCase() }}</div>
      <div class="info">
        <div class="name">{{ req.username }}</div>
        <div class="preview">wants to be your friend</div>
      </div>
      <div class="actions">
        <button class="accept-btn" @click="accept(index)">Accept</button>
        <button class="reject-btn" @click="reject(index)">Ignore</button>
        <button class="block-btn" @click="block(index)">Block</button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.header {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 16px;
}
.back-btn {
  background: none;
  border: none;
  font-size: 20px;
  cursor: pointer;
}
.empty {
  text-align: center;
  padding: 60px 20px;
  color: #888;
}
.request-item {
  display: flex;
  align-items: center;
  padding: 12px 16px;
  border-bottom: 1px solid #f0f0f0;
}
.avatar {
  width: 44px;
  height: 44px;
  border-radius: 50%;
  background: #f0a030;
  color: white;
  display: flex;
  align-items: center;
  justify-content: center;
  font-weight: bold;
  font-size: 18px;
  flex-shrink: 0;
}
.info {
  flex: 1;
  margin-left: 12px;
  overflow: hidden;
}
.name { font-weight: 500; }
.preview {
  font-size: 13px;
  color: #888;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.actions {
  display: flex;
  gap: 6px;
  flex-shrink: 0;
}
.accept-btn {
  padding: 6px 12px;
  background: #4a90d9;
  color: white;
  border: none;
  border-radius: 6px;
  cursor: pointer;
  font-size: 13px;
}
.reject-btn {
  padding: 6px 12px;
  background: #e0e0e0;
  color: #333;
  border: none;
  border-radius: 6px;
  cursor: pointer;
  font-size: 13px;
}
.block-btn {
  padding: 6px 12px;
  background: white;
  color: #e53e3e;
  border: 1px solid #e53e3e;
  border-radius: 6px;
  cursor: pointer;
  font-size: 13px;
}
</style>
