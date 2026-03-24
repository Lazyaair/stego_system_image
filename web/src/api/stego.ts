import apiClient from './index'

export interface EmbedResponse {
  status: string
  stego_image: string
  is_demo: boolean
}

export interface ExtractResponse {
  status: string
  secret_message: string
  is_demo: boolean
}

export const stegoApi = {
  async embed(coverImage: File, message: string, key: string, embedRate: number = 0.5): Promise<EmbedResponse> {
    const formData = new FormData()
    formData.append('cover_image', coverImage)
    formData.append('secret_message', message)
    formData.append('key', key)
    formData.append('embed_rate', embedRate.toString())

    const response = await apiClient.post<EmbedResponse>('/api/v1/stego/embed', formData)
    return response.data
  },

  async extract(stegoImage: File, key: string): Promise<ExtractResponse> {
    const formData = new FormData()
    formData.append('stego_image', stegoImage)
    formData.append('key', key)

    const response = await apiClient.post<ExtractResponse>('/api/v1/stego/extract', formData)
    return response.data
  }
}
