import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import type { Message } from '../db'
import {
  getMessagesByContact,
  saveMessage,
  updateMessageStatus,
  getMessage,
} from '../db'
import { wsClient } from '../api/websocket'
import { getMyCode, getUserCode } from '../api/invite'
import { useAuthStore } from './auth'
import { useContactsStore } from './contacts'
import { useSettingsStore } from './settings'
import {
  hexToBytes,
  bytesToHex,
  xorBytes,
  deriveMkey,
  sealMessage,
  tryOpenMessage,
} from '../crypto/e2ee'

export const useChatStore = defineStore('chat', () => {
  const messages = ref<Map<string, Message[]>>(new Map())
  const pendingRequests = ref<Array<{ userId: string; username: string; messages: any[] }>>([])
  const myInviteCode = ref('')
  const peerInviteCode = ref('')
  const inviteCodesLoaded = ref(false)

  // Active chat session mkey cache. Not persisted. Invalidated whenever
  // activeContactId / self userKey / peer userKey change.
  const activeContactId = ref<string | null>(null)
  const currentMkey = ref<Uint8Array | null>(null)
  const hasE2EE = computed(() => currentMkey.value !== null)

  async function loadInviteCodes(peerUserId: string) {
    try {
      const [myCodeRes, peerCodeRes] = await Promise.all([
        getMyCode(),
        getUserCode(peerUserId)
      ])
      myInviteCode.value = myCodeRes.code
      peerInviteCode.value = peerCodeRes.code
      inviteCodesLoaded.value = true
    } catch (e) {
      console.error('Failed to load invite codes:', e)
      inviteCodesLoaded.value = false
    }
  }

  /**
   * 隐写模型 seed 密钥 = 双方邀请码的按字节 XOR,然后转 hex 字符串。
   *
   * XOR 天然对称,A↔B 双方算出的 seed 完全相同,因此不再需要区分方向;
   * 调用方可以放心地在发送或接收(提取)路径上直接调用。邀请码长度固定
   * (INVITE_CODE_LENGTH=8),不涉及 padding。
   */
  function getStegoKey(): string {
    const a = new TextEncoder().encode(myInviteCode.value)
    const b = new TextEncoder().encode(peerInviteCode.value)
    return bytesToHex(xorBytes(a, b))
  }

  /**
   * Derive (or invalidate) the AES-GCM mkey for the current active chat.
   * Lazy-recomputed whenever self userKey or peer userKey changes. Result
   * lives only in memory (never in IndexedDB).
   */
  async function refreshMkey() {
    const contactId = activeContactId.value
    if (!contactId) {
      currentMkey.value = null
      return
    }
    const settings = useSettingsStore()
    const contactsStore = useContactsStore()
    const peerHex = contactsStore.getContactById(contactId)?.peerUserKeyHex
    if (!settings.userKeyHex || !peerHex) {
      currentMkey.value = null
      return
    }
    try {
      const selfKey = hexToBytes(settings.userKeyHex)
      const peerKey = hexToBytes(peerHex)
      currentMkey.value = await deriveMkey(selfKey, peerKey)
    } catch (e) {
      console.error('deriveMkey failed:', e)
      currentMkey.value = null
    }
  }

  async function setActiveContact(contactId: string | null) {
    activeContactId.value = contactId
    await refreshMkey()
  }

  async function loadMessages(contactId: string) {
    const msgs = await getMessagesByContact(contactId)
    messages.value.set(contactId, msgs)
  }

  function getMessages(contactId: string): Message[] {
    return messages.value.get(contactId) || []
  }

  function getLastMessage(contactId: string): Message | null {
    const msgs = messages.value.get(contactId)
    if (!msgs || msgs.length === 0) return null
    return msgs[msgs.length - 1]
  }

  async function sendTextMessage(toUserId: string, content: string) {
    const auth = useAuthStore()
    if (!auth.user) return

    // Gate: refuse to send when E2EE is not fully configured. Callers
    // should render a banner that matches this state so the user is not
    // surprised; throwing here is a defensive fallback.
    if (!currentMkey.value || activeContactId.value !== toUserId) {
      throw new Error('E2EE_NOT_CONFIGURED')
    }

    const mkey = currentMkey.value
    let sealed: string
    try {
      sealed = await sealMessage(mkey, content)
    } catch (e) {
      console.error('sealMessage failed:', e)
      throw new Error('E2EE_SEAL_FAILED')
    }

    const id = crypto.randomUUID()
    const now = new Date().toISOString()

    // Local DB: store the PLAINTEXT so user's own history reads correctly.
    // Sealed blob goes only on the wire.
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
        content: sealed,
        content_type: 'text',
        burn_after: 0,
        is_first_contact: false,
      },
    })
  }

  /**
   * Seal plaintext with the current active chat's mkey. Returns sealed
   * base64url ASCII string suitable for feeding to `/stego/embed` as the
   * `message` field. Throws when E2EE is not configured for the active
   * chat so the caller can surface a banner.
   */
  async function sealStegoPayload(plaintext: string): Promise<string> {
    if (!currentMkey.value) throw new Error('E2EE_NOT_CONFIGURED')
    return sealMessage(currentMkey.value, plaintext)
  }

  /**
   * Open a sealed payload extracted from a stego image. Uses the given
   * contactId to derive an mkey (may differ from the active chat — when
   * viewing history from another contact). Returns null if either side's
   * key is missing, or the blob is not a valid sealed message.
   *
   * Never logs sealed content or derived key material.
   */
  async function openStegoPayload(contactId: string, sealed: string): Promise<string | null> {
    const settings = useSettingsStore()
    const contactsStore = useContactsStore()
    const peerHex = contactsStore.getContactById(contactId)?.peerUserKeyHex
    if (!settings.userKeyHex || !peerHex) return null
    try {
      const selfKey = hexToBytes(settings.userKeyHex)
      const peerKey = hexToBytes(peerHex)
      const mkey = await deriveMkey(selfKey, peerKey)
      return await tryOpenMessage(mkey, sealed)
    } catch {
      return null
    }
  }

  async function sendStegoMessage(toUserId: string, stegoImage: string) {
    const auth = useAuthStore()
    if (!auth.user) return

    const id = crypto.randomUUID()
    const now = new Date().toISOString()

    const message: Message = {
      id,
      contact_id: toUserId,
      direction: 'sent',
      content: '',
      content_type: 'stego',
      stego_image: stegoImage,
      status: 'sending',
      burn_after: 0,
      burned: false,
      revoked: false,
      created_at: now,
    }

    console.log('[STEGO-DEBUG] sendStegoMessage: saving message', { id, direction: 'sent', contact_id: toUserId, stego_image_len: stegoImage.length })
    await saveMessage(message)
    const savedMsg = await getMessage(id)
    console.log('[STEGO-DEBUG] sendStegoMessage: after save, DB has', { id, direction: savedMsg?.direction, has_stego: !!savedMsg?.stego_image })
    await loadMessages(toUserId)

    const wsPayloadSize = JSON.stringify({
      type: 'chat', id, timestamp: Math.floor(Date.now() / 1000),
      payload: { from_user_id: auth.user.user_id, to_user_id: toUserId, content_type: 'stego', stego_image: stegoImage }
    }).length
    console.log('[STEGO-DEBUG] sendStegoMessage: WS payload size =', wsPayloadSize, 'bytes')

    wsClient.send({
      type: 'chat',
      id,
      timestamp: Math.floor(Date.now() / 1000),
      payload: {
        from_user_id: auth.user.user_id,
        from_username: auth.user.username,
        to_user_id: toUserId,
        content: '',
        content_type: 'stego',
        stego_image: stegoImage,
        burn_after: 0,
        is_first_contact: false,
      },
    })
    console.log('[STEGO-DEBUG] sendStegoMessage: WS send called, id =', id)
  }

  async function handleIncomingChat(msg: any) {
    const payload = msg.payload
    console.log('[STEGO-DEBUG] handleIncomingChat: received', { id: msg.id, type: msg.type, from: payload.from_user_id, to: payload.to_user_id, content_type: payload.content_type, has_stego: !!payload.stego_image })
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
      const existing = pendingRequests.value.find(r => r.userId === payload.from_user_id)
      if (existing) {
        pendingRequests.value = pendingRequests.value.map(r =>
          r.userId === payload.from_user_id
            ? { ...r, messages: [...r.messages, msg] }
            : r
        )
      } else {
        pendingRequests.value = [...pendingRequests.value, {
          userId: payload.from_user_id,
          username: payload.from_username,
          messages: [msg],
        }]
      }
      return
    }

    // For text messages, attempt decryption when E2EE is configured for that
    // contact. Fall back to raw content (legacy plaintext) on failure — this
    // keeps backward compat with history sent before key configuration.
    const contentType: string = payload.content_type || 'text'
    let storedContent: string = payload.content ?? ''
    if (contentType === 'text') {
      const settings = useSettingsStore()
      const peerHex = existingContact.peerUserKeyHex
      if (settings.userKeyHex && peerHex && typeof payload.content === 'string') {
        try {
          const selfKey = hexToBytes(settings.userKeyHex)
          const peerKey = hexToBytes(peerHex)
          const mkey = await deriveMkey(selfKey, peerKey)
          const opened = await tryOpenMessage(mkey, payload.content)
          if (opened !== null) {
            storedContent = opened
          }
          // null → treat as legacy plaintext; keep raw content as-is
        } catch (e) {
          console.warn('decrypt incoming failed:', e)
        }
      }
    }

    const message: Message = {
      id: msg.id,
      contact_id: payload.from_user_id,
      direction: 'received',
      content: storedContent,
      content_type: contentType as 'text' | 'stego',
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
    console.log('[STEGO-DEBUG] handleAck: received ack for', msg.id)
    await updateMessageStatus(msg.id, 'sent')
    const stored = await getMessage(msg.id)
    console.log('[STEGO-DEBUG] handleAck: after update, DB has', { id: msg.id, direction: stored?.direction, content_type: stored?.content_type, has_stego: !!stored?.stego_image })
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

  let handlersRegistered = false

  function setupWsHandlers() {
    if (handlersRegistered) return
    handlersRegistered = true
    wsClient.on('chat', handleIncomingChat)
    wsClient.on('ack', handleAck)
    wsClient.on('delivered', handleDelivered)
    wsClient.on('read', handleRead)
    wsClient.on('revoke', handleRevoke)
    wsClient.on('kicked', handleKicked)
    wsClient.on('auth_failed', handleAuthFailed)
    wsClient.on('_auth_failed', handleAuthFailed)
  }

  function handleKicked() {
    wsClient.disconnect()
    // Emit a custom event for App.vue to handle
    window.dispatchEvent(new CustomEvent('ws-kicked'))
  }

  function handleAuthFailed() {
    wsClient.disconnect()
    window.dispatchEvent(new CustomEvent('ws-auth-failed'))
  }

  return {
    messages,
    pendingRequests,
    myInviteCode,
    peerInviteCode,
    inviteCodesLoaded,
    activeContactId,
    currentMkey,
    hasE2EE,
    loadMessages,
    getMessages,
    getLastMessage,
    loadInviteCodes,
    getStegoKey,
    setActiveContact,
    refreshMkey,
    sendTextMessage,
    sendStegoMessage,
    sealStegoPayload,
    openStegoPayload,
    sendReadReceipt,
    setupWsHandlers,
  }
})
