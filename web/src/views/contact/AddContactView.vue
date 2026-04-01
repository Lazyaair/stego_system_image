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
    error.value = e.response?.data?.detail || 'Invalid invite code'
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="add-contact">
    <div class="header">
      <button class="back-btn" @click="router.back()">&#8592;</button>
      <h2>Add Contact</h2>
    </div>
    <form @submit.prevent="handleAdd" class="form">
      <div class="form-group">
        <label>Invite Code</label>
        <input v-model="code" type="text" placeholder="Enter invite code" required />
      </div>
      <p v-if="error" class="error">{{ error }}</p>
      <button type="submit" :disabled="loading || !code.trim()">
        {{ loading ? 'Looking up...' : 'Add Contact' }}
      </button>
    </form>
  </div>
</template>

<style scoped>
.add-contact { padding: 0; }
.header {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 16px;
}
.back-btn {
  background: none;
  border: none;
  font-size: 20px;
  cursor: pointer;
}
.form { padding: 0 16px; }
.form-group { margin-bottom: 16px; }
.form-group label { display: block; margin-bottom: 4px; font-weight: 500; }
.form-group input {
  width: 100%;
  padding: 10px 12px;
  border: 1px solid #ccc;
  border-radius: 6px;
  font-size: 14px;
  box-sizing: border-box;
}
button[type="submit"] {
  width: 100%;
  padding: 10px;
  background: #4a90d9;
  color: white;
  border: none;
  border-radius: 6px;
  font-size: 16px;
  cursor: pointer;
}
button:disabled { opacity: 0.5; }
.error { color: #e53e3e; font-size: 14px; }
</style>
