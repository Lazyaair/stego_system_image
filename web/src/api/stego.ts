import apiClient from './index'

export interface Model {
  id: string
  name: string
  default: boolean
}

export interface Algorithm {
  id: string
  name: string
  default: boolean
  chat_default: boolean
  models: Model[]
}

export interface ModelsResponse {
  models: Model[]
}

export interface AlgorithmsResponse {
  algorithms: Algorithm[]
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
  algorithm?: string
  model?: string
  message_length?: number
  error?: string
  max_capacity?: number
  is_demo: boolean
}

export interface ExtractResponse {
  status: string
  secret_message?: string
  algorithm?: string
  model?: string
  error?: string
  is_demo: boolean
}

export interface MaxCapacityResponse {
  max_capacity: number
}

export const stegoApi = {
  async getAlgorithms(): Promise<AlgorithmsResponse> {
    const response = await apiClient.get<AlgorithmsResponse>('/api/v1/stego/algorithms')
    return response.data
  },

  async getModels(algorithm?: string): Promise<ModelsResponse> {
    const params = algorithm ? { algorithm } : undefined
    const response = await apiClient.get<ModelsResponse>('/api/v1/stego/models', { params })
    return response.data
  },

  async checkCapacity(
    message: string,
    key: string,
    model?: string,
    algorithm?: string,
  ): Promise<CapacityResponse> {
    const formData = new FormData()
    formData.append('message', message)
    formData.append('key', key)
    if (model) formData.append('model', model)
    if (algorithm) formData.append('algorithm', algorithm)

    const response = await apiClient.post<CapacityResponse>('/api/v1/stego/capacity', formData)
    return response.data
  },

  async embed(
    message: string,
    key: string,
    model?: string,
    algorithm?: string,
  ): Promise<EmbedResponse> {
    const formData = new FormData()
    formData.append('message', message)
    formData.append('key', key)
    if (model) formData.append('model', model)
    if (algorithm) formData.append('algorithm', algorithm)

    const response = await apiClient.post<EmbedResponse>('/api/v1/stego/embed', formData)
    return response.data
  },

  async extract(
    stegoImage: File,
    key: string,
    model?: string,
    algorithm?: string,
  ): Promise<ExtractResponse> {
    const formData = new FormData()
    formData.append('stego_image', stegoImage)
    formData.append('key', key)
    if (model) formData.append('model', model)
    if (algorithm) formData.append('algorithm', algorithm)

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
  },

  async getMaxCapacity(
    key: string,
    model?: string,
    algorithm?: string,
  ): Promise<MaxCapacityResponse> {
    const params: Record<string, string> = { key }
    if (model) params.model = model
    if (algorithm) params.algorithm = algorithm
    const { data } = await apiClient.get('/api/v1/stego/max-capacity', { params })
    return data
  },
}
