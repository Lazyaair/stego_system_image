import apiClient from './index'

export interface Model {
  id: string
  name: string
  default: boolean
}

export interface ModelsResponse {
  models: Model[]
}

export interface CapacityResponse {
  valid: boolean
  message_length: number
  max_capacity: number
  error?: string
}

export interface EmbedResponse {
  status: string
  stego_image?: string
  model?: string
  message_length?: number
  error?: string
  max_capacity?: number
  is_demo: boolean
}

export interface ExtractResponse {
  status: string
  secret_message?: string
  model?: string
  error?: string
  is_demo: boolean
}

export const stegoApi = {
  async getModels(): Promise<ModelsResponse> {
    const response = await apiClient.get<ModelsResponse>('/api/v1/stego/models')
    return response.data
  },

  async checkCapacity(message: string, key: string, model: string): Promise<CapacityResponse> {
    const formData = new FormData()
    formData.append('message', message)
    formData.append('key', key)
    formData.append('model', model)

    const response = await apiClient.post<CapacityResponse>('/api/v1/stego/capacity', formData)
    return response.data
  },

  async embed(message: string, key: string, model: string): Promise<EmbedResponse> {
    const formData = new FormData()
    formData.append('message', message)
    formData.append('key', key)
    formData.append('model', model)

    const response = await apiClient.post<EmbedResponse>('/api/v1/stego/embed', formData)
    return response.data
  },

  async extract(stegoImage: File, key: string, model: string): Promise<ExtractResponse> {
    const formData = new FormData()
    formData.append('stego_image', stegoImage)
    formData.append('key', key)
    formData.append('model', model)

    const response = await apiClient.post<ExtractResponse>('/api/v1/stego/extract', formData)
    return response.data
  },

  validateKey(key: string): { valid: boolean; error?: string } {
    if (!key) {
      return { valid: false, error: '密钥不能为空' }
    }
    if (key.length > 64) {
      return { valid: false, error: '密钥长度不能超过 64 字符' }
    }
    return { valid: true }
  }
}
