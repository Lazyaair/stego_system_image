<script setup lang="ts">
import { ref, computed } from 'vue'

const props = defineProps<{
  stegoMaxCapacity: number
  stegoModeDisabled: boolean
}>()

const emit = defineEmits<{
  send: [content: string, isStegoMode: boolean]
}>()

const content = ref('')
const stegoMode = ref(false)

const byteLength = computed(() => new TextEncoder().encode(content.value).length)
const overCapacity = computed(() => stegoMode.value && byteLength.value > props.stegoMaxCapacity)
const canSend = computed(() => {
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
  if (props.stegoModeDisabled) return
  stegoMode.value = !stegoMode.value
}
</script>

<template>
  <div class="message-input">
    <div v-if="stegoMode" class="capacity-indicator" :class="{ over: overCapacity }">
      秘密消息: {{ byteLength }}/{{ stegoMaxCapacity }} 字节
    </div>
    <div class="input-row">
      <button
        class="mode-toggle"
        :class="{ active: stegoMode, disabled: stegoModeDisabled }"
        @click="toggleStegoMode"
        :title="stegoModeDisabled ? '加载中...' : (stegoMode ? '切换到普通模式' : '切换到隐写模式')"
      >
        {{ stegoMode ? '🔒' : '💬' }}
      </button>
      <input
        v-model="content"
        :placeholder="stegoMode ? '输入秘密消息...' : '输入消息...'"
        @keyup.enter="handleSend"
      />
      <button class="send-btn" :disabled="!canSend" @click="handleSend">发送</button>
    </div>
  </div>
</template>

<style scoped>
.message-input {
  padding: 8px 12px;
  border-top: 1px solid #eee;
  background: #fff;
}
.input-row {
  display: flex;
  gap: 8px;
  align-items: center;
}
.mode-toggle {
  width: 36px;
  height: 36px;
  border-radius: 50%;
  border: 1px solid #ddd;
  background: #f5f5f5;
  cursor: pointer;
  font-size: 18px;
  display: flex;
  align-items: center;
  justify-content: center;
}
.mode-toggle.active {
  background: #e3f2fd;
  border-color: #2196f3;
}
.mode-toggle.disabled {
  opacity: 0.4;
  cursor: not-allowed;
}
input {
  flex: 1;
  padding: 8px 12px;
  border: 1px solid #ddd;
  border-radius: 20px;
  outline: none;
}
input:focus {
  border-color: #2196f3;
}
.send-btn {
  padding: 8px 16px;
  background: #2196f3;
  color: #fff;
  border: none;
  border-radius: 20px;
  cursor: pointer;
}
.send-btn:disabled {
  background: #ccc;
  cursor: not-allowed;
}
.capacity-indicator {
  font-size: 12px;
  color: #666;
  padding: 2px 8px 2px 48px;
}
.capacity-indicator.over {
  color: #e53935;
  font-weight: bold;
}
</style>
