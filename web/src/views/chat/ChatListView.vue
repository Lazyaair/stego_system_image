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
  <div class="h-full flex flex-col">
    <!-- Header -->
    <div class="flex items-center justify-between px-6 py-5 border-b border-outline-variant/10">
      <h2 class="text-xl font-bold text-on-surface">会话</h2>
      <router-link
        v-if="pendingCount > 0"
        to="/contacts/requests"
        class="flex items-center gap-1.5 px-3 py-1.5 rounded-full bg-error-container text-on-error-container text-xs font-bold"
      >
        <span class="material-symbols-outlined text-sm">notifications</span>
        {{ pendingCount }} 条新请求
      </router-link>
    </div>

    <!-- Empty State -->
    <div v-if="sortedContacts.length === 0" class="flex-1 flex flex-col items-center justify-center text-on-surface-variant gap-4 px-6">
      <span class="material-symbols-outlined text-6xl opacity-30">forum</span>
      <p class="text-sm">暂无会话</p>
      <router-link to="/contacts/add" class="btn-primary text-sm px-4 py-2">
        添加联系人
      </router-link>
    </div>

    <!-- Chat List -->
    <div v-else class="flex-1 overflow-y-auto">
      <div
        v-for="contact in sortedContacts"
        :key="contact.user_id"
        class="flex items-center gap-4 px-6 py-4 cursor-pointer hover:bg-surface-container-high transition-colors border-b border-outline-variant/5"
        @click="openChat(contact.user_id)"
      >
        <div class="w-11 h-11 rounded-full bg-primary-container text-on-primary-container flex items-center justify-center font-bold text-lg flex-shrink-0">
          {{ (contact.nickname || contact.username)[0].toUpperCase() }}
        </div>
        <div class="flex-1 min-w-0">
          <div class="font-semibold text-on-surface text-sm">{{ contact.nickname || contact.username }}</div>
          <div class="text-xs text-on-surface-variant truncate mt-0.5">
            {{ chatStore.getLastMessage(contact.user_id)?.content || '暂无消息' }}
          </div>
        </div>
        <div v-if="chatStore.getLastMessage(contact.user_id)" class="text-[11px] text-on-surface-variant/60 flex-shrink-0">
          {{ formatTime(chatStore.getLastMessage(contact.user_id)!.created_at) }}
        </div>
      </div>
    </div>
  </div>
</template>
