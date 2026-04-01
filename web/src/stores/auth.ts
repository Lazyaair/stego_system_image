import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import * as authApi from '../api/auth'
import { clearAllData } from '../db'

export const useAuthStore = defineStore('auth', () => {
  const token = ref<string | null>(localStorage.getItem('token'))
  const user = ref<{ user_id: string; username: string } | null>(
    JSON.parse(localStorage.getItem('user') || 'null')
  )

  const isAuthenticated = computed(() => !!token.value)

  async function register(username: string, password: string) {
    const res = await authApi.register(username, password)
    token.value = res.token
    user.value = { user_id: res.user_id, username: res.username }
    localStorage.setItem('token', res.token)
    localStorage.setItem('user', JSON.stringify(user.value))
  }

  async function login(username: string, password: string) {
    const res = await authApi.login(username, password)
    token.value = res.token
    user.value = { user_id: res.user_id, username: res.username }
    localStorage.setItem('token', res.token)
    localStorage.setItem('user', JSON.stringify(user.value))
  }

  async function logout() {
    token.value = null
    user.value = null
    localStorage.removeItem('token')
    localStorage.removeItem('user')
    await clearAllData()
  }

  return { token, user, isAuthenticated, register, login, logout }
})
