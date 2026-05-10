import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import { bytesToHex, deriveUserKey, fingerprint } from '../crypto/e2ee'
import {
  saveSelfE2EE,
  loadSelfE2EE,
  clearSelfE2EE,
} from '../db'

/**
 * 本地持有自身 E2EE 状态的 store。phrase 仅保存在浏览器 IndexedDB,
 * 不会上传到 server。userKey/指纹是派生产物,这里存 hex 以便序列化。
 */
export const useSettingsStore = defineStore('settings', () => {
  const phrase = ref('')
  const userKeyHex = ref('')
  const fingerprintHex = ref('')

  const hasE2EE = computed(() => phrase.value !== '' && userKeyHex.value !== '')

  async function setPhrase(newPhrase: string) {
    const trimmed = newPhrase.trim()
    if (!trimmed) throw new Error('phrase: empty')
    const uk = await deriveUserKey(trimmed)
    const fp = await fingerprint(uk)
    const ukHex = bytesToHex(uk)
    const fpHex = bytesToHex(fp)
    phrase.value = trimmed
    userKeyHex.value = ukHex
    fingerprintHex.value = fpHex
    await saveSelfE2EE(trimmed, ukHex, fpHex)
  }

  async function clear() {
    phrase.value = ''
    userKeyHex.value = ''
    fingerprintHex.value = ''
    await clearSelfE2EE()
  }

  /** 从 IndexedDB 恢复状态,应在 App 启动时调用一次。 */
  async function hydrate() {
    const rec = await loadSelfE2EE()
    if (rec) {
      phrase.value = rec.phrase
      userKeyHex.value = rec.userKeyHex
      fingerprintHex.value = rec.fingerprintHex
    }
  }

  return {
    phrase,
    userKeyHex,
    fingerprintHex,
    hasE2EE,
    setPhrase,
    clear,
    hydrate,
  }
})
