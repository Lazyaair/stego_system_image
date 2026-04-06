<script setup lang="ts">
import { ref } from 'vue'
import type { Message } from '../db'
import { stegoApi } from '../api/stego'

const props = defineProps<{
  message: Message
  stegoKey: string
}>()

const showMenu = ref(false)
const menuX = ref(0)
const menuY = ref(0)
const extractedText = ref<string | null>(null)
const extracting = ref(false)

function formatTime(iso: string) {
  return new Date(iso).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })
}

const statusIcon: Record<string, string> = {
  sending: '⏳', sent: '✓', delivered: '✓✓', read: '👁', failed: '❌'
}

function getStegoImageSrc(base64: string): string {
  if (base64.startsWith('data:')) return base64
  return 'data:image/png;base64,' + base64
}

function onContextMenu(e: MouseEvent) {
  e.preventDefault()
  menuX.value = e.clientX
  menuY.value = e.clientY
  showMenu.value = true
}

function closeMenu() {
  showMenu.value = false
}

async function extractSecret() {
  closeMenu()
  if (!props.message.stego_image || extracting.value) return
  extracting.value = true
  try {
    const base64 = props.message.stego_image.startsWith('data:')
      ? props.message.stego_image.split(',')[1]
      : props.message.stego_image
    const byteString = atob(base64)
    const bytes = new Uint8Array(byteString.length)
    for (let i = 0; i < byteString.length; i++) bytes[i] = byteString.charCodeAt(i)
    const blob = new Blob([bytes], { type: 'image/png' })
    const file = new File([blob], 'stego.png', { type: 'image/png' })
    const res = await stegoApi.extract(file, props.stegoKey, 'celebahq')
    extractedText.value = res.secret_message || '(空)'
  } catch (e: any) {
    extractedText.value = '提取失败: ' + (e.response?.data?.detail || e.message)
  } finally {
    extracting.value = false
  }
}

function saveImage() {
  closeMenu()
  if (!props.message.stego_image) return
  const src = getStegoImageSrc(props.message.stego_image)
  const a = document.createElement('a')
  a.href = src
  a.download = `stego-${props.message.id}.png`
  a.click()
}
</script>

<template>
  <div class="bubble" :class="message.direction" @click="closeMenu">
    <template v-if="message.revoked">
      <div class="revoked">消息已撤回</div>
    </template>
    <template v-else>
      <div v-if="message.content_type === 'stego' && message.stego_image" class="stego-image">
        <img
          :src="getStegoImageSrc(message.stego_image)"
          alt="隐写图像"
          @contextmenu="onContextMenu"
        />
        <div v-if="extracting" class="extracted-text">提取中...</div>
        <div v-else-if="extractedText !== null" class="extracted-text">
          {{ extractedText }}
        </div>
      </div>
      <div v-if="message.content" class="content">{{ message.content }}</div>
      <div class="meta">
        <span class="time">{{ formatTime(message.created_at) }}</span>
        <span v-if="message.direction === 'sent'" class="status">
          {{ statusIcon[message.status] || '' }}
        </span>
      </div>
    </template>

    <!-- Context menu -->
    <Teleport to="body">
      <div v-if="showMenu" class="context-menu" :style="{ left: menuX + 'px', top: menuY + 'px' }" @click.stop>
        <div class="menu-item" @click="extractSecret">提取秘密消息</div>
        <div class="menu-item" @click="saveImage">保存图像</div>
      </div>
      <div v-if="showMenu" class="menu-overlay" @click="closeMenu"></div>
    </Teleport>
  </div>
</template>

<style scoped>
.bubble {
  max-width: 70%;
  padding: 8px 12px;
  margin: 4px 12px;
  border-radius: 12px;
  word-break: break-word;
}
.bubble.sent {
  align-self: flex-end;
  background: #dcf8c6;
  margin-left: auto;
}
.bubble.received {
  align-self: flex-start;
  background: #fff;
  border: 1px solid #eee;
}
.revoked {
  color: #999;
  font-style: italic;
  font-size: 13px;
}
.content {
  font-size: 14px;
  line-height: 1.4;
}
.meta {
  display: flex;
  justify-content: flex-end;
  gap: 4px;
  margin-top: 4px;
}
.time {
  font-size: 11px;
  color: #999;
}
.status {
  font-size: 11px;
}
.stego-image img {
  max-width: 240px;
  border-radius: 8px;
  cursor: context-menu;
}
.extracted-text {
  margin-top: 4px;
  padding: 6px 10px;
  background: rgba(0, 0, 0, 0.05);
  border-radius: 6px;
  font-size: 13px;
  color: #333;
}
.context-menu {
  position: fixed;
  background: #fff;
  border: 1px solid #ddd;
  border-radius: 8px;
  box-shadow: 0 2px 12px rgba(0,0,0,0.15);
  z-index: 1000;
  min-width: 140px;
  overflow: hidden;
}
.menu-item {
  padding: 10px 16px;
  cursor: pointer;
  font-size: 14px;
}
.menu-item:hover {
  background: #f5f5f5;
}
.menu-overlay {
  position: fixed;
  inset: 0;
  z-index: 999;
}
</style>
