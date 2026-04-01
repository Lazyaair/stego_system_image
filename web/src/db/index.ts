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
