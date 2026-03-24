<script setup lang="ts">
import { ref } from 'vue'
import { stegoApi } from '../api/stego'

const stegoImageFile = ref<File | null>(null)
const stegoPreview = ref<string>('')
const key = ref('')
const extractedMessage = ref('')
const isLoading = ref(false)
const isDemo = ref(false)
const error = ref('')

function onFileChange(e: Event) {
  const target = e.target as HTMLInputElement
  const file = target.files?.[0]
  if (file) {
    stegoImageFile.value = file
    stegoPreview.value = URL.createObjectURL(file)
  }
}

async function handleExtract() {
  if (!stegoImageFile.value || !key.value) {
    error.value = '请选择图像并填写密钥'
    return
  }

  isLoading.value = true
  error.value = ''

  try {
    const result = await stegoApi.extract(stegoImageFile.value, key.value)
    extractedMessage.value = result.secret_message
    isDemo.value = result.is_demo
  } catch (e: any) {
    error.value = e.message || '请求失败'
  } finally {
    isLoading.value = false
  }
}
</script>

<template>
  <div class="extract-view">
    <h1>消息提取</h1>

    <div class="form-group">
      <label>选择含密图像</label>
      <input type="file" accept="image/*" @change="onFileChange" />
      <img v-if="stegoPreview" :src="stegoPreview" class="preview" />
    </div>

    <div class="form-group">
      <label>密钥</label>
      <input type="password" v-model="key" placeholder="输入密钥" />
    </div>

    <button @click="handleExtract" :disabled="isLoading">
      {{ isLoading ? '提取中...' : '提取消息' }}
    </button>

    <p v-if="error" class="error">{{ error }}</p>

    <div v-if="extractedMessage" class="result">
      <h3>提取结果：</h3>
      <p class="message-box">{{ extractedMessage }}</p>
      <span v-if="isDemo" class="demo-tag">[演示模式]</span>
    </div>
  </div>
</template>

<style scoped>
.extract-view { max-width: 500px; }
.form-group { margin-bottom: 16px; }
.form-group label { display: block; margin-bottom: 4px; font-weight: bold; }
.form-group input { width: 100%; padding: 8px; box-sizing: border-box; }
.preview { max-width: 100%; max-height: 200px; margin-top: 8px; border: 1px solid #ddd; }
button { padding: 10px 20px; background: #42b883; color: white; border: none; cursor: pointer; }
button:disabled { background: #ccc; }
.error { color: red; }
.result { margin-top: 20px; }
.message-box { padding: 12px; background: #f5f5f5; border-radius: 4px; }
.demo-tag { color: #ff9800; font-size: 14px; }
</style>
