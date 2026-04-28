<script setup lang="ts">
import { useRoute } from 'vue-router'

const route = useRoute()

const navItems = [
  { path: '/chats', icon: 'chat', label: '会话' },
  { path: '/contacts', icon: 'people', label: '联系人' },
  { path: '/embed', icon: 'construction', label: '工具' },
  { path: '/profile', icon: 'tune', label: '设置' },
]

function isActive(path: string): boolean {
  if (path === '/embed') {
    return route.path === '/embed' || route.path === '/extract'
  }
  if (path === '/contacts') {
    return route.path.startsWith('/contacts')
  }
  if (path === '/chats') {
    return route.path.startsWith('/chat')
  }
  return route.path.startsWith(path)
}
</script>

<template>
  <nav class="w-16 flex-shrink-0 bg-surface-container-lowest border-r border-outline-variant/10 flex flex-col items-center py-4 h-screen sticky top-0">
    <!-- Brand -->
    <div class="mb-6">
      <span class="material-symbols-outlined filled text-primary text-3xl">shield_lock</span>
    </div>

    <!-- Nav Items -->
    <div class="flex flex-col gap-2 flex-1">
      <router-link
        v-for="item in navItems"
        :key="item.path"
        :to="item.path"
        class="group flex flex-col items-center gap-0.5 px-2 py-2 rounded-xl transition-all"
        :class="isActive(item.path) ? 'text-primary bg-primary/10' : 'text-on-surface-variant hover:text-on-surface hover:bg-surface-container'"
      >
        <span class="material-symbols-outlined text-2xl" :class="{ filled: isActive(item.path) }">{{ item.icon }}</span>
        <span class="text-[10px] font-medium">{{ item.label }}</span>
      </router-link>
    </div>
  </nav>
</template>
