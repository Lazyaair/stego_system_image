<script setup lang="ts">
import { computed, watch, onMounted, onUnmounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useAuthStore } from './stores/auth'
import { useChatStore } from './stores/chat'
import { useContactsStore } from './stores/contacts'
import { wsClient } from './api/websocket'
import SideNav from './components/SideNav.vue'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const chatStore = useChatStore()
const contactsStore = useContactsStore()

const showNav = computed(() => {
  const guestRoutes = ['/login', '/register']
  if (guestRoutes.includes(route.path)) return false
  return auth.isAuthenticated
})

// Handle being kicked by another device
function onKicked() {
  auth.onKicked()
  router.push('/login')
  alert('您的账号已在其他设备登录。')
}
onMounted(() => window.addEventListener('ws-kicked', onKicked))
onUnmounted(() => window.removeEventListener('ws-kicked', onKicked))

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
  <div class="flex h-screen overflow-hidden bg-surface">
    <SideNav v-if="showNav" />
    <main class="flex-1 overflow-y-auto">
      <router-view />
    </main>
  </div>
</template>
