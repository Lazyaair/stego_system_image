<script setup lang="ts">
import type { Message } from '../db'

const props = defineProps<{
  message: Message
}>()

function formatTime(dateStr: string): string {
  const d = new Date(dateStr)
  return d.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })
}

const statusIcon: Record<string, string> = {
  sending: '...',
  sent: '\u2713',
  delivered: '\u2713\u2713',
  read: '\u2713\u2713',
  failed: '\u2717',
}
</script>

<template>
  <div class="bubble" :class="[message.direction, { revoked: message.revoked }]">
    <div v-if="message.revoked" class="revoked-text">Message revoked</div>
    <template v-else>
      <div v-if="message.content_type === 'stego' && message.stego_image" class="stego-image">
        <img :src="'data:image/png;base64,' + message.stego_image" alt="stego" />
      </div>
      <div class="content">{{ message.content }}</div>
    </template>
    <div class="meta">
      <span class="time">{{ formatTime(message.created_at) }}</span>
      <span v-if="message.direction === 'sent'" class="status" :class="message.status">
        {{ statusIcon[message.status] || '' }}
      </span>
    </div>
  </div>
</template>

<style scoped>
.bubble {
  max-width: 70%;
  padding: 8px 12px;
  border-radius: 12px;
  margin-bottom: 8px;
  word-break: break-word;
}
.sent {
  background: #4a90d9;
  color: white;
  margin-left: auto;
  border-bottom-right-radius: 4px;
}
.received {
  background: #e8e8e8;
  color: #333;
  margin-right: auto;
  border-bottom-left-radius: 4px;
}
.revoked {
  opacity: 0.6;
  font-style: italic;
}
.stego-image img {
  max-width: 200px;
  border-radius: 8px;
  margin-bottom: 4px;
}
.meta {
  display: flex;
  justify-content: flex-end;
  gap: 4px;
  font-size: 11px;
  opacity: 0.7;
  margin-top: 4px;
}
.status.read {
  color: #4fc3f7;
}
</style>
