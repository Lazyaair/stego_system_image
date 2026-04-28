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
  <div
    class="max-w-[70%] px-4 py-2.5 rounded-2xl break-words"
    :class="message.direction === 'sent'
      ? 'self-end bg-primary-container text-on-primary-container ml-auto'
      : 'self-start bg-surface-container-high text-on-surface'"
    @click="closeMenu"
  >
    <template v-if="message.revoked">
      <div class="text-on-surface-variant/60 italic text-sm">消息已撤回</div>
    </template>
    <template v-else>
      <div v-if="message.content_type === 'stego' && message.stego_image" class="mb-1">
        <img
          :src="getStegoImageSrc(message.stego_image)"
          alt="隐写图像"
          class="max-w-[240px] rounded-lg cursor-context-menu"
          @contextmenu="onContextMenu"
        />
        <div v-if="extracting" class="mt-2 px-3 py-2 rounded-lg bg-surface-container text-on-surface-variant text-xs">提取中...</div>
        <div v-else-if="extractedText !== null" class="mt-2 px-3 py-2 rounded-lg bg-tertiary-container/30 text-tertiary text-xs font-medium">
          {{ extractedText }}
        </div>
      </div>
      <div v-if="message.content" class="text-sm leading-relaxed">{{ message.content }}</div>
      <div class="flex justify-end gap-1 mt-1">
        <span class="text-[11px] opacity-50">{{ formatTime(message.created_at) }}</span>
        <span v-if="message.direction === 'sent'" class="text-[11px] opacity-50">
          {{ statusIcon[message.status] || '' }}
        </span>
      </div>
    </template>

    <!-- Context menu -->
    <Teleport to="body">
      <div
        v-if="showMenu"
        class="fixed bg-surface-container-highest border border-outline-variant/20 rounded-xl shadow-2xl z-[1000] min-w-[160px] overflow-hidden"
        :style="{ left: menuX + 'px', top: menuY + 'px' }"
        @click.stop
      >
        <div class="px-4 py-3 cursor-pointer text-sm text-on-surface hover:bg-surface-container-high transition-colors flex items-center gap-2" @click="extractSecret">
          <span class="material-symbols-outlined text-lg text-tertiary">key</span>
          提取秘密消息
        </div>
        <div class="px-4 py-3 cursor-pointer text-sm text-on-surface hover:bg-surface-container-high transition-colors flex items-center gap-2" @click="saveImage">
          <span class="material-symbols-outlined text-lg text-primary">download</span>
          保存图像
        </div>
      </div>
      <div v-if="showMenu" class="fixed inset-0 z-[999]" @click="closeMenu"></div>
    </Teleport>
  </div>
</template>
