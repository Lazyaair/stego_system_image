import { openDB, type IDBPDatabase } from 'idb'

const DB_NAME = 'stego-app'
const DB_VERSION = 2

export interface Contact {
  user_id: string
  username: string
  nickname: string
  status: 'pending' | 'accepted'
  added_at: string
  /** 对方提供的加密助记词(本地明文保存,仅供派生 peer user_key 使用) */
  peerPhrase?: string
  /** 由 peerPhrase 派生的 peer user_key,hex 编码(256 bit) */
  peerUserKeyHex?: string
  /** peer user_key 的 SHA-256 前 8 字节指纹,hex 编码 */
  peerFingerprintHex?: string
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

export interface SelfE2EERecord {
  key: 'e2ee_self'
  phrase: string
  userKeyHex: string
  fingerprintHex: string
}

let dbPromise: Promise<IDBPDatabase> | null = null

function getDB() {
  if (!dbPromise) {
    dbPromise = openDB(DB_NAME, DB_VERSION, {
      upgrade(db, _oldVersion, _newVersion, _transaction) {
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
        // v2: 仅新增 Contact 上的可选字段(peerPhrase / peerUserKeyHex / peerFingerprintHex)
        // 和 settings 里的 e2ee_self 记录,不需要修改 store 结构。
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

/** 写入/更新联系人的 peer E2EE 字段,保留其它字段不变。 */
export async function saveContactPeerE2EE(
  userId: string,
  phrase: string,
  userKeyHex: string,
  fingerprintHex: string,
): Promise<void> {
  const db = await getDB()
  const existing = await db.get('contacts', userId)
  if (!existing) throw new Error(`contact not found: ${userId}`)
  existing.peerPhrase = phrase
  existing.peerUserKeyHex = userKeyHex
  existing.peerFingerprintHex = fingerprintHex
  await db.put('contacts', existing)
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

// Self E2EE (settings)
const SELF_E2EE_KEY = 'e2ee_self'

export async function saveSelfE2EE(
  phrase: string,
  userKeyHex: string,
  fingerprintHex: string,
): Promise<void> {
  const db = await getDB()
  const rec: SelfE2EERecord = {
    key: SELF_E2EE_KEY,
    phrase,
    userKeyHex,
    fingerprintHex,
  }
  await db.put('settings', rec)
}

export async function loadSelfE2EE(): Promise<{
  phrase: string
  userKeyHex: string
  fingerprintHex: string
} | null> {
  const db = await getDB()
  const rec = (await db.get('settings', SELF_E2EE_KEY)) as SelfE2EERecord | undefined
  if (!rec) return null
  return {
    phrase: rec.phrase,
    userKeyHex: rec.userKeyHex,
    fingerprintHex: rec.fingerprintHex,
  }
}

export async function clearSelfE2EE(): Promise<void> {
  const db = await getDB()
  await db.delete('settings', SELF_E2EE_KEY)
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
