import api from './index'

export interface InviteCodeResponse {
  code: string
  link: string
  created_at: string | null
}

export interface InviteLookupResponse {
  user_id: string
  username: string
}

export async function getMyCode(): Promise<InviteCodeResponse> {
  const { data } = await api.get('/api/v1/invite/my-code')
  return data
}

export async function resetCode(): Promise<InviteCodeResponse> {
  const { data } = await api.post('/api/v1/invite/reset')
  return data
}

export async function lookupCode(code: string): Promise<InviteLookupResponse> {
  const { data } = await api.get(`/api/v1/invite/${code}`)
  return data
}
