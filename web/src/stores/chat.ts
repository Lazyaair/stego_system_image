import { defineStore } from 'pinia'
import { ref } from 'vue'
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

export const useChatStore = defineStore('chat', () => {
  const messages = ref<Map<string, Message[]>>(new Map())
  const pendingRequests = ref<Array<{ userId: string; username: string; messages: any[] }>>([])
  const myInviteCode = ref('')
  const peerInviteCode = ref('')
  const inviteCodesLoaded = ref(false)

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

  function getStegoKey(isOutgoing: boolean): string {
    if (isOutgoing) {
      return myInviteCode.value + peerInviteCode.value
    }
    return peerInviteCode.value + myInviteCode.value
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
  }

  function handleKicked() {
    wsClient.disconnect()
    // Emit a custom event for App.vue to handle
    window.dispatchEvent(new CustomEvent('ws-kicked'))
  }

  return {
    messages,
    pendingRequests,
    myInviteCode,
    peerInviteCode,
    inviteCodesLoaded,
    loadMessages,
    getMessages,
    getLastMessage,
    loadInviteCodes,
    getStegoKey,
    sendTextMessage,
    sendStegoMessage,
    sendReadReceipt,
    setupWsHandlers,
  }
})
