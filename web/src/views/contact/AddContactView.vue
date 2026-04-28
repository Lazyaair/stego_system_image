<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { useContactsStore } from '../../stores/contacts'

const router = useRouter()
const contactsStore = useContactsStore()

const code = ref('')
const error = ref('')
const loading = ref(false)

async function handleAdd() {
  error.value = ''
  loading.value = true
  try {
    const contact = await contactsStore.addContactByCode(code.value.trim())
    router.push(`/chat/${contact.user_id}`)
  } catch (e: any) {
    error.value = e.response?.data?.detail || '无效的邀请码'
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="h-full flex flex-col">
    <!-- Header -->
    <div class="flex items-center gap-3 px-6 py-5 border-b border-outline-variant/10">
      <button @click="router.back()" class="text-on-surface-variant hover:text-on-surface transition-colors">
        <span class="material-symbols-outlined">arrow_back</span>
      </button>
      <h2 class="text-xl font-bold text-on-surface">添加联系人</h2>
    </div>

    <!-- Form -->
    <div class="max-w-md mx-auto w-full px-6 py-8">
      <form @submit.prevent="handleAdd" class="space-y-6">
        <div class="space-y-2">
          <label class="text-xs font-bold text-on-surface-variant uppercase ml-1">邀请码</label>
          <div class="relative group">
            <span class="material-symbols-outlined absolute left-4 top-1/2 -translate-y-1/2 text-on-surface-variant/50 group-focus-within:text-primary transition-colors">qr_code</span>
            <input
              v-model="code"
              type="text"
              placeholder="输入对方的邀请码"
              required
              class="w-full bg-surface-container/50 border border-outline-variant/20 rounded-xl py-4 pl-12 pr-4 text-on-surface focus:ring-2 focus:ring-primary/40 focus:border-primary transition-all outline-none"
            />
          </div>
        </div>

        <p v-if="error" class="text-error text-sm">{{ error }}</p>

        <button
          type="submit"
          :disabled="loading || !code.trim()"
          class="w-full py-4 bg-primary text-on-primary font-bold rounded-xl shadow-lg shadow-primary/30 hover:bg-primary/90 active:scale-[0.98] transition-all flex items-center justify-center gap-2 disabled:opacity-40 disabled:cursor-not-allowed"
        >
          <span class="material-symbols-outlined text-xl">person_add</span>
          {{ loading ? '查找中...' : '添加联系人' }}
        </button>
      </form>
    </div>
  </div>
</template>
