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
  }
})
