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
import { wsClient } from '../api/websocket'
import { useAuthStore } from './auth'

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

    // Notify the other user via WebSocket
    const auth = useAuthStore()
    if (auth.user) {
      wsClient.send({
        type: 'chat',
        id: crypto.randomUUID(),
        timestamp: Math.floor(Date.now() / 1000),
        payload: {
          from_user_id: auth.user.user_id,
          from_username: auth.user.username,
          to_user_id: info.user_id,
          content: `${auth.user.username} wants to be your friend`,
          content_type: 'text',
          burn_after: 0,
          is_first_contact: true,
        },
      })
    }

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
