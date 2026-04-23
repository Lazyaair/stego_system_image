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
  <div class="embed-view">
    <div class="tab-bar">
      <router-link to="/embed" class="tab active">消息嵌入</router-link>
      <router-link to="/extract" class="tab">消息提取</router-link>
    </div>

    <div class="form-group">
      <label>选择模型</label>
      <select v-model="selectedModel">
        <option v-for="m in models" :key="m.id" :value="m.id">
          {{ m.name }}
        </option>
      </select>
    </div>

    <div class="form-group">
      <label>秘密消息</label>
      <textarea
        v-model="message"
        rows="4"
        placeholder="输入要隐藏的秘密消息..."
        @blur="checkCapacity"
      ></textarea>
      <div v-if="capacityInfo" class="capacity-info" :class="{ invalid: !capacityInfo.valid }">
        消息长度: {{ message.length }} bytes / 最大容量: {{ capacityInfo.max_capacity }} bytes
      </div>
    </div>

    <div class="form-group">
      <label>密钥 (1-64 字符)</label>
      <input
        type="password"
        v-model="key"
        placeholder="输入密钥，用于加密和解密"
        maxlength="64"
      />
    </div>

    <div class="actions">
      <button
        @click="checkCapacity"
        :disabled="isCheckingCapacity || !message || !key"
        class="btn-secondary"
      >
        {{ isCheckingCapacity ? '检查中...' : '检查容量' }}
      </button>
      <button
        @click="handleEmbed"
        :disabled="isLoading || !message || !key"
        class="btn-primary"
      >
        {{ isLoading ? '生成中...' : '生成含密图像' }}
      </button>
    </div>

    <p v-if="error" class="error">{{ error }}</p>

    <div v-if="stegoImage" class="result">
      <h3>生成结果：</h3>
      <img :src="stegoImage" class="preview" />
      <button @click="downloadImage" class="btn-download">下载图像</button>
    </div>
  </div>
</template>

<style scoped>
.embed-view { max-width: 600px; }
.tab-bar {
  display: flex;
  margin-bottom: 20px;
  border-bottom: 2px solid #e0e0e0;
}
.tab {
  flex: 1;
  text-align: center;
  padding: 12px 0;
  text-decoration: none;
  color: #888;
  font-size: 16px;
  font-weight: bold;
  border-bottom: 2px solid transparent;
  margin-bottom: -2px;
  transition: color 0.2s, border-color 0.2s;
}
.tab:hover { color: #333; }
.tab.active {
  color: #42b883;
  border-bottom-color: #42b883;
}
.form-group { margin-bottom: 16px; }
.form-group label { display: block; margin-bottom: 4px; font-weight: bold; }
.form-group input,
.form-group textarea,
.form-group select {
  width: 100%;
  padding: 10px;
  box-sizing: border-box;
  border: 1px solid #ddd;
  border-radius: 4px;
  font-size: 14px;
}
.capacity-info {
  margin-top: 4px;
  font-size: 12px;
  color: #666;
}
.capacity-info.invalid {
  color: #e74c3c;
}
.preview {
  max-width: 100%;
  max-height: 300px;
  margin-top: 8px;
  border: 1px solid #ddd;
  border-radius: 4px;
}
.actions {
  display: flex;
  gap: 10px;
  margin-top: 16px;
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
.btn-secondary {
  padding: 12px 24px;
  background: #fff;
  color: #333;
  border: 1px solid #ddd;
  border-radius: 4px;
  cursor: pointer;
  font-size: 14px;
}
.btn-secondary:disabled { color: #ccc; }
.btn-download {
  margin-top: 10px;
  padding: 8px 16px;
  background: #3498db;
  color: white;
  border: none;
  border-radius: 4px;
  cursor: pointer;
}
.error { color: #e74c3c; margin-top: 10px; }
.result { margin-top: 20px; }
</style>
