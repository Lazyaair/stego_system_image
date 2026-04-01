import api from './index'

export interface AuthResponse {
  user_id: string
  username: string
  token: string
}

export interface UserInfo {
  user_id: string
  username: string
  created_at: string
}

export async function register(username: string, password: string): Promise<AuthResponse> {
  const { data } = await api.post('/api/v1/auth/register', { username, password })
  return data
}

export async function login(username: string, password: string): Promise<AuthResponse> {
  const { data } = await api.post('/api/v1/auth/login', { username, password })
  return data
}

export async function getMe(): Promise<UserInfo> {
  const { data } = await api.get('/api/v1/auth/me')
  return data
}

export async function getUserPublic(userId: string): Promise<{ user_id: string; username: string }> {
  const { data } = await api.get(`/api/v1/auth/user/${userId}/public`)
  return data
}
