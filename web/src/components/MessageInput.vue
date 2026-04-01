<script setup lang="ts">
import { ref } from 'vue'

const emit = defineEmits<{
  send: [content: string, isStegoMode: boolean]
}>()

const content = ref('')
const stegoMode = ref(false)

function handleSend() {
  const text = content.value.trim()
  if (!text) return
  emit('send', text, stegoMode.value)
  content.value = ''
}
</script>

<template>
  <div class="input-bar">
    <button class="mode-toggle" :class="{ active: stegoMode }" @click="stegoMode = !stegoMode"
      :title="stegoMode ? 'Stego mode ON' : 'Normal mode'">
      {{ stegoMode ? '\uD83D\uDD12' : '\uD83D\uDCAC' }}
    </button>
    <input
      v-model="content"
      type="text"
      placeholder="Type a message..."
      @keyup.enter="handleSend"
    />
    <button class="send-btn" @click="handleSend" :disabled="!content.trim()">Send</button>
  </div>
</template>

<style scoped>
.input-bar {
  display: flex;
  gap: 8px;
  padding: 8px 12px;
  border-top: 1px solid #e0e0e0;
  background: white;
}
.input-bar input {
  flex: 1;
  padding: 8px 12px;
  border: 1px solid #ccc;
  border-radius: 20px;
  font-size: 14px;
}
.mode-toggle {
  background: none;
  border: 1px solid #ccc;
  border-radius: 50%;
  width: 36px;
  height: 36px;
  font-size: 16px;
  cursor: pointer;
}
.mode-toggle.active {
  border-color: #4a90d9;
  background: #e8f0fe;
}
.send-btn {
  padding: 8px 16px;
  background: #4a90d9;
  color: white;
  border: none;
  border-radius: 20px;
  cursor: pointer;
}
.send-btn:disabled {
  opacity: 0.5;
}
</style>
