<script setup lang="ts">
import { computed, watch } from 'vue'
import { useRoute } from 'vue-router'
import { useAuthStore } from './stores/auth'
import { useChatStore } from './stores/chat'
import { useContactsStore } from './stores/contacts'
import { wsClient } from './api/websocket'
import BottomNav from './components/BottomNav.vue'

const route = useRoute()
const auth = useAuthStore()
const chatStore = useChatStore()
const contactsStore = useContactsStore()

const showNav = computed(() => {
  const guestRoutes = ['/login', '/register']
  const fullScreenRoutes = ['/chat/']
  if (guestRoutes.includes(route.path)) return false
  if (fullScreenRoutes.some((r) => route.path.startsWith(r))) return false
  return auth.isAuthenticated
})

// Connect WebSocket when authenticated
watch(
  () => auth.token,
  (token) => {
    if (token) {
      chatStore.setupWsHandlers()
      wsClient.connect(token)
      contactsStore.loadContacts()
    } else {
      wsClient.disconnect()
    }
  },
  { immediate: true }
)
</script>

<template>
  <div class="app" :class="{ 'has-nav': showNav }">
    <router-view />
    <BottomNav v-if="showNav" />
  </div>
</template>

<style>
* {
  margin: 0;
  padding: 0;
  box-sizing: border-box;
}
body {
  font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
  background: #fff;
}
.app.has-nav {
  padding-bottom: 60px;
}
</style>
