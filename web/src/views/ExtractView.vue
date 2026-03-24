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
  <div class="extract-view">
    <h1>消息提取</h1>

    <div class="form-group">
      <label>选择模型 (需与嵌入时使用的模型相同)</label>
      <select v-model="selectedModel">
        <option v-for="m in models" :key="m.id" :value="m.id">
          {{ m.name }}
        </option>
      </select>
    </div>

    <div class="form-group">
      <label>选择含密图像</label>
      <input type="file" accept="image/png" @change="onFileChange" />
      <img v-if="stegoPreview" :src="stegoPreview" class="preview" />
    </div>

    <div class="form-group">
      <label>密钥 (需与嵌入时使用的密钥相同)</label>
      <input
        type="password"
        v-model="key"
        placeholder="输入密钥"
        maxlength="64"
      />
    </div>

    <button
      @click="handleExtract"
      :disabled="isLoading || !stegoImageFile || !key"
      class="btn-primary"
    >
      {{ isLoading ? '提取中...' : '提取消息' }}
    </button>

    <p v-if="error" class="error">{{ error }}</p>

    <div v-if="extractedMessage" class="result">
      <h3>提取结果：</h3>
      <div class="message-box">{{ extractedMessage }}</div>
    </div>
  </div>
</template>

<style scoped>
.extract-view { max-width: 600px; }
.form-group { margin-bottom: 16px; }
.form-group label { display: block; margin-bottom: 4px; font-weight: bold; }
.form-group input,
.form-group select {
  width: 100%;
  padding: 10px;
  box-sizing: border-box;
  border: 1px solid #ddd;
  border-radius: 4px;
  font-size: 14px;
}
.preview {
  max-width: 100%;
  max-height: 300px;
  margin-top: 8px;
  border: 1px solid #ddd;
  border-radius: 4px;
}
.btn-primary {
  padding: 12px 24px;
  background: #42b883;
  color: white;
  border: none;
  border-radius: 4px;
  cursor: pointer;
  font-size: 14px;
}
.btn-primary:disabled { background: #ccc; }
.error { color: #e74c3c; margin-top: 10px; }
.result { margin-top: 20px; }
.message-box {
  padding: 16px;
  background: #f8f9fa;
  border-radius: 4px;
  border: 1px solid #e9ecef;
  white-space: pre-wrap;
  word-break: break-all;
  font-family: monospace;
}
</style>
