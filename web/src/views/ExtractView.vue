<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { stegoApi, type Model } from '../api/stego'

const models = ref<Model[]>([])
const selectedModel = ref('')
const stegoImageFile = ref<File | null>(null)
const stegoPreview = ref<string>('')
const key = ref('')
const extractedMessage = ref('')
const isLoading = ref(false)
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

function onFileChange(e: Event) {
  const target = e.target as HTMLInputElement
  const file = target.files?.[0]
  if (file) {
    stegoImageFile.value = file
    stegoPreview.value = URL.createObjectURL(file)
  }
}

async function handleExtract() {
  const keyCheck = stegoApi.validateKey(key.value)
  if (!keyCheck.valid) {
    error.value = keyCheck.error || ''
    return
  }

  if (!stegoImageFile.value) {
    error.value = '请选择含密图像'
    return
  }

  isLoading.value = true
  error.value = ''
  extractedMessage.value = ''

  try {
    const result = await stegoApi.extract(stegoImageFile.value, key.value, selectedModel.value)
    if (result.status === 'success' && result.secret_message) {
      extractedMessage.value = result.secret_message
    } else {
      error.value = result.error || '提取失败'
    }
  } catch (e: any) {
    error.value = e.response?.data?.detail || '请求失败'
  } finally {
    isLoading.value = false
  }
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
        class="flex-1 py-3 text-center text-sm font-bold uppercase tracking-wider transition-all text-on-surface-variant hover:text-on-surface border-b-2 border-transparent"
      >消息嵌入</router-link>
      <router-link
        to="/extract"
        class="flex-1 py-3 text-center text-sm font-bold uppercase tracking-wider transition-all text-primary border-b-2 border-primary"
      >消息提取</router-link>
    </div>

    <!-- Form -->
    <div class="space-y-6">
      <!-- Model select -->
      <div class="space-y-2">
        <label class="text-xs font-bold text-on-surface-variant uppercase ml-1">选择模型 (需与嵌入时使用的模型相同)</label>
        <select v-model="selectedModel" class="input-field">
          <option v-for="m in models" :key="m.id" :value="m.id">{{ m.name }}</option>
        </select>
      </div>

      <!-- File upload -->
      <div class="space-y-2">
        <label class="text-xs font-bold text-on-surface-variant uppercase ml-1">选择含密图像</label>
        <label class="flex flex-col items-center justify-center gap-3 p-8 border-2 border-dashed border-outline-variant/30 rounded-xl cursor-pointer hover:border-primary/40 hover:bg-surface-container/30 transition-all">
          <span class="material-symbols-outlined text-4xl text-on-surface-variant/40">upload_file</span>
          <span class="text-sm text-on-surface-variant">点击选择 PNG 图像</span>
          <input type="file" accept="image/png" @change="onFileChange" class="hidden" />
        </label>
        <img v-if="stegoPreview" :src="stegoPreview" class="max-w-full max-h-[300px] rounded-lg border border-outline-variant/10 mt-2" />
      </div>

      <!-- Key -->
      <div class="space-y-2">
        <label class="text-xs font-bold text-on-surface-variant uppercase ml-1">密钥 (需与嵌入时使用的密钥相同)</label>
        <div class="relative group">
          <span class="material-symbols-outlined absolute left-4 top-1/2 -translate-y-1/2 text-on-surface-variant/50 group-focus-within:text-primary transition-colors">key</span>
          <input
            type="password"
            v-model="key"
            placeholder="输入密钥"
            maxlength="64"
            class="w-full bg-surface-container/50 border border-outline-variant/20 rounded-xl py-3 pl-12 pr-4 text-on-surface focus:ring-2 focus:ring-primary/40 focus:border-primary transition-all outline-none"
          />
        </div>
      </div>

      <!-- Submit -->
      <button
        @click="handleExtract"
        :disabled="isLoading || !stegoImageFile || !key"
        class="btn-primary w-full flex items-center justify-center gap-2"
      >
        <span class="material-symbols-outlined text-lg">lock_open</span>
        {{ isLoading ? '提取中...' : '提取消息' }}
      </button>

      <!-- Error -->
      <p v-if="error" class="text-error text-sm">{{ error }}</p>

      <!-- Result -->
      <div v-if="extractedMessage" class="space-y-3 p-6 bg-surface-container rounded-xl">
        <h3 class="section-title">提取结果</h3>
        <div class="p-4 bg-surface-container-lowest rounded-lg border border-outline-variant/10 font-mono text-sm text-tertiary whitespace-pre-wrap break-all leading-relaxed">
          {{ extractedMessage }}
        </div>
      </div>
    </div>
  </div>
</template>
