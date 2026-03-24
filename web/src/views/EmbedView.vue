<script setup lang="ts">
import { ref } from 'vue'
import { stegoApi } from '../api/stego'

const coverImage = ref<File | null>(null)
const coverPreview = ref<string>('')
const message = ref('')
const key = ref('')
const stegoImage = ref<string>('')
const isLoading = ref(false)
const isDemo = ref(false)
const error = ref('')

function onFileChange(e: Event) {
  const target = e.target as HTMLInputElement
  const file = target.files?.[0]
  if (file) {
    coverImage.value = file
    coverPreview.value = URL.createObjectURL(file)
  }
}

async function handleEmbed() {
  if (!coverImage.value || !message.value || !key.value) {
    error.value = '请填写所有字段'
    return
  }

  isLoading.value = true
  error.value = ''

  try {
    const result = await stegoApi.embed(coverImage.value, message.value, key.value)
    stegoImage.value = result.stego_image
    isDemo.value = result.is_demo
  } catch (e: any) {
    error.value = e.message || '请求失败'
  } finally {
    isLoading.value = false
  }
}
</script>

<template>
  <div class="embed-view">
    <h1>消息嵌入</h1>

    <div class="form-group">
      <label>选择载体图像</label>
      <input type="file" accept="image/*" @change="onFileChange" />
      <img v-if="coverPreview" :src="coverPreview" class="preview" />
    </div>

    <div class="form-group">
      <label>秘密消息</label>
      <textarea v-model="message" rows="3" placeholder="输入秘密消息..."></textarea>
    </div>

    <div class="form-group">
      <label>密钥</label>
      <input type="password" v-model="key" placeholder="输入密钥" />
    </div>

    <button @click="handleEmbed" :disabled="isLoading">
      {{ isLoading ? '生成中...' : '生成含密图像' }}
    </button>

    <p v-if="error" class="error">{{ error }}</p>

    <div v-if="stegoImage" class="result">
      <h3>生成结果：</h3>
      <img :src="stegoImage" class="preview" />
      <span v-if="isDemo" class="demo-tag">[演示模式]</span>
    </div>
  </div>
</template>

<style scoped>
.embed-view { max-width: 500px; }
.form-group { margin-bottom: 16px; }
.form-group label { display: block; margin-bottom: 4px; font-weight: bold; }
.form-group input, .form-group textarea { width: 100%; padding: 8px; box-sizing: border-box; }
.preview { max-width: 100%; max-height: 200px; margin-top: 8px; border: 1px solid #ddd; }
button { padding: 10px 20px; background: #42b883; color: white; border: none; cursor: pointer; }
button:disabled { background: #ccc; }
.error { color: red; }
.result { margin-top: 20px; }
.demo-tag { color: #ff9800; font-size: 14px; }
</style>
