<script setup lang="ts">
import { useRoute } from 'vue-router'

const route = useRoute()

const tabs = [
  { path: '/chats', label: 'Messages', icon: '\uD83D\uDCAC' },
  { path: '/contacts', label: 'Contacts', icon: '\uD83D\uDC65' },
  { path: '/embed', label: 'Stego', icon: '\uD83D\uDD10' },
  { path: '/profile', label: 'Profile', icon: '\uD83D\uDC64' },
]

function isActive(path: string): boolean {
  if (path === '/embed') {
    return route.path === '/embed' || route.path === '/extract'
  }
  return route.path.startsWith(path)
}
</script>

<template>
  <nav class="bottom-nav">
    <router-link
      v-for="tab in tabs"
      :key="tab.path"
      :to="tab.path"
      class="nav-item"
      :class="{ active: isActive(tab.path) }"
    >
      <span class="icon">{{ tab.icon }}</span>
      <span class="label">{{ tab.label }}</span>
    </router-link>
  </nav>
</template>

<style scoped>
.bottom-nav {
  display: flex;
  border-top: 1px solid #e0e0e0;
  background: white;
  position: fixed;
  bottom: 0;
  left: 0;
  right: 0;
  z-index: 100;
}
.nav-item {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  padding: 8px 0;
  text-decoration: none;
  color: #888;
  font-size: 11px;
}
.nav-item.active {
  color: #4a90d9;
}
.icon { font-size: 20px; }
.label { margin-top: 2px; }
</style>
