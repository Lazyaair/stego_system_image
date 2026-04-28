# User System Web Frontend Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add user auth, contacts, chat with WebSocket messaging, and local IndexedDB storage to the Vue.js web app.

**Architecture:** Extend the existing Vue 3 app with Pinia stores for state management, IndexedDB (via idb) for local persistence, a WebSocket client for real-time messaging, and new views for auth/chat/contacts. Axios interceptor handles JWT token injection.

**Tech Stack:** Vue 3, TypeScript, Pinia, Vue Router, Axios, idb (IndexedDB wrapper), native WebSocket

**Depends on:** Server plan (`2026-03-31-user-system-server.md`) must be completed first.

**Spec:** `docs/superpowers/specs/2026-03-31-user-system-design.md`

---

## File Structure

| Action | File | Responsibility |
|--------|------|----------------|
| Create | `web/src/api/auth.ts` | Auth API calls |
| Create | `web/src/api/invite.ts` | Invite API calls |
| Create | `web/src/api/websocket.ts` | WebSocket client |
| Modify | `web/src/api/index.ts` | Add auth interceptor |
| Create | `web/src/stores/auth.ts` | Auth state (user, token) |
| Create | `web/src/stores/contacts.ts` | Contacts store (IndexedDB backed) |
| Create | `web/src/stores/chat.ts` | Chat/messages store |
| Create | `web/src/db/index.ts` | IndexedDB schema and helpers |
| Create | `web/src/views/auth/LoginView.vue` | Login page |
| Create | `web/src/views/auth/RegisterView.vue` | Register page |
| Create | `web/src/views/chat/ChatListView.vue` | Conversation list |
| Create | `web/src/views/chat/ChatView.vue` | Chat conversation |
| Create | `web/src/views/contact/ContactsView.vue` | Contact list |
| Create | `web/src/views/contact/AddContactView.vue` | Add contact by invite code |
| Create | `web/src/views/profile/ProfileView.vue` | User profile & invite code |
| Create | `web/src/components/BottomNav.vue` | Bottom navigation bar |
| Create | `web/src/components/MessageBubble.vue` | Chat message component |
| Create | `web/src/components/MessageInput.vue` | Chat input with stego toggle |
| Modify | `web/src/router/index.ts` | Add routes and auth guard |
| Modify | `web/src/App.vue` | Update layout with BottomNav |

---

## Chunk 1: Foundation (Dependencies, DB, Auth Store, API)

### Task 1: Install Dependencies

- [ ] **Step 1: Install idb package**

Run: `cd web && npm install idb`

- [ ] **Step 2: Commit**

```bash
git add web/package.json web/package-lock.json
git commit -m "feat(web): add idb dependency for IndexedDB"
```

---

### Task 2: IndexedDB Setup

**Files:**
- Create: `web/src/db/index.ts`

- [ ] **Step 1: Create IndexedDB schema**

```typescript
import { openDB, type IDBPDatabase } from 'idb'

const DB_NAME = 'stego-app'
const DB_VERSION = 1

export interface Contact {
  user_id: string
  username: string
  nickname: string
  status: 'pending' | 'accepted'
  added_at: string
}

export interface Message {
  id: string
  contact_id: string
  direction: 'sent' | 'received'
  content: string
  content_type: 'text' | 'stego'
  stego_image?: string // base64
  status: 'sending' | 'sent' | 'delivered' | 'read' | 'failed'
  burn_after: number
  burned: boolean
  revoked: boolean
  created_at: string
}

export interface BlacklistEntry {
  user_id: string
  username: string
  blocked_at: string
}

let dbPromise: Promise<IDBPDatabase> | null = null

function getDB() {
  if (!dbPromise) {
    dbPromise = openDB(DB_NAME, DB_VERSION, {
      upgrade(db) {
        if (!db.objectStoreNames.contains('contacts')) {
          db.createObjectStore('contacts', { keyPath: 'user_id' })
        }
        if (!db.objectStoreNames.contains('messages')) {
          const msgStore = db.createObjectStore('messages', { keyPath: 'id' })
          msgStore.createIndex('by_contact', 'contact_id')
          msgStore.createIndex('by_created', 'created_at')
        }
        if (!db.objectStoreNames.contains('blacklist')) {
          db.createObjectStore('blacklist', { keyPath: 'user_id' })
        }
        if (!db.objectStoreNames.contains('settings')) {
          db.createObjectStore('settings', { keyPath: 'key' })
        }
      },
    })
  }
  return dbPromise
}

// Contacts
export async function getAllContacts(): Promise<Contact[]> {
  const db = await getDB()
  return db.getAll('contacts')
}

export async function getContact(userId: string): Promise<Contact | undefined> {
  const db = await getDB()
  return db.get('contacts', userId)
}

export async function saveContact(contact: Contact): Promise<void> {
  const db = await getDB()
  await db.put('contacts', contact)
}

export async function deleteContact(userId: string): Promise<void> {
  const db = await getDB()
  await db.delete('contacts', userId)
}

// Messages
export async function getMessagesByContact(contactId: string): Promise<Message[]> {
  const db = await getDB()
  const all = await db.getAllFromIndex('messages', 'by_contact', contactId)
  return all.sort((a, b) => a.created_at.localeCompare(b.created_at))
}

export async function saveMessage(message: Message): Promise<void> {
  const db = await getDB()
  await db.put('messages', message)
}

export async function getMessage(id: string): Promise<Message | undefined> {
  const db = await getDB()
  return db.get('messages', id)
}

export async function updateMessageStatus(id: string, status: Message['status']): Promise<void> {
  const db = await getDB()
  const msg = await db.get('messages', id)
  if (msg) {
    msg.status = status
    await db.put('messages', msg)
  }
}

export async function deleteMessage(id: string): Promise<void> {
  const db = await getDB()
  await db.delete('messages', id)
}

// Blacklist
export async function getBlacklist(): Promise<BlacklistEntry[]> {
  const db = await getDB()
  return db.getAll('blacklist')
}

export async function addToBlacklist(entry: BlacklistEntry): Promise<void> {
  const db = await getDB()
  await db.put('blacklist', entry)
}

export async function removeFromBlacklist(userId: string): Promise<void> {
  const db = await getDB()
  await db.delete('blacklist', userId)
}

export async function isBlacklisted(userId: string): Promise<boolean> {
  const db = await getDB()
  const entry = await db.get('blacklist', userId)
  return !!entry
}

// Clear all data (for logout)
export async function clearAllData(): Promise<void> {
  const db = await getDB()
  const tx = db.transaction(['contacts', 'messages', 'blacklist', 'settings'], 'readwrite')
  await Promise.all([
    tx.objectStore('contacts').clear(),
    tx.objectStore('messages').clear(),
    tx.objectStore('blacklist').clear(),
    tx.objectStore('settings').clear(),
    tx.done,
  ])
}
```

- [ ] **Step 2: Commit**

```bash
git add web/src/db/
git commit -m "feat(web): add IndexedDB schema for contacts and messages"
```

---

### Task 3: Auth API and Axios Interceptor

**Files:**
- Create: `web/src/api/auth.ts`
- Create: `web/src/api/invite.ts`
- Modify: `web/src/api/index.ts`

- [ ] **Step 1: Create auth API**

`web/src/api/auth.ts`:
```typescript
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
```

- [ ] **Step 2: Create invite API**

`web/src/api/invite.ts`:
```typescript
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
```

- [ ] **Step 3: Add auth interceptor to Axios client**

Replace `web/src/api/index.ts` with:
```typescript
import axios from 'axios'

const api = axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL || 'http://localhost:8000',
  timeout: 300000,
})

// Request interceptor: inject JWT token
api.interceptors.request.use((config) => {
  const token = localStorage.getItem('token')
  if (token) {
    config.headers.Authorization = `Bearer ${token}`
  }
  return config
})

// Response interceptor: handle 401
api.interceptors.response.use(
  (response) => response,
  (error) => {
    if (error.response?.status === 401) {
      localStorage.removeItem('token')
      localStorage.removeItem('user')
      window.location.href = '/login'
    }
    return Promise.reject(error)
  }
)

export default api
```

- [ ] **Step 4: Commit**

```bash
git add web/src/api/
git commit -m "feat(web): add auth/invite API and axios interceptor"
```

---

### Task 4: Auth Store

**Files:**
- Create: `web/src/stores/auth.ts`

- [ ] **Step 1: Create auth store**

```typescript
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
```

- [ ] **Step 2: Commit**

```bash
git add web/src/stores/
git commit -m "feat(web): add auth store with login/register/logout"
```

---

## Chunk 2: WebSocket Client and Chat Store

### Task 5: WebSocket Client

**Files:**
- Create: `web/src/api/websocket.ts`

- [ ] **Step 1: Create WebSocket client with reconnection**

```typescript
type MessageHandler = (message: any) => void

class WsClient {
  private ws: WebSocket | null = null
  private url: string = ''
  private token: string = ''
  private handlers: Map<string, MessageHandler[]> = new Map()
  private reconnectTimer: ReturnType<typeof setTimeout> | null = null
  private reconnectAttempts = 0
  private maxReconnectAttempts = 10
  private shouldReconnect = false

  connect(token: string) {
    this.token = token
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
```

- [ ] **Step 2: Commit**

```bash
git add web/src/api/websocket.ts
git commit -m "feat(web): add WebSocket client with auto-reconnect"
```

---

### Task 6: Contacts Store

**Files:**
- Create: `web/src/stores/contacts.ts`

- [ ] **Step 1: Create contacts store**

```typescript
import { defineStore } from 'pinia'
import { ref } from 'vue'
import type { Contact } from '../db'
import {
  getAllContacts,
  saveContact,
  deleteContact as dbDeleteContact,
  getContact,
  getBlacklist,
  addToBlacklist as dbAddToBlacklist,
  removeFromBlacklist as dbRemoveFromBlacklist,
  isBlacklisted,
} from '../db'
import { lookupCode } from '../api/invite'

export const useContactsStore = defineStore('contacts', () => {
  const contacts = ref<Contact[]>([])
  const blacklist = ref<string[]>([])

  async function loadContacts() {
    contacts.value = await getAllContacts()
    const bl = await getBlacklist()
    blacklist.value = bl.map((b) => b.user_id)
  }

  async function addContactByCode(code: string): Promise<Contact> {
    const info = await lookupCode(code)
    const existing = await getContact(info.user_id)
    if (existing) return existing

    const contact: Contact = {
      user_id: info.user_id,
      username: info.username,
      nickname: '',
      status: 'accepted',
      added_at: new Date().toISOString(),
    }
    await saveContact(contact)
    await loadContacts()
    return contact
  }

  async function acceptContact(userId: string, username: string) {
    const contact: Contact = {
      user_id: userId,
      username,
      nickname: '',
      status: 'accepted',
      added_at: new Date().toISOString(),
    }
    await saveContact(contact)
    await loadContacts()
  }

  async function removeContact(userId: string) {
    await dbDeleteContact(userId)
    await loadContacts()
  }

  async function blockUser(userId: string, username: string) {
    await dbAddToBlacklist({
      user_id: userId,
      username,
      blocked_at: new Date().toISOString(),
    })
    await dbDeleteContact(userId)
    await loadContacts()
  }

  async function unblockUser(userId: string) {
    await dbRemoveFromBlacklist(userId)
    await loadContacts()
  }

  async function isUserBlacklisted(userId: string): Promise<boolean> {
    return isBlacklisted(userId)
  }

  return {
    contacts,
    blacklist,
    loadContacts,
    addContactByCode,
    acceptContact,
    removeContact,
    blockUser,
    unblockUser,
    isUserBlacklisted,
  }
})
```

- [ ] **Step 2: Commit**

```bash
git add web/src/stores/contacts.ts
git commit -m "feat(web): add contacts store with IndexedDB persistence"
```

---

### Task 7: Chat Store

**Files:**
- Create: `web/src/stores/chat.ts`

- [ ] **Step 1: Create chat store**

```typescript
import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import { v4 as uuidv4 } from 'crypto'
import type { Message } from '../db'
import {
  getMessagesByContact,
  saveMessage,
  updateMessageStatus,
  getMessage,
  deleteMessage,
} from '../db'
import { wsClient } from '../api/websocket'
import { useAuthStore } from './auth'
import { useContactsStore } from './contacts'

export const useChatStore = defineStore('chat', () => {
  const messages = ref<Map<string, Message[]>>(new Map())
  const pendingRequests = ref<Array<{ userId: string; username: string; message: any }>>([])

  async function loadMessages(contactId: string) {
    const msgs = await getMessagesByContact(contactId)
    messages.value.set(contactId, msgs)
  }

  function getMessages(contactId: string): Message[] {
    return messages.value.get(contactId) || []
  }

  // Get last message per contact for chat list
  function getLastMessage(contactId: string): Message | null {
    const msgs = messages.value.get(contactId)
    if (!msgs || msgs.length === 0) return null
    return msgs[msgs.length - 1]
  }

  async function sendTextMessage(toUserId: string, content: string) {
    const auth = useAuthStore()
    if (!auth.user) return

    const id = crypto.randomUUID()
    const now = new Date().toISOString()

    const message: Message = {
      id,
      contact_id: toUserId,
      direction: 'sent',
      content,
      content_type: 'text',
      status: 'sending',
      burn_after: 0,
      burned: false,
      revoked: false,
      created_at: now,
    }

    await saveMessage(message)
    await loadMessages(toUserId)

    wsClient.send({
      type: 'chat',
      id,
      timestamp: Math.floor(Date.now() / 1000),
      payload: {
        from_user_id: auth.user.user_id,
        from_username: auth.user.username,
        to_user_id: toUserId,
        content,
        content_type: 'text',
        burn_after: 0,
        is_first_contact: false,
      },
    })
  }

  async function sendStegoMessage(toUserId: string, content: string, stegoImage: string) {
    const auth = useAuthStore()
    if (!auth.user) return

    const id = crypto.randomUUID()
    const now = new Date().toISOString()

    const message: Message = {
      id,
      contact_id: toUserId,
      direction: 'sent',
      content,
      content_type: 'stego',
      stego_image: stegoImage,
      status: 'sending',
      burn_after: 0,
      burned: false,
      revoked: false,
      created_at: now,
    }

    await saveMessage(message)
    await loadMessages(toUserId)

    wsClient.send({
      type: 'chat',
      id,
      timestamp: Math.floor(Date.now() / 1000),
      payload: {
        from_user_id: auth.user.user_id,
        from_username: auth.user.username,
        to_user_id: toUserId,
        content,
        content_type: 'stego',
        stego_image: stegoImage,
        burn_after: 0,
        is_first_contact: false,
      },
    })
  }

  async function handleIncomingChat(msg: any) {
    const payload = msg.payload
    const contactsStore = useContactsStore()

    // Check blacklist
    if (await contactsStore.isUserBlacklisted(payload.from_user_id)) {
      return
    }

    // Check if this is a new contact (friend request)
    const existingContact = contactsStore.contacts.find(
      (c) => c.user_id === payload.from_user_id
    )
    if (!existingContact) {
      pendingRequests.value.push({
        userId: payload.from_user_id,
        username: payload.from_username,
        message: msg,
      })
      return
    }

    const message: Message = {
      id: msg.id,
      contact_id: payload.from_user_id,
      direction: 'received',
      content: payload.content,
      content_type: payload.content_type || 'text',
      stego_image: payload.stego_image,
      status: 'delivered',
      burn_after: payload.burn_after || 0,
      burned: false,
      revoked: false,
      created_at: new Date(msg.timestamp * 1000).toISOString(),
    }

    await saveMessage(message)
    await loadMessages(payload.from_user_id)

    // Send delivered receipt
    wsClient.send({
      type: 'delivered',
      id: crypto.randomUUID(),
      timestamp: Math.floor(Date.now() / 1000),
      payload: {
        to_user_id: payload.from_user_id,
        message_id: msg.id,
      },
    })
  }

  async function handleAck(msg: any) {
    await updateMessageStatus(msg.id, 'sent')
    // Reload messages for the affected contact
    const stored = await getMessage(msg.id)
    if (stored) {
      await loadMessages(stored.contact_id)
    }
  }

  async function handleDelivered(msg: any) {
    const messageId = msg.payload?.message_id
    if (messageId) {
      await updateMessageStatus(messageId, 'delivered')
      const stored = await getMessage(messageId)
      if (stored) {
        await loadMessages(stored.contact_id)
      }
    }
  }

  async function handleRead(msg: any) {
    const messageId = msg.payload?.message_id
    if (messageId) {
      await updateMessageStatus(messageId, 'read')
      const stored = await getMessage(messageId)
      if (stored) {
        await loadMessages(stored.contact_id)
      }
    }
  }

  async function handleRevoke(msg: any) {
    const messageId = msg.payload?.message_id
    if (messageId) {
      const stored = await getMessage(messageId)
      if (stored) {
        stored.revoked = true
        stored.content = ''
        await saveMessage(stored)
        await loadMessages(stored.contact_id)
      }
    }
  }

  function sendReadReceipt(toUserId: string, messageId: string) {
    wsClient.send({
      type: 'read',
      id: crypto.randomUUID(),
      timestamp: Math.floor(Date.now() / 1000),
      payload: {
        to_user_id: toUserId,
        message_id: messageId,
      },
    })
  }

  function setupWsHandlers() {
    wsClient.on('chat', handleIncomingChat)
    wsClient.on('ack', handleAck)
    wsClient.on('delivered', handleDelivered)
    wsClient.on('read', handleRead)
    wsClient.on('revoke', handleRevoke)
  }

  return {
    messages,
    pendingRequests,
    loadMessages,
    getMessages,
    getLastMessage,
    sendTextMessage,
    sendStegoMessage,
    sendReadReceipt,
    setupWsHandlers,
    pendingRequests,
  }
})
```

Note: Use `crypto.randomUUID()` (available in all modern browsers) instead of a UUID library.

- [ ] **Step 2: Commit**

```bash
git add web/src/stores/chat.ts
git commit -m "feat(web): add chat store with WebSocket message handling"
```

---

## Chunk 3: Views and Navigation

### Task 8: Auth Views

**Files:**
- Create: `web/src/views/auth/LoginView.vue`
- Create: `web/src/views/auth/RegisterView.vue`

- [ ] **Step 1: Create LoginView**

`web/src/views/auth/LoginView.vue`:
```vue
<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { useAuthStore } from '../../stores/auth'

const router = useRouter()
const auth = useAuthStore()

const username = ref('')
const password = ref('')
const error = ref('')
const loading = ref(false)

async function handleLogin() {
  error.value = ''
  loading.value = true
  try {
    await auth.login(username.value, password.value)
    router.push('/chats')
  } catch (e: any) {
    error.value = e.response?.data?.detail || 'Login failed'
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="auth-container">
    <h1>Login</h1>
    <form @submit.prevent="handleLogin">
      <div class="form-group">
        <label>Username</label>
        <input v-model="username" type="text" required minlength="3" maxlength="32" />
      </div>
      <div class="form-group">
        <label>Password</label>
        <input v-model="password" type="password" required minlength="6" />
      </div>
      <p v-if="error" class="error">{{ error }}</p>
      <button type="submit" :disabled="loading">
        {{ loading ? 'Logging in...' : 'Login' }}
      </button>
    </form>
    <p class="link">
      Don't have an account? <router-link to="/register">Register</router-link>
    </p>
  </div>
</template>

<style scoped>
.auth-container {
  max-width: 400px;
  margin: 80px auto;
  padding: 24px;
}
.form-group {
  margin-bottom: 16px;
}
.form-group label {
  display: block;
  margin-bottom: 4px;
  font-weight: 500;
}
.form-group input {
  width: 100%;
  padding: 8px 12px;
  border: 1px solid #ccc;
  border-radius: 6px;
  font-size: 14px;
  box-sizing: border-box;
}
button {
  width: 100%;
  padding: 10px;
  background: #4a90d9;
  color: white;
  border: none;
  border-radius: 6px;
  font-size: 16px;
  cursor: pointer;
}
button:disabled {
  opacity: 0.6;
}
.error {
  color: #e53e3e;
  font-size: 14px;
}
.link {
  text-align: center;
  margin-top: 16px;
}
</style>
```

- [ ] **Step 2: Create RegisterView**

`web/src/views/auth/RegisterView.vue`:
```vue
<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { useAuthStore } from '../../stores/auth'

const router = useRouter()
const auth = useAuthStore()

const username = ref('')
const password = ref('')
const confirmPassword = ref('')
const error = ref('')
const loading = ref(false)

async function handleRegister() {
  error.value = ''
  if (password.value !== confirmPassword.value) {
    error.value = 'Passwords do not match'
    return
  }
  loading.value = true
  try {
    await auth.register(username.value, password.value)
    router.push('/chats')
  } catch (e: any) {
    error.value = e.response?.data?.detail || 'Registration failed'
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="auth-container">
    <h1>Register</h1>
    <form @submit.prevent="handleRegister">
      <div class="form-group">
        <label>Username</label>
        <input v-model="username" type="text" required minlength="3" maxlength="32" />
      </div>
      <div class="form-group">
        <label>Password</label>
        <input v-model="password" type="password" required minlength="6" />
      </div>
      <div class="form-group">
        <label>Confirm Password</label>
        <input v-model="confirmPassword" type="password" required minlength="6" />
      </div>
      <p v-if="error" class="error">{{ error }}</p>
      <button type="submit" :disabled="loading">
        {{ loading ? 'Registering...' : 'Register' }}
      </button>
    </form>
    <p class="link">
      Already have an account? <router-link to="/login">Login</router-link>
    </p>
  </div>
</template>

<style scoped>
.auth-container {
  max-width: 400px;
  margin: 80px auto;
  padding: 24px;
}
.form-group {
  margin-bottom: 16px;
}
.form-group label {
  display: block;
  margin-bottom: 4px;
  font-weight: 500;
}
.form-group input {
  width: 100%;
  padding: 8px 12px;
  border: 1px solid #ccc;
  border-radius: 6px;
  font-size: 14px;
  box-sizing: border-box;
}
button {
  width: 100%;
  padding: 10px;
  background: #4a90d9;
  color: white;
  border: none;
  border-radius: 6px;
  font-size: 16px;
  cursor: pointer;
}
button:disabled {
  opacity: 0.6;
}
.error {
  color: #e53e3e;
  font-size: 14px;
}
.link {
  text-align: center;
  margin-top: 16px;
}
</style>
```

- [ ] **Step 3: Commit**

```bash
git add web/src/views/auth/
git commit -m "feat(web): add login and register views"
```

---

### Task 9: Chat Views

**Files:**
- Create: `web/src/views/chat/ChatListView.vue`
- Create: `web/src/views/chat/ChatView.vue`
- Create: `web/src/components/MessageBubble.vue`
- Create: `web/src/components/MessageInput.vue`

- [ ] **Step 1: Create MessageBubble component**

`web/src/components/MessageBubble.vue`:
```vue
<script setup lang="ts">
import type { Message } from '../db'

const props = defineProps<{
  message: Message
}>()

function formatTime(dateStr: string): string {
  const d = new Date(dateStr)
  return d.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })
}

const statusIcon: Record<string, string> = {
  sending: '...',
  sent: '✓',
  delivered: '✓✓',
  read: '✓✓',
  failed: '✗',
}
</script>

<template>
  <div class="bubble" :class="[message.direction, { revoked: message.revoked }]">
    <div v-if="message.revoked" class="revoked-text">Message revoked</div>
    <template v-else>
      <div v-if="message.content_type === 'stego' && message.stego_image" class="stego-image">
        <img :src="'data:image/png;base64,' + message.stego_image" alt="stego" />
      </div>
      <div class="content">{{ message.content }}</div>
    </template>
    <div class="meta">
      <span class="time">{{ formatTime(message.created_at) }}</span>
      <span v-if="message.direction === 'sent'" class="status" :class="message.status">
        {{ statusIcon[message.status] || '' }}
      </span>
    </div>
  </div>
</template>

<style scoped>
.bubble {
  max-width: 70%;
  padding: 8px 12px;
  border-radius: 12px;
  margin-bottom: 8px;
  word-break: break-word;
}
.sent {
  background: #4a90d9;
  color: white;
  margin-left: auto;
  border-bottom-right-radius: 4px;
}
.received {
  background: #e8e8e8;
  color: #333;
  margin-right: auto;
  border-bottom-left-radius: 4px;
}
.revoked {
  opacity: 0.6;
  font-style: italic;
}
.stego-image img {
  max-width: 200px;
  border-radius: 8px;
  margin-bottom: 4px;
}
.meta {
  display: flex;
  justify-content: flex-end;
  gap: 4px;
  font-size: 11px;
  opacity: 0.7;
  margin-top: 4px;
}
.status.read {
  color: #4fc3f7;
}
</style>
```

- [ ] **Step 2: Create MessageInput component**

`web/src/components/MessageInput.vue`:
```vue
<script setup lang="ts">
import { ref } from 'vue'

const emit = defineEmits<{
  send: [content: string, isStegoMode: boolean]
}>()

const content = ref('')
const stegoMode = ref(false)

function handleSend() {
  const text = content.value.trim()
  if (!text) return
  emit('send', text, stegoMode.value)
  content.value = ''
}
</script>

<template>
  <div class="input-bar">
    <button class="mode-toggle" :class="{ active: stegoMode }" @click="stegoMode = !stegoMode"
      :title="stegoMode ? 'Stego mode ON' : 'Normal mode'">
      {{ stegoMode ? '🔒' : '💬' }}
    </button>
    <input
      v-model="content"
      type="text"
      placeholder="Type a message..."
      @keyup.enter="handleSend"
    />
    <button class="send-btn" @click="handleSend" :disabled="!content.trim()">Send</button>
  </div>
</template>

<style scoped>
.input-bar {
  display: flex;
  gap: 8px;
  padding: 8px 12px;
  border-top: 1px solid #e0e0e0;
  background: white;
}
.input-bar input {
  flex: 1;
  padding: 8px 12px;
  border: 1px solid #ccc;
  border-radius: 20px;
  font-size: 14px;
}
.mode-toggle {
  background: none;
  border: 1px solid #ccc;
  border-radius: 50%;
  width: 36px;
  height: 36px;
  font-size: 16px;
  cursor: pointer;
}
.mode-toggle.active {
  border-color: #4a90d9;
  background: #e8f0fe;
}
.send-btn {
  padding: 8px 16px;
  background: #4a90d9;
  color: white;
  border: none;
  border-radius: 20px;
  cursor: pointer;
}
.send-btn:disabled {
  opacity: 0.5;
}
</style>
```

- [ ] **Step 3: Create ChatListView**

`web/src/views/chat/ChatListView.vue`:
```vue
<script setup lang="ts">
import { onMounted, computed } from 'vue'
import { useRouter } from 'vue-router'
import { useContactsStore } from '../../stores/contacts'
import { useChatStore } from '../../stores/chat'

const router = useRouter()
const contactsStore = useContactsStore()
const chatStore = useChatStore()

onMounted(async () => {
  await contactsStore.loadContacts()
  for (const contact of contactsStore.contacts) {
    await chatStore.loadMessages(contact.user_id)
  }
})

const sortedContacts = computed(() => {
  return [...contactsStore.contacts]
    .filter((c) => c.status === 'accepted')
    .sort((a, b) => {
      const lastA = chatStore.getLastMessage(a.user_id)
      const lastB = chatStore.getLastMessage(b.user_id)
      if (!lastA && !lastB) return 0
      if (!lastA) return 1
      if (!lastB) return -1
      return lastB.created_at.localeCompare(lastA.created_at)
    })
})

function formatTime(dateStr: string): string {
  const d = new Date(dateStr)
  const now = new Date()
  if (d.toDateString() === now.toDateString()) {
    return d.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })
  }
  return d.toLocaleDateString()
}

function openChat(userId: string) {
  router.push(`/chat/${userId}`)
}

const pendingCount = computed(() => chatStore.pendingRequests.length)
</script>

<template>
  <div class="chat-list">
    <div class="header">
      <h2>Messages</h2>
      <router-link v-if="pendingCount > 0" to="/contacts/requests" class="badge">
        {{ pendingCount }} new
      </router-link>
    </div>
    <div v-if="sortedContacts.length === 0" class="empty">
      <p>No conversations yet</p>
      <router-link to="/contacts/add">Add a contact</router-link>
    </div>
    <div
      v-for="contact in sortedContacts"
      :key="contact.user_id"
      class="chat-item"
      @click="openChat(contact.user_id)"
    >
      <div class="avatar">{{ (contact.nickname || contact.username)[0].toUpperCase() }}</div>
      <div class="info">
        <div class="name">{{ contact.nickname || contact.username }}</div>
        <div class="last-msg">
          {{ chatStore.getLastMessage(contact.user_id)?.content || 'No messages' }}
        </div>
      </div>
      <div v-if="chatStore.getLastMessage(contact.user_id)" class="time">
        {{ formatTime(chatStore.getLastMessage(contact.user_id)!.created_at) }}
      </div>
    </div>
  </div>
</template>

<style scoped>
.chat-list { padding: 0; }
.header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 16px;
}
.badge {
  background: #e53e3e;
  color: white;
  padding: 4px 10px;
  border-radius: 12px;
  font-size: 12px;
  text-decoration: none;
}
.empty {
  text-align: center;
  padding: 60px 20px;
  color: #888;
}
.chat-item {
  display: flex;
  align-items: center;
  padding: 12px 16px;
  border-bottom: 1px solid #f0f0f0;
  cursor: pointer;
}
.chat-item:hover { background: #f8f8f8; }
.avatar {
  width: 44px;
  height: 44px;
  border-radius: 50%;
  background: #4a90d9;
  color: white;
  display: flex;
  align-items: center;
  justify-content: center;
  font-weight: bold;
  font-size: 18px;
  flex-shrink: 0;
}
.info {
  flex: 1;
  margin-left: 12px;
  overflow: hidden;
}
.name { font-weight: 500; }
.last-msg {
  font-size: 13px;
  color: #888;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.time {
  font-size: 12px;
  color: #888;
  flex-shrink: 0;
}
</style>
```

- [ ] **Step 4: Create ChatView**

`web/src/views/chat/ChatView.vue`:
```vue
<script setup lang="ts">
import { ref, computed, onMounted, nextTick, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useContactsStore } from '../../stores/contacts'
import { useChatStore } from '../../stores/chat'
import { useAuthStore } from '../../stores/auth'
import { embed } from '../../api/stego'
import MessageBubble from '../../components/MessageBubble.vue'
import MessageInput from '../../components/MessageInput.vue'

const route = useRoute()
const router = useRouter()
const contactsStore = useContactsStore()
const chatStore = useChatStore()
const auth = useAuthStore()

const contactId = computed(() => route.params.id as string)
const contact = computed(() =>
  contactsStore.contacts.find((c) => c.user_id === contactId.value)
)
const messages = computed(() => chatStore.getMessages(contactId.value))
const messagesContainer = ref<HTMLElement | null>(null)
const stegoLoading = ref(false)

onMounted(async () => {
  await contactsStore.loadContacts()
  await chatStore.loadMessages(contactId.value)
  scrollToBottom()

  // Mark unread messages as read
  const unread = messages.value.filter(
    (m) => m.direction === 'received' && m.status === 'delivered'
  )
  for (const msg of unread) {
    chatStore.sendReadReceipt(contactId.value, msg.id)
  }
})

watch(messages, () => nextTick(scrollToBottom), { deep: true })

function scrollToBottom() {
  if (messagesContainer.value) {
    messagesContainer.value.scrollTop = messagesContainer.value.scrollHeight
  }
}

async function handleSend(content: string, isStegoMode: boolean) {
  if (isStegoMode) {
    stegoLoading.value = true
    try {
      const result = await embed(content, 'default-key', 'celebahq')
      await chatStore.sendStegoMessage(contactId.value, content, result.stego_image)
    } catch (e: any) {
      alert('Stego embed failed: ' + (e.response?.data?.detail || e.message))
    } finally {
      stegoLoading.value = false
    }
  } else {
    await chatStore.sendTextMessage(contactId.value, content)
  }
}
</script>

<template>
  <div class="chat-view">
    <div class="chat-header">
      <button class="back-btn" @click="router.push('/chats')">←</button>
      <span class="chat-name">{{ contact?.nickname || contact?.username || 'Chat' }}</span>
    </div>
    <div class="messages" ref="messagesContainer">
      <MessageBubble v-for="msg in messages" :key="msg.id" :message="msg" />
      <div v-if="stegoLoading" class="stego-loading">Generating stego image...</div>
    </div>
    <MessageInput @send="handleSend" />
  </div>
</template>

<style scoped>
.chat-view {
  display: flex;
  flex-direction: column;
  height: 100vh;
}
.chat-header {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 12px 16px;
  border-bottom: 1px solid #e0e0e0;
  background: white;
}
.back-btn {
  background: none;
  border: none;
  font-size: 20px;
  cursor: pointer;
  padding: 4px 8px;
}
.chat-name {
  font-weight: 600;
  font-size: 16px;
}
.messages {
  flex: 1;
  overflow-y: auto;
  padding: 16px;
  display: flex;
  flex-direction: column;
  background: #f5f5f5;
}
.stego-loading {
  text-align: center;
  color: #888;
  padding: 8px;
  font-style: italic;
}
</style>
```

- [ ] **Step 5: Commit**

```bash
git add web/src/views/chat/ web/src/components/MessageBubble.vue web/src/components/MessageInput.vue
git commit -m "feat(web): add chat views and message components"
```

---

### Task 10: Contact and Profile Views

**Files:**
- Create: `web/src/views/contact/ContactsView.vue`
- Create: `web/src/views/contact/AddContactView.vue`
- Create: `web/src/views/profile/ProfileView.vue`

- [ ] **Step 1: Create ContactsView**

`web/src/views/contact/ContactsView.vue`:
```vue
<script setup lang="ts">
import { onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { useContactsStore } from '../../stores/contacts'

const router = useRouter()
const contactsStore = useContactsStore()

onMounted(() => contactsStore.loadContacts())

function openChat(userId: string) {
  router.push(`/chat/${userId}`)
}
</script>

<template>
  <div class="contacts-view">
    <div class="header">
      <h2>Contacts</h2>
      <router-link to="/contacts/add" class="add-btn">+ Add</router-link>
    </div>
    <div v-if="contactsStore.contacts.length === 0" class="empty">
      <p>No contacts yet</p>
    </div>
    <div
      v-for="contact in contactsStore.contacts"
      :key="contact.user_id"
      class="contact-item"
      @click="openChat(contact.user_id)"
    >
      <div class="avatar">{{ (contact.nickname || contact.username)[0].toUpperCase() }}</div>
      <div class="info">
        <div class="name">{{ contact.nickname || contact.username }}</div>
        <div class="username">@{{ contact.username }}</div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 16px;
}
.add-btn {
  background: #4a90d9;
  color: white;
  padding: 6px 14px;
  border-radius: 6px;
  text-decoration: none;
  font-size: 14px;
}
.empty {
  text-align: center;
  padding: 60px 20px;
  color: #888;
}
.contact-item {
  display: flex;
  align-items: center;
  padding: 12px 16px;
  border-bottom: 1px solid #f0f0f0;
  cursor: pointer;
}
.contact-item:hover { background: #f8f8f8; }
.avatar {
  width: 40px;
  height: 40px;
  border-radius: 50%;
  background: #4a90d9;
  color: white;
  display: flex;
  align-items: center;
  justify-content: center;
  font-weight: bold;
}
.info { margin-left: 12px; }
.name { font-weight: 500; }
.username { font-size: 13px; color: #888; }
</style>
```

- [ ] **Step 2: Create AddContactView**

`web/src/views/contact/AddContactView.vue`:
```vue
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
      <button class="back-btn" @click="router.back()">←</button>
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
```

- [ ] **Step 3: Create ProfileView**

`web/src/views/profile/ProfileView.vue`:
```vue
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
```

- [ ] **Step 4: Commit**

```bash
git add web/src/views/contact/ web/src/views/profile/
git commit -m "feat(web): add contact and profile views"
```

---

## Chunk 4: Navigation and Integration

### Task 11: Bottom Navigation Component

**Files:**
- Create: `web/src/components/BottomNav.vue`

- [ ] **Step 1: Create BottomNav**

```vue
<script setup lang="ts">
import { computed } from 'vue'
import { useRoute } from 'vue-router'

const route = useRoute()

const tabs = [
  { path: '/chats', label: 'Messages', icon: '💬' },
  { path: '/contacts', label: 'Contacts', icon: '👥' },
  { path: '/embed', label: 'Stego', icon: '🔐' },
  { path: '/profile', label: 'Profile', icon: '👤' },
]

function isActive(path: string): boolean {
  return route.path.startsWith(path)
}
</script>

<template>
  <nav class="bottom-nav">
    <router-link
      v-for="tab in tabs"
      :key="tab.path"
      :to="tab.path"
      class="nav-item"
      :class="{ active: isActive(tab.path) }"
    >
      <span class="icon">{{ tab.icon }}</span>
      <span class="label">{{ tab.label }}</span>
    </router-link>
  </nav>
</template>

<style scoped>
.bottom-nav {
  display: flex;
  border-top: 1px solid #e0e0e0;
  background: white;
  position: fixed;
  bottom: 0;
  left: 0;
  right: 0;
  z-index: 100;
}
.nav-item {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  padding: 8px 0;
  text-decoration: none;
  color: #888;
  font-size: 11px;
}
.nav-item.active {
  color: #4a90d9;
}
.icon { font-size: 20px; }
.label { margin-top: 2px; }
</style>
```

- [ ] **Step 2: Commit**

```bash
git add web/src/components/BottomNav.vue
git commit -m "feat(web): add bottom navigation component"
```

---

### Task 12: Router and App Shell Update

**Files:**
- Modify: `web/src/router/index.ts`
- Modify: `web/src/App.vue`

- [ ] **Step 1: Update router with all routes and auth guard**

Replace `web/src/router/index.ts` with:
```typescript
import { createRouter, createWebHistory } from 'vue-router'

const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes: [
    { path: '/', redirect: '/chats' },
    {
      path: '/login',
      component: () => import('../views/auth/LoginView.vue'),
      meta: { guest: true },
    },
    {
      path: '/register',
      component: () => import('../views/auth/RegisterView.vue'),
      meta: { guest: true },
    },
    {
      path: '/chats',
      component: () => import('../views/chat/ChatListView.vue'),
    },
    {
      path: '/chat/:id',
      component: () => import('../views/chat/ChatView.vue'),
    },
    {
      path: '/contacts',
      component: () => import('../views/contact/ContactsView.vue'),
    },
    {
      path: '/contacts/add',
      component: () => import('../views/contact/AddContactView.vue'),
    },
    {
      path: '/profile',
      component: () => import('../views/profile/ProfileView.vue'),
    },
    {
      path: '/embed',
      component: () => import('../views/EmbedView.vue'),
    },
    {
      path: '/extract',
      component: () => import('../views/ExtractView.vue'),
    },
  ],
})

router.beforeEach((to) => {
  const token = localStorage.getItem('token')
  if (!token && !to.meta.guest) {
    return '/login'
  }
  if (token && to.meta.guest) {
    return '/chats'
  }
})

export default router
```

- [ ] **Step 2: Update App.vue**

Replace `web/src/App.vue` with:
```vue
<script setup lang="ts">
import { computed, onMounted, watch } from 'vue'
import { useRoute } from 'vue-router'
import { useAuthStore } from './stores/auth'
import { useChatStore } from './stores/chat'
import { useContactsStore } from './stores/contacts'
import { wsClient } from './api/websocket'
import BottomNav from './components/BottomNav.vue'

const route = useRoute()
const auth = useAuthStore()
const chatStore = useChatStore()
const contactsStore = useContactsStore()

const showNav = computed(() => {
  const guestRoutes = ['/login', '/register']
  const fullScreenRoutes = ['/chat/']
  if (guestRoutes.includes(route.path)) return false
  if (fullScreenRoutes.some((r) => route.path.startsWith(r))) return false
  return auth.isAuthenticated
})

// Connect WebSocket when authenticated
watch(
  () => auth.token,
  (token) => {
    if (token) {
      chatStore.setupWsHandlers()
      wsClient.connect(token)
      contactsStore.loadContacts()
    } else {
      wsClient.disconnect()
    }
  },
  { immediate: true }
)
</script>

<template>
  <div class="app" :class="{ 'has-nav': showNav }">
    <router-view />
    <BottomNav v-if="showNav" />
  </div>
</template>

<style>
* {
  margin: 0;
  padding: 0;
  box-sizing: border-box;
}
body {
  font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
  background: #fff;
}
.app.has-nav {
  padding-bottom: 60px;
}
</style>
```

- [ ] **Step 3: Verify app compiles**

Run: `cd web && npm run build`
Expected: Build succeeds (or only minor type warnings)

- [ ] **Step 4: Commit**

```bash
git add web/src/router/index.ts web/src/App.vue
git commit -m "feat(web): update router and app shell with auth guard and bottom nav"
```

---

## Summary

After completing all tasks, the web app will have:

| Feature | Route |
|---------|-------|
| Login | `/login` |
| Register | `/register` |
| Chat list | `/chats` |
| Chat conversation | `/chat/:id` |
| Contacts | `/contacts` |
| Add contact | `/contacts/add` |
| Profile & invite code | `/profile` |
| Stego embed (existing) | `/embed` |
| Stego extract (existing) | `/extract` |

Bottom navigation: Messages | Contacts | Stego | Profile
