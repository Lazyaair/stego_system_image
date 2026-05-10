<script setup lang="ts">
import { ref, computed } from 'vue'

const props = withDefaults(
  defineProps<{
    stegoMaxCapacity: number
    stegoModeDisabled: boolean
    disabled?: boolean
    disabledReason?: string
  }>(),
  { disabled: false, disabledReason: '' },
)

const emit = defineEmits<{
  send: [content: string, isStegoMode: boolean]
}>()

const content = ref('')
const stegoMode = ref(false)

const byteLength = computed(() => new TextEncoder().encode(content.value).length)
const overCapacity = computed(() => stegoMode.value && byteLength.value > props.stegoMaxCapacity)
const canSend = computed(() => {
  if (props.disabled) return false
  const trimmed = content.value.trim()
  if (!trimmed) return false
  if (stegoMode.value && overCapacity.value) return false
  return true
})

function handleSend() {
  if (!canSend.value) return
  emit('send', content.value.trim(), stegoMode.value)
  content.value = ''
}

function toggleStegoMode() {
  if (props.stegoModeDisabled || props.disabled) return
  stegoMode.value = !stegoMode.value
}
</script>

<template>
  <div class="border-t border-outline-variant/10 bg-surface-container-low">
    <!-- Capacity indicator -->
    <div v-if="stegoMode" class="px-6 pt-2 text-xs" :class="overCapacity ? 'text-error font-bold' : 'text-on-surface-variant/60'">
      秘密消息: {{ byteLength }}/{{ stegoMaxCapacity }} 字节
    </div>
    <!-- Input row -->
    <div class="flex items-center gap-3 px-4 py-3">
      <button
        class="w-9 h-9 rounded-full flex items-center justify-center transition-all flex-shrink-0"
        :class="stegoMode
          ? 'bg-tertiary-container text-on-tertiary-container'
          : 'bg-surface-container text-on-surface-variant hover:bg-surface-container-high'"
        :disabled="stegoModeDisabled || disabled"
        :title="disabled ? (disabledReason || '未配置加密') : (stegoModeDisabled ? '加载中...' : (stegoMode ? '切换到普通模式' : '切换到隐写模式'))"
        @click="toggleStegoMode"
      >
        <span class="material-symbols-outlined text-xl">{{ stegoMode ? 'lock' : 'chat_bubble' }}</span>
      </button>
      <input
        v-model="content"
        :placeholder="disabled ? (disabledReason || '未配置加密,无法发送') : (stegoMode ? '输入秘密消息...' : '输入消息...')"
        :disabled="disabled"
        class="flex-1 bg-surface-container border border-outline-variant/20 rounded-full py-2.5 px-4 text-sm text-on-surface focus:ring-2 focus:ring-primary/30 focus:border-primary outline-none transition-all disabled:opacity-50 disabled:cursor-not-allowed"
        @keyup.enter="handleSend"
      />
      <button
        :disabled="!canSend"
        class="w-9 h-9 rounded-full bg-primary text-on-primary flex items-center justify-center transition-all hover:bg-primary/80 active:scale-95 disabled:opacity-30 disabled:cursor-not-allowed flex-shrink-0"
        @click="handleSend"
      >
        <span class="material-symbols-outlined text-xl">send</span>
      </button>
    </div>
  </div>
</template>
