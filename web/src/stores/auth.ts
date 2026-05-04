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

  /** Called when kicked by another device — clear token but keep local data */
  function onKicked() {
    token.value = null
    user.value = null
    localStorage.removeItem('token')
    localStorage.removeItem('user')
  }

  /** 启动时/WS 鉴权失败时验证本地 token 是否仍被 server 接受。失败则清除并触发跳登录。 */
  async function verify(): Promise<boolean> {
    if (!token.value) return false
    try {
      const me = await authApi.getMe()
      user.value = { user_id: me.user_id, username: me.username }
      localStorage.setItem('user', JSON.stringify(user.value))
      return true
    } catch {
      onKicked()
      return false
    }
  }

  return { token, user, isAuthenticated, register, login, logout, onKicked, verify }
})
