type MessageHandler = (message: any) => void

class WsClient {
  private ws: WebSocket | null = null
  private url: string = ''
  private handlers: Map<string, MessageHandler[]> = new Map()
  private reconnectTimer: ReturnType<typeof setTimeout> | null = null
  private reconnectAttempts = 0
  private maxReconnectAttempts = 10
  private shouldReconnect = false

  connect(token: string) {
    this.shouldReconnect = true
    this.reconnectAttempts = 0
    const base = (import.meta.env.VITE_API_BASE_URL || 'http://localhost:8000')
      .replace('http://', 'ws://')
      .replace('https://', 'wss://')
    this.url = `${base}/ws?token=${token}`
    this._connect()
  }

  private _connect() {
    if (this.ws) {
      this.ws.close()
    }
    this.ws = new WebSocket(this.url)

    this.ws.onopen = () => {
      this.reconnectAttempts = 0
      this.emit('_connected', {})
    }

    this.ws.onmessage = (event) => {
      try {
        const message = JSON.parse(event.data)
        this.emit(message.type, message)
      } catch {
        // ignore invalid JSON
      }
    }

    this.ws.onclose = (event) => {
      if (event.code === 4001) {
        // Kicked by another device, don't reconnect
        this.shouldReconnect = false
        this.emit('_kicked', {})
        return
      }
      if (event.code === 4003) {
        // Auth failed, don't reconnect
        this.shouldReconnect = false
        this.emit('_auth_failed', {})
        return
      }
      if (this.shouldReconnect && this.reconnectAttempts < this.maxReconnectAttempts) {
        const delay = Math.min(1000 * Math.pow(2, this.reconnectAttempts), 30000)
        this.reconnectTimer = setTimeout(() => {
          this.reconnectAttempts++
          this._connect()
        }, delay)
      }
    }

    this.ws.onerror = () => {
      // onclose will handle reconnection
    }
  }

  disconnect() {
    this.shouldReconnect = false
    if (this.reconnectTimer) {
      clearTimeout(this.reconnectTimer)
    }
    if (this.ws) {
      this.ws.close()
      this.ws = null
    }
  }

  send(message: any) {
    if (this.ws?.readyState === WebSocket.OPEN) {
      this.ws.send(JSON.stringify(message))
    }
  }

  on(type: string, handler: MessageHandler) {
    if (!this.handlers.has(type)) {
      this.handlers.set(type, [])
    }
    this.handlers.get(type)!.push(handler)
  }

  off(type: string, handler: MessageHandler) {
    const handlers = this.handlers.get(type)
    if (handlers) {
      const idx = handlers.indexOf(handler)
      if (idx >= 0) handlers.splice(idx, 1)
    }
  }

  private emit(type: string, message: any) {
    const handlers = this.handlers.get(type) || []
    handlers.forEach((h) => h(message))
  }
}

export const wsClient = new WsClient()
