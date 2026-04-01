<script setup lang="ts">
import { onMounted, computed } from 'vue'
import { useRouter } from 'vue-router'
import { useContactsStore } from '../../stores/contacts'
import { useChatStore } from '../../stores/chat'

const router = useRouter()
const contactsStore = useContactsStore()
const chatStore = useChatStore()

onMounted(async () => {
  await contactsStore.loadContacts()
  for (const contact of contactsStore.contacts) {
    await chatStore.loadMessages(contact.user_id)
  }
})

const sortedContacts = computed(() => {
  return [...contactsStore.contacts]
    .filter((c) => c.status === 'accepted')
    .sort((a, b) => {
      const lastA = chatStore.getLastMessage(a.user_id)
      const lastB = chatStore.getLastMessage(b.user_id)
      if (!lastA && !lastB) return 0
      if (!lastA) return 1
      if (!lastB) return -1
      return lastB.created_at.localeCompare(lastA.created_at)
    })
})

function formatTime(dateStr: string): string {
  const d = new Date(dateStr)
  const now = new Date()
  if (d.toDateString() === now.toDateString()) {
    return d.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })
  }
  return d.toLocaleDateString()
}

function openChat(userId: string) {
  router.push(`/chat/${userId}`)
}

const pendingCount = computed(() => chatStore.pendingRequests.length)
</script>

<template>
  <div class="chat-list">
    <div class="header">
      <h2>Messages</h2>
      <router-link v-if="pendingCount > 0" to="/contacts/requests" class="badge">
        {{ pendingCount }} new
      </router-link>
    </div>
    <div v-if="sortedContacts.length === 0" class="empty">
      <p>No conversations yet</p>
      <router-link to="/contacts/add">Add a contact</router-link>
    </div>
    <div
      v-for="contact in sortedContacts"
      :key="contact.user_id"
      class="chat-item"
      @click="openChat(contact.user_id)"
    >
      <div class="avatar">{{ (contact.nickname || contact.username)[0].toUpperCase() }}</div>
      <div class="info">
        <div class="name">{{ contact.nickname || contact.username }}</div>
        <div class="last-msg">
          {{ chatStore.getLastMessage(contact.user_id)?.content || 'No messages' }}
        </div>
      </div>
      <div v-if="chatStore.getLastMessage(contact.user_id)" class="time">
        {{ formatTime(chatStore.getLastMessage(contact.user_id)!.created_at) }}
      </div>
    </div>
  </div>
</template>

<style scoped>
.chat-list { padding: 0; }
.header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 16px;
}
.badge {
  background: #e53e3e;
  color: white;
  padding: 4px 10px;
  border-radius: 12px;
  font-size: 12px;
  text-decoration: none;
}
.empty {
  text-align: center;
  padding: 60px 20px;
  color: #888;
}
.chat-item {
  display: flex;
  align-items: center;
  padding: 12px 16px;
  border-bottom: 1px solid #f0f0f0;
  cursor: pointer;
}
.chat-item:hover { background: #f8f8f8; }
.avatar {
  width: 44px;
  height: 44px;
  border-radius: 50%;
  background: #4a90d9;
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
.last-msg {
  font-size: 13px;
  color: #888;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.time {
  font-size: 12px;
  color: #888;
  flex-shrink: 0;
}
</style>
