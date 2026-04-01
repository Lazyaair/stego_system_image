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
</script>

<template>
  <div class="contacts-view">
    <div class="header">
      <h2>Contacts</h2>
      <router-link to="/contacts/add" class="add-btn">+ Add</router-link>
    </div>
    <div v-if="contactsStore.contacts.length === 0" class="empty">
      <p>No contacts yet</p>
    </div>
    <div
      v-for="contact in contactsStore.contacts"
      :key="contact.user_id"
      class="contact-item"
      @click="openChat(contact.user_id)"
    >
      <div class="avatar">{{ (contact.nickname || contact.username)[0].toUpperCase() }}</div>
      <div class="info">
        <div class="name">{{ contact.nickname || contact.username }}</div>
        <div class="username">@{{ contact.username }}</div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 16px;
}
.add-btn {
  background: #4a90d9;
  color: white;
  padding: 6px 14px;
  border-radius: 6px;
  text-decoration: none;
  font-size: 14px;
}
.empty {
  text-align: center;
  padding: 60px 20px;
  color: #888;
}
.contact-item {
  display: flex;
  align-items: center;
  padding: 12px 16px;
  border-bottom: 1px solid #f0f0f0;
  cursor: pointer;
}
.contact-item:hover { background: #f8f8f8; }
.avatar {
  width: 40px;
  height: 40px;
  border-radius: 50%;
  background: #4a90d9;
  color: white;
  display: flex;
  align-items: center;
  justify-content: center;
  font-weight: bold;
}
.info { margin-left: 12px; }
.name { font-weight: 500; }
.username { font-size: 13px; color: #888; }
</style>
