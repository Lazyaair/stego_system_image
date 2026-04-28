<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { stegoApi, type Model } from '../api/stego'

const models = ref<Model[]>([])
const selectedModel = ref('')
const message = ref('')
const key = ref('')
const stegoImage = ref<string>('')
const isLoading = ref(false)
const isCheckingCapacity = ref(false)
const capacityInfo = ref<{ valid: boolean; max_capacity: number; error?: string } | null>(null)
const error = ref('')

onMounted(async () => {
  try {
    const res = await stegoApi.getModels()
    models.value = res.models
    const defaultModel = res.models.find(m => m.default)
    if (defaultModel) {
      selectedModel.value = defaultModel.id
    }
  } catch (e: any) {
    error.value = '获取模型列表失败'
  }
})

async function checkCapacity() {
  const keyCheck = stegoApi.validateKey(key.value)
  if (!keyCheck.valid) {
    error.value = keyCheck.error || ''
    return
  }

  if (!message.value) {
    error.value = '请输入秘密消息'
    return
  }

  isCheckingCapacity.value = true
  error.value = ''

  try {
    const result = await stegoApi.checkCapacity(message.value, key.value, selectedModel.value)
    capacityInfo.value = result
    if (!result.valid) {
      error.value = result.error || '消息超出容量'
    }
  } catch (e: any) {
    error.value = e.response?.data?.detail || '容量检查失败'
  } finally {
    isCheckingCapacity.value = false
  }
}

async function handleEmbed() {
  const keyCheck = stegoApi.validateKey(key.value)
  if (!keyCheck.valid) {
    error.value = keyCheck.error || ''
    return
  }

  if (!message.value) {
    error.value = '请输入秘密消息'
    return
  }

  isLoading.value = true
  error.value = ''
  stegoImage.value = ''

  try {
    const result = await stegoApi.embed(message.value, key.value, selectedModel.value)
    if (result.status === 'success' && result.stego_image) {
      stegoImage.value = result.stego_image
    } else {
      error.value = result.error || '嵌入失败'
      if (result.max_capacity) {
        error.value += ` (最大容量: ${result.max_capacity} bytes)`
      }
    }
  } catch (e: any) {
    error.value = e.response?.data?.detail || '请求失败'
  } finally {
    isLoading.value = false
  }
}

function downloadImage() {
  if (!stegoImage.value) return

  const link = document.createElement('a')
  link.href = stegoImage.value
  link.download = 'stego_image.png'
  link.click()
}
</script>

<template>
  <div class="max-w-2xl mx-auto py-8 px-6">
    <!-- Page Header -->
    <div class="mb-6">
      <h1 class="text-3xl font-extrabold tracking-tight text-on-surface mb-1">隐写工具箱</h1>
      <p class="text-on-surface-variant text-sm font-medium opacity-70">基于 Pulsar 算法的可证安全图像隐写</p>
    </div>

    <!-- Tab Bar -->
    <div class="flex border-b border-outline-variant/20 mb-8">
      <router-link
        to="/embed"
        class="flex-1 py-3 text-center text-sm font-bold uppercase tracking-wider transition-all text-primary border-b-2 border-primary"
      >消息嵌入</router-link>
      <router-link
        to="/extract"
        class="flex-1 py-3 text-center text-sm font-bold uppercase tracking-wider transition-all text-on-surface-variant hover:text-on-surface border-b-2 border-transparent"
      >消息提取</router-link>
    </div>

    <!-- Form -->
    <div class="space-y-6">
      <!-- Model select -->
      <div class="space-y-2">
        <label class="text-xs font-bold text-on-surface-variant uppercase ml-1">选择模型</label>
        <select v-model="selectedModel" class="input-field">
          <option v-for="m in models" :key="m.id" :value="m.id">{{ m.name }}</option>
        </select>
      </div>

      <!-- Secret message -->
      <div class="space-y-2">
        <label class="text-xs font-bold text-on-surface-variant uppercase ml-1">秘密消息</label>
        <textarea
          v-model="message"
          rows="4"
          placeholder="输入要隐藏的秘密消息..."
          class="input-field resize-none"
          @blur="checkCapacity"
        ></textarea>
        <div v-if="capacityInfo" class="text-xs ml-1" :class="capacityInfo.valid ? 'text-on-surface-variant/60' : 'text-error font-bold'">
          消息长度: {{ message.length }} bytes / 最大容量: {{ capacityInfo.max_capacity }} bytes
        </div>
      </div>

      <!-- Key -->
      <div class="space-y-2">
        <label class="text-xs font-bold text-on-surface-variant uppercase ml-1">密钥 (1-64 字符)</label>
        <div class="relative group">
          <span class="material-symbols-outlined absolute left-4 top-1/2 -translate-y-1/2 text-on-surface-variant/50 group-focus-within:text-primary transition-colors">key</span>
          <input
            type="password"
            v-model="key"
            placeholder="输入密钥，用于加密和解密"
            maxlength="64"
            class="w-full bg-surface-container/50 border border-outline-variant/20 rounded-xl py-3 pl-12 pr-4 text-on-surface focus:ring-2 focus:ring-primary/40 focus:border-primary transition-all outline-none"
          />
        </div>
      </div>

      <!-- Actions -->
      <div class="flex gap-3">
        <button
          @click="checkCapacity"
          :disabled="isCheckingCapacity || !message || !key"
          class="btn-ghost flex-1"
        >
          {{ isCheckingCapacity ? '检查中...' : '检查容量' }}
        </button>
        <button
          @click="handleEmbed"
          :disabled="isLoading || !message || !key"
          class="btn-primary flex-1"
        >
          {{ isLoading ? '生成中...' : '生成含密图像' }}
        </button>
      </div>

      <!-- Error -->
      <p v-if="error" class="text-error text-sm">{{ error }}</p>

      <!-- Result -->
      <div v-if="stegoImage" class="space-y-4 p-6 bg-surface-container rounded-xl">
        <h3 class="section-title">生成结果</h3>
        <img :src="stegoImage" class="max-w-full max-h-[300px] rounded-lg border border-outline-variant/10" />
        <button @click="downloadImage" class="btn-primary flex items-center gap-2">
          <span class="material-symbols-outlined text-lg">download</span>
          下载图像
        </button>
      </div>
    </div>
  </div>
</template>
