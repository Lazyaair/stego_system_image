<script setup lang="ts">
import { onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { useContactsStore } from '../../stores/contacts'

const router = useRouter()
const contactsStore = useContactsStore()

onMounted(() => contactsStore.loadContacts())

function openChat(userId: string) {
  router.push(`/chat/${userId}`)
}

function openDetail(userId: string, event: Event) {
  event.stopPropagation()
  router.push(`/contacts/${userId}`)
}
</script>

<template>
  <div class="h-full flex flex-col">
    <!-- Header -->
    <div class="flex items-center justify-between px-6 py-5 border-b border-outline-variant/10">
      <h2 class="text-xl font-bold text-on-surface">联系人</h2>
      <router-link
        to="/contacts/add"
        class="flex items-center gap-1.5 px-4 py-2 rounded-xl bg-primary text-on-primary text-sm font-semibold hover:bg-primary/80 transition-colors"
      >
        <span class="material-symbols-outlined text-lg">person_add</span>
        添加
      </router-link>
    </div>

    <!-- Empty State -->
    <div v-if="contactsStore.contacts.length === 0" class="flex-1 flex flex-col items-center justify-center text-on-surface-variant gap-4">
      <span class="material-symbols-outlined text-6xl opacity-30">people</span>
      <p class="text-sm">暂无联系人</p>
    </div>

    <!-- Contact List -->
    <div v-else class="flex-1 overflow-y-auto">
      <div
        v-for="contact in contactsStore.contacts"
        :key="contact.user_id"
        class="flex items-center gap-4 px-6 py-4 cursor-pointer hover:bg-surface-container-high transition-colors border-b border-outline-variant/5"
        @click="openChat(contact.user_id)"
      >
        <div class="w-10 h-10 rounded-full bg-secondary-container text-on-secondary-container flex items-center justify-center font-bold flex-shrink-0">
          {{ (contact.nickname || contact.username)[0].toUpperCase() }}
        </div>
        <div class="min-w-0 flex-1">
          <div class="flex items-center gap-2">
            <span class="font-semibold text-on-surface text-sm">{{ contact.nickname || contact.username }}</span>
            <span
              v-if="!contact.peerUserKeyHex"
              class="inline-flex items-center gap-0.5 text-[10px] font-bold text-on-error-container bg-error-container/40 px-1.5 py-0.5 rounded"
              title="尚未配置对方加密助记词"
            >
              <span class="material-symbols-outlined text-[12px]">lock_open</span>
              未加密
            </span>
            <span
              v-else
              class="inline-flex items-center gap-0.5 text-[10px] font-bold text-on-tertiary-container bg-tertiary-container/40 px-1.5 py-0.5 rounded"
              title="已配置对方加密助记词"
            >
              <span class="material-symbols-outlined text-[12px]">lock</span>
              已加密
            </span>
          </div>
          <div class="text-xs text-on-surface-variant">@{{ contact.username }}</div>
        </div>
        <button
          class="w-8 h-8 rounded-lg text-on-surface-variant hover:text-primary hover:bg-surface-container-highest transition-colors flex items-center justify-center"
          aria-label="详情"
          title="详情 / 加密设置"
          @click="openDetail(contact.user_id, $event)"
        >
          <span class="material-symbols-outlined text-lg">tune</span>
        </button>
      </div>
    </div>
  </div>
</template>
