<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { useAuthStore } from '../../stores/auth'
import { getMyCode, resetCode } from '../../api/invite'

const router = useRouter()
const auth = useAuthStore()

const inviteCode = ref('')
const inviteLink = ref('')
const loading = ref(false)

onMounted(async () => {
  try {
    const res = await getMyCode()
    inviteCode.value = res.code
    inviteLink.value = res.link
  } catch {
    // ignore
  }
})

async function handleResetCode() {
  loading.value = true
  try {
    const res = await resetCode()
    inviteCode.value = res.code
    inviteLink.value = res.link
  } finally {
    loading.value = false
  }
}

function copyCode() {
  navigator.clipboard.writeText(inviteCode.value)
}

async function handleLogout() {
  await auth.logout()
  router.push('/login')
}
</script>

<template>
  <div class="profile-view">
    <h2>Profile</h2>
    <div class="user-info">
      <div class="avatar">{{ auth.user?.username?.[0]?.toUpperCase() }}</div>
      <div class="username">{{ auth.user?.username }}</div>
    </div>

    <div class="section">
      <h3>My Invite Code</h3>
      <p class="desc">Share this code with friends so they can add you.</p>
      <div class="code-row">
        <code>{{ inviteCode }}</code>
        <button @click="copyCode" class="small-btn">Copy</button>
        <button @click="handleResetCode" :disabled="loading" class="small-btn danger">Reset</button>
      </div>
    </div>

    <button class="logout-btn" @click="handleLogout">Logout</button>
  </div>
</template>

<style scoped>
.profile-view { padding: 16px; }
.user-info {
  display: flex;
  align-items: center;
  gap: 16px;
  margin-bottom: 32px;
}
.avatar {
  width: 60px;
  height: 60px;
  border-radius: 50%;
  background: #4a90d9;
  color: white;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 24px;
  font-weight: bold;
}
.username { font-size: 20px; font-weight: 600; }
.section { margin-bottom: 24px; }
.desc { font-size: 13px; color: #888; margin-bottom: 8px; }
.code-row { display: flex; align-items: center; gap: 8px; }
.code-row code {
  background: #f0f0f0;
  padding: 8px 16px;
  border-radius: 6px;
  font-size: 16px;
  letter-spacing: 1px;
}
.small-btn {
  padding: 6px 12px;
  border: 1px solid #ccc;
  border-radius: 6px;
  background: white;
  cursor: pointer;
}
.danger { color: #e53e3e; border-color: #e53e3e; }
.logout-btn {
  width: 100%;
  padding: 12px;
  background: #e53e3e;
  color: white;
  border: none;
  border-radius: 6px;
  font-size: 16px;
  cursor: pointer;
  margin-top: 32px;
}
</style>
