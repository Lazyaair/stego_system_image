# Stego Chat Enhancement Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Complete steganographic image messaging in chat — proper key derivation from invite codes, send/display/extract on both Web and Android, capacity checking, and bug fixes.

**Architecture:** Client-side embed via existing stego API. Key = sender_invite_code + receiver_invite_code (direct concatenation). Invite codes preloaded on chat entry. Extract triggered by long-press (Android) / right-click (Web) context menu.

**Tech Stack:** Python/FastAPI (server), Vue 3/TypeScript/Pinia (web), Kotlin/Jetpack Compose/Retrofit (android)

**Spec:** `docs/superpowers/specs/2026-04-06-stego-chat-enhancement-design.md`

---

## Chunk 1: Server — New Endpoints

### Task 1: Add max-capacity endpoint to stego API

**Files:**
- Modify: `server/api/v1/stego.py:17` (insert before existing `/capacity` endpoint)

- [ ] **Step 1: Add the max-capacity GET endpoint**

Add after line 14 (after `/models` endpoint), before the existing `/capacity` POST:

```python
@router.get("/max-capacity")
async def get_max_capacity(
    key: str,
    model: str = DEFAULT_MODEL
):
    """获取最大消息容量（无需提供消息内容）"""
    key_check = PulsarService.validate_key(key)
    if not key_check["valid"]:
        raise HTTPException(status_code=400, detail=key_check["error"])

    try:
        key_bytes = key.encode("utf-8")
        max_capacity = PulsarService.get_capacity(model, key_bytes)
        return JSONResponse(content={"max_capacity": max_capacity})
    except ValueError as e:
        raise HTTPException(status_code=400, detail=str(e))
    except Exception as e:
        raise HTTPException(status_code=500, detail=f"容量查询失败: {str(e)}")
```

- [ ] **Step 2: Verify endpoint works**

Run: `cd server && python -c "from api.v1.stego import router; print('OK')"`
Expected: OK (no import errors)

- [ ] **Step 3: Commit**

```bash
git add server/api/v1/stego.py
git commit -m "feat(server): add max-capacity endpoint for stego key capacity lookup"
```

---

### Task 2: Add user-code endpoint to invite API

**Files:**
- Modify: `server/api/v1/invite.py:57` (insert before existing `/{code}` route)

- [ ] **Step 1: Add user-code endpoint**

Insert before line 58 (`@router.get("/{code}")`), so it takes priority over the catch-all route:

```python
@router.get("/user-code/{user_id}")
async def get_user_code(user_id: str, user=Depends(get_current_user)):
    """根据 user_id 获取该用户的 invite code（需要认证）"""
    db = await get_db()
    rows = await db.execute_fetchall(
        "SELECT code FROM invite_codes WHERE user_id = ?", (user_id,)
    )
    if rows:
        code = rows[0][0]
    else:
        # Auto-generate if user has no code
        code = generate_code()
        await db.execute(
            "INSERT INTO invite_codes (code, user_id) VALUES (?, ?)",
            (code, user_id),
        )
        await db.commit()

    # Get username
    user_rows = await db.execute_fetchall(
        "SELECT username FROM users WHERE id = ?", (user_id,)
    )
    if not user_rows:
        raise HTTPException(status_code=404, detail="User not found")

    return {"code": code, "user_id": user_id, "username": user_rows[0][0]}
```

- [ ] **Step 2: Verify no import errors**

Run: `cd server && python -c "from api.v1.invite import router; print('OK')"`
Expected: OK

- [ ] **Step 3: Commit**

```bash
git add server/api/v1/invite.py
git commit -m "feat(server): add user-code endpoint to get invite code by user_id"
```

---

## Chunk 2: Web — API Layer & Store

### Task 3: Add Web API methods for new endpoints

**Files:**
- Modify: `web/src/api/stego.ts` (add `getMaxCapacity` method)
- Modify: `web/src/api/invite.ts` (add `getUserCode` method)

- [ ] **Step 1: Add getMaxCapacity to stego.ts**

Add new response type and method to `stegoApi` object:

```typescript
// Add to interfaces section (after ExtractResponse)
export interface MaxCapacityResponse {
  max_capacity: number
}

// Add to stegoApi object (after validateKey method)
async getMaxCapacity(key: string, model: string = 'celebahq'): Promise<MaxCapacityResponse> {
  const { data } = await apiClient.get('/api/v1/stego/max-capacity', {
    params: { key, model }
  })
  return data
},
```

- [ ] **Step 2: Add UserCodeResponse type and getUserCode to invite.ts**

Add new type and method after `lookupCode`:

```typescript
export interface UserCodeResponse {
  code: string
  user_id: string
  username: string
}

export async function getUserCode(userId: string): Promise<UserCodeResponse> {
  const { data } = await api.get(`/api/v1/invite/user-code/${userId}`)
  return data
}
```

- [ ] **Step 3: Commit**

```bash
git add web/src/api/stego.ts web/src/api/invite.ts
git commit -m "feat(web): add API methods for max-capacity and user-code endpoints"
```

---

### Task 4: Add invite code state and stego key helper to chat store

**Files:**
- Modify: `web/src/stores/chat.ts`

- [ ] **Step 1: Add imports and invite code state**

Add to imports (line 4 area):

```typescript
import { getMyCode, getUserCode, type UserCodeResponse } from '../api/invite'
```

Add inside the store function (after `pendingRequests` ref, ~line 16):

```typescript
const myInviteCode = ref('')
const peerInviteCode = ref('')
const inviteCodesLoaded = ref(false)
```

- [ ] **Step 2: Add loadInviteCodes and getStegoKey methods**

Add after the invite code refs:

```typescript
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
```

- [ ] **Step 3: Modify sendStegoMessage to hardcode empty content**

Replace the existing `sendStegoMessage` function (lines 72-111). The key change: remove `content` parameter, hardcode `content: ''`:

```typescript
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
      content: '',
      content_type: 'stego',
      stego_image: stegoImage,
      burn_after: 0,
      is_first_contact: false,
    },
  })
}
```

- [ ] **Step 4: Export new members**

Add to the store's return object (around line 247):

```typescript
myInviteCode,
peerInviteCode,
inviteCodesLoaded,
loadInviteCodes,
getStegoKey,
```

- [ ] **Step 5: Commit**

```bash
git add web/src/stores/chat.ts
git commit -m "feat(web): add invite code state, stego key helper, and fix sendStegoMessage"
```

---

## Chunk 3: Web — UI Components

### Task 5: Enhance MessageInput with capacity indicator

**Files:**
- Modify: `web/src/components/MessageInput.vue`

- [ ] **Step 1: Add capacity props and byte length computation**

Replace the entire `<script setup>` section:

```typescript
import { ref, computed, watch } from 'vue'

const props = defineProps<{
  stegoMaxCapacity: number
  stegoModeDisabled: boolean
}>()

const emit = defineEmits<{
  send: [content: string, isStegoMode: boolean]
}>()

const content = ref('')
const stegoMode = ref(false)

const byteLength = computed(() => new TextEncoder().encode(content.value).length)
const overCapacity = computed(() => stegoMode.value && byteLength.value > props.stegoMaxCapacity)
const canSend = computed(() => {
  const trimmed = content.value.trim()
  if (!trimmed) return false
  if (stegoMode.value && overCapacity.value) return false
  return true
})

function handleSend() {
  if (!canSend.value) return
  emit('send', content.value.trim(), stegoMode.value)
  content.value = ''
}

function toggleStegoMode() {
  if (props.stegoModeDisabled) return
  stegoMode.value = !stegoMode.value
}
```

- [ ] **Step 2: Update template with capacity indicator**

Replace the template section:

```html
<template>
  <div class="message-input">
    <div v-if="stegoMode" class="capacity-indicator" :class="{ over: overCapacity }">
      秘密消息: {{ byteLength }}/{{ stegoMaxCapacity }} 字节
    </div>
    <div class="input-row">
      <button
        class="mode-toggle"
        :class="{ active: stegoMode, disabled: stegoModeDisabled }"
        @click="toggleStegoMode"
        :title="stegoModeDisabled ? '加载中...' : (stegoMode ? '切换到普通模式' : '切换到隐写模式')"
      >
        {{ stegoMode ? '🔒' : '💬' }}
      </button>
      <input
        v-model="content"
        :placeholder="stegoMode ? '输入秘密消息...' : '输入消息...'"
        @keyup.enter="handleSend"
      />
      <button class="send-btn" :disabled="!canSend" @click="handleSend">发送</button>
    </div>
  </div>
</template>
```

- [ ] **Step 3: Add capacity indicator styles**

Add to `<style scoped>`:

```css
.capacity-indicator {
  font-size: 12px;
  color: #666;
  padding: 2px 8px 2px 48px;
}
.capacity-indicator.over {
  color: #e53935;
  font-weight: bold;
}
.mode-toggle.disabled {
  opacity: 0.4;
  cursor: not-allowed;
}
```

- [ ] **Step 4: Commit**

```bash
git add web/src/components/MessageInput.vue
git commit -m "feat(web): add capacity indicator and stego mode guard to MessageInput"
```

---

### Task 6: Fix MessageBubble — stego image display and context menu

**Files:**
- Modify: `web/src/components/MessageBubble.vue`

- [ ] **Step 1: Add script logic for context menu and extraction**

Replace the entire `<script setup>`:

```typescript
import { ref } from 'vue'
import type { Message } from '../db'
import { stegoApi } from '../api/stego'

const props = defineProps<{
  message: Message
  stegoKey: string
}>()

const showMenu = ref(false)
const menuX = ref(0)
const menuY = ref(0)
const extractedText = ref<string | null>(null)
const extracting = ref(false)

function formatTime(iso: string) {
  return new Date(iso).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })
}

const statusIcon: Record<string, string> = {
  sending: '⏳', sent: '✓', delivered: '✓✓', read: '👁', failed: '❌'
}

function getStegoImageSrc(base64: string): string {
  if (base64.startsWith('data:')) return base64
  return 'data:image/png;base64,' + base64
}

function onContextMenu(e: MouseEvent) {
  e.preventDefault()
  menuX.value = e.clientX
  menuY.value = e.clientY
  showMenu.value = true
}

function closeMenu() {
  showMenu.value = false
}

async function extractSecret() {
  closeMenu()
  if (!props.message.stego_image || extracting.value) return
  extracting.value = true
  try {
    const base64 = props.message.stego_image.startsWith('data:')
      ? props.message.stego_image.split(',')[1]
      : props.message.stego_image
    const byteString = atob(base64)
    const bytes = new Uint8Array(byteString.length)
    for (let i = 0; i < byteString.length; i++) bytes[i] = byteString.charCodeAt(i)
    const blob = new Blob([bytes], { type: 'image/png' })
    const file = new File([blob], 'stego.png', { type: 'image/png' })
    const res = await stegoApi.extract(file, props.stegoKey, 'celebahq')
    extractedText.value = res.secret_message || '(空)'
  } catch (e: any) {
    extractedText.value = '提取失败: ' + (e.response?.data?.detail || e.message)
  } finally {
    extracting.value = false
  }
}

function saveImage() {
  closeMenu()
  if (!props.message.stego_image) return
  const src = getStegoImageSrc(props.message.stego_image)
  const a = document.createElement('a')
  a.href = src
  a.download = `stego-${props.message.id}.png`
  a.click()
}
```

- [ ] **Step 2: Update template**

Replace the template:

```html
<template>
  <div class="bubble" :class="message.direction" @click="closeMenu">
    <template v-if="message.revoked">
      <div class="revoked">消息已撤回</div>
    </template>
    <template v-else>
      <div v-if="message.content_type === 'stego' && message.stego_image" class="stego-image">
        <img
          :src="getStegoImageSrc(message.stego_image)"
          alt="隐写图像"
          @contextmenu="onContextMenu"
        />
        <div v-if="extracting" class="extracted-text">提取中...</div>
        <div v-else-if="extractedText !== null" class="extracted-text">
          {{ extractedText }}
        </div>
      </div>
      <div v-if="message.content" class="content">{{ message.content }}</div>
      <div class="meta">
        <span class="time">{{ formatTime(message.created_at) }}</span>
        <span v-if="message.direction === 'sent'" class="status">
          {{ statusIcon[message.status] || '' }}
        </span>
      </div>
    </template>

    <!-- Context menu -->
    <Teleport to="body">
      <div v-if="showMenu" class="context-menu" :style="{ left: menuX + 'px', top: menuY + 'px' }" @click.stop>
        <div class="menu-item" @click="extractSecret">提取秘密消息</div>
        <div class="menu-item" @click="saveImage">保存图像</div>
      </div>
      <div v-if="showMenu" class="menu-overlay" @click="closeMenu"></div>
    </Teleport>
  </div>
</template>
```

- [ ] **Step 3: Add styles for context menu and extracted text**

Add to `<style scoped>`:

```css
.stego-image img {
  max-width: 240px;
  border-radius: 8px;
  cursor: context-menu;
}
.extracted-text {
  margin-top: 4px;
  padding: 6px 10px;
  background: rgba(0, 0, 0, 0.05);
  border-radius: 6px;
  font-size: 13px;
  color: #333;
}
.context-menu {
  position: fixed;
  background: #fff;
  border: 1px solid #ddd;
  border-radius: 8px;
  box-shadow: 0 2px 12px rgba(0,0,0,0.15);
  z-index: 1000;
  min-width: 140px;
  overflow: hidden;
}
.menu-item {
  padding: 10px 16px;
  cursor: pointer;
  font-size: 14px;
}
.menu-item:hover {
  background: #f5f5f5;
}
.menu-overlay {
  position: fixed;
  inset: 0;
  z-index: 999;
}
```

- [ ] **Step 4: Commit**

```bash
git add web/src/components/MessageBubble.vue
git commit -m "feat(web): add stego image display, context menu extract, and save"
```

---

### Task 7: Update ChatView to wire everything together

**Files:**
- Modify: `web/src/views/chat/ChatView.vue`

- [ ] **Step 1: Update script section**

Replace the `<script setup>` content:

```typescript
import { ref, computed, onMounted, nextTick, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useContactsStore } from '../../stores/contacts'
import { useChatStore } from '../../stores/chat'
import { stegoApi } from '../../api/stego'
import MessageBubble from '../../components/MessageBubble.vue'
import MessageInput from '../../components/MessageInput.vue'

const route = useRoute()
const router = useRouter()
const contactsStore = useContactsStore()
const chatStore = useChatStore()

const contactId = computed(() => route.params.id as string)
const contact = computed(() => contactsStore.contacts.find((c) => c.user_id === contactId.value))
const messages = computed(() => chatStore.getMessages(contactId.value))
const messagesContainer = ref<HTMLElement>()
const stegoLoading = ref(false)
const stegoMaxCapacity = ref(0)

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

  // Preload invite codes
  await chatStore.loadInviteCodes(contactId.value)
  // Fetch max capacity once codes are loaded
  if (chatStore.inviteCodesLoaded) {
    try {
      const key = chatStore.getStegoKey(true)
      const res = await stegoApi.getMaxCapacity(key)
      stegoMaxCapacity.value = res.max_capacity
    } catch (e) {
      console.error('Failed to fetch max capacity:', e)
    }
  }
})

watch(messages, () => nextTick(scrollToBottom), { deep: true })

function scrollToBottom() {
  if (messagesContainer.value) {
    messagesContainer.value.scrollTop = messagesContainer.value.scrollHeight
  }
}

function getStegoKeyForMessage(msg: any): string {
  const isOutgoing = msg.direction === 'sent'
  return chatStore.getStegoKey(isOutgoing)
}

async function handleSend(content: string, isStegoMode: boolean) {
  if (isStegoMode) {
    stegoLoading.value = true
    try {
      const key = chatStore.getStegoKey(true)
      const res = await stegoApi.embed(content, key, 'celebahq')
      // Strip data:image/png;base64, prefix if present
      let base64 = res.stego_image
      if (base64.startsWith('data:')) {
        base64 = base64.split(',')[1]
      }
      await chatStore.sendStegoMessage(contactId.value, base64)
    } catch (e: any) {
      alert('隐写嵌入失败: ' + (e.response?.data?.detail || e.message))
    } finally {
      stegoLoading.value = false
    }
  } else {
    await chatStore.sendTextMessage(contactId.value, content)
  }
}
```

- [ ] **Step 2: Update template**

Replace the template:

```html
<template>
  <div class="chat-view">
    <div class="header">
      <button class="back-btn" @click="router.back()">←</button>
      <span class="name">{{ contact?.username || contactId }}</span>
    </div>

    <div class="messages" ref="messagesContainer">
      <MessageBubble
        v-for="msg in messages"
        :key="msg.id"
        :message="msg"
        :stego-key="getStegoKeyForMessage(msg)"
      />
    </div>

    <div v-if="stegoLoading" class="loading">正在生成隐写图像...</div>

    <MessageInput
      @send="handleSend"
      :stego-max-capacity="stegoMaxCapacity"
      :stego-mode-disabled="!chatStore.inviteCodesLoaded"
    />
  </div>
</template>
```

- [ ] **Step 3: Commit**

```bash
git add web/src/views/chat/ChatView.vue
git commit -m "feat(web): wire stego chat with invite code keys and capacity check"
```

---

## Chunk 4: Android — API Layer & ViewModel

### Task 8: Add Android API methods for new endpoints

**Files:**
- Modify: `android/app/src/main/java/com/stegoapp/app/api/StegoApi.kt`
- Modify: `android/app/src/main/java/com/stegoapp/app/api/InviteApi.kt`
- Modify: `android/app/src/main/java/com/stegoapp/app/api/ApiResponse.kt`

- [ ] **Step 1: Add MaxCapacityResponse and UserCodeResponse to ApiResponse.kt**

Add after `CapacityResponse` (after line 18):

```kotlin
data class MaxCapacityResponse(
    val max_capacity: Int
)

data class UserCodeResponse(
    val code: String,
    val user_id: String,
    val username: String
)
```

- [ ] **Step 2: Add getMaxCapacity to StegoApi.kt**

Add after the `extract` method (after line 34):

```kotlin
@GET("/api/v1/stego/max-capacity")
suspend fun getMaxCapacity(
    @Query("key") key: String,
    @Query("model") model: String = "celebahq"
): Response<MaxCapacityResponse>
```

- [ ] **Step 3: Add getUserCode to InviteApi.kt**

Add after `lookupCode` method (after line 13):

```kotlin
@GET("/api/v1/invite/user-code/{userId}")
suspend fun getUserCode(@Path("userId") userId: String): UserCodeResponse
```

- [ ] **Step 4: Commit**

```bash
git add android/app/src/main/java/com/stegoapp/app/api/StegoApi.kt \
        android/app/src/main/java/com/stegoapp/app/api/InviteApi.kt \
        android/app/src/main/java/com/stegoapp/app/api/ApiResponse.kt
git commit -m "feat(android): add API methods for max-capacity and user-code endpoints"
```

---

### Task 9: Add stego capabilities to ChatViewModel

**Files:**
- Modify: `android/app/src/main/java/com/stegoapp/app/ui/viewmodel/ChatViewModel.kt`

- [ ] **Step 1: Add imports**

Add to imports (after line 13):

```kotlin
import com.stegoapp.app.api.ApiClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
```

- [ ] **Step 2: Add invite code state and stego mode state**

Add after `_kicked` (after line 40):

```kotlin
private val _myInviteCode = MutableStateFlow("")
val myInviteCode: StateFlow<String> = _myInviteCode.asStateFlow()

private val _peerInviteCode = MutableStateFlow("")
val peerInviteCode: StateFlow<String> = _peerInviteCode.asStateFlow()

private val _inviteCodesLoaded = MutableStateFlow(false)
val inviteCodesLoaded: StateFlow<Boolean> = _inviteCodesLoaded.asStateFlow()

private val _stegoMode = MutableStateFlow(false)
val stegoMode: StateFlow<Boolean> = _stegoMode.asStateFlow()

private val _maxCapacity = MutableStateFlow(0)
val maxCapacity: StateFlow<Int> = _maxCapacity.asStateFlow()

private val _stegoLoading = MutableStateFlow(false)
val stegoLoading: StateFlow<Boolean> = _stegoLoading.asStateFlow()
```

- [ ] **Step 3: Add loadInviteCodes method**

Add after the state declarations:

```kotlin
fun loadInviteCodes(peerUserId: String) {
    viewModelScope.launch {
        try {
            val inviteApi = ApiClient.inviteApi
            val myCodeRes = inviteApi.getMyCode()
            val peerCodeRes = inviteApi.getUserCode(peerUserId)
            _myInviteCode.value = myCodeRes.code
            _peerInviteCode.value = peerCodeRes.code
            _inviteCodesLoaded.value = true
            // Fetch max capacity
            fetchMaxCapacity()
        } catch (e: Exception) {
            _inviteCodesLoaded.value = false
        }
    }
}

private fun fetchMaxCapacity() {
    viewModelScope.launch {
        try {
            val key = _myInviteCode.value + _peerInviteCode.value
            val stegoApi = ApiClient.stegoApi
            val res = stegoApi.getMaxCapacity(key)
            if (res.isSuccessful) {
                _maxCapacity.value = res.body()?.max_capacity ?: 0
            }
        } catch (_: Exception) {}
    }
}

fun toggleStegoMode() {
    if (!_inviteCodesLoaded.value) return
    _stegoMode.value = !_stegoMode.value
}

fun getStegoKey(isOutgoing: Boolean): String {
    return if (isOutgoing) {
        _myInviteCode.value + _peerInviteCode.value
    } else {
        _peerInviteCode.value + _myInviteCode.value
    }
}
```

- [ ] **Step 4: Add sendStegoMessage method**

Add after `sendTextMessage`:

```kotlin
fun sendStegoMessage(toUserId: String, secretMessage: String, fromUserId: String, fromUsername: String) {
    viewModelScope.launch {
        _stegoLoading.value = true
        try {
            val key = getStegoKey(true)
            val stegoApi = ApiClient.stegoApi
            val res = stegoApi.embed(secretMessage, key, "celebahq")
            if (!res.isSuccessful) {
                _stegoLoading.value = false
                return@launch
            }
            var stegoImage = res.body()?.stego_image ?: return@launch
            // Strip data:image/png;base64, prefix
            if (stegoImage.startsWith("data:")) {
                stegoImage = stegoImage.substringAfter(",")
            }

            val msgId = UUID.randomUUID().toString()
            val now = System.currentTimeMillis() / 1000
            val entity = MessageEntity(
                id = msgId,
                contactId = toUserId,
                direction = "sent",
                content = "",
                contentType = "stego",
                stegoImage = stegoImage,
                status = "sending",
                createdAt = now.toString()
            )
            messageDao.insert(entity)

            wsClient.send(WsMessage(
                type = "chat",
                id = msgId,
                timestamp = now,
                payload = mapOf(
                    "from_user_id" to fromUserId,
                    "from_username" to fromUsername,
                    "to_user_id" to toUserId,
                    "content" to "",
                    "content_type" to "stego",
                    "stego_image" to stegoImage,
                    "burn_after" to 0
                )
            ))
        } catch (_: Exception) {
        } finally {
            _stegoLoading.value = false
        }
    }
}
```

- [ ] **Step 5: Add extractMessage method**

Add after `sendStegoMessage`:

```kotlin
suspend fun extractMessage(stegoImageBase64: String, isOutgoing: Boolean): String {
    return withContext(Dispatchers.IO) {
        try {
            val key = getStegoKey(isOutgoing)
            // Convert base64 to temp file
            val base64Clean = if (stegoImageBase64.startsWith("data:")) {
                stegoImageBase64.substringAfter(",")
            } else {
                stegoImageBase64
            }
            val bytes = android.util.Base64.decode(base64Clean, android.util.Base64.DEFAULT)
            val tempFile = File.createTempFile("stego_extract", ".png", getApplication<Application>().cacheDir)
            tempFile.writeBytes(bytes)

            val requestFile = tempFile.asRequestBody("image/png".toMediaTypeOrNull())
            val part = okhttp3.MultipartBody.Part.createFormData("stego_image", "stego.png", requestFile)
            val keyBody = key.toRequestBody("text/plain".toMediaTypeOrNull())
            val modelBody = "celebahq".toRequestBody("text/plain".toMediaTypeOrNull())

            val stegoApi = ApiClient.stegoApi
            val res = stegoApi.extract(part, keyBody, modelBody)
            tempFile.delete()

            if (res.isSuccessful) {
                res.body()?.secret_message ?: "(空)"
            } else {
                "提取失败: ${res.code()}"
            }
        } catch (e: Exception) {
            "提取失败: ${e.message}"
        }
    }
}
```

- [ ] **Step 6: Commit**

```bash
git add android/app/src/main/java/com/stegoapp/app/ui/viewmodel/ChatViewModel.kt
git commit -m "feat(android): add stego send, extract, and invite code management to ChatViewModel"
```

---

## Chunk 5: Android — Chat UI

### Task 10: Update ChatScreen with stego mode and image display

**Files:**
- Modify: `android/app/src/main/java/com/stegoapp/app/ui/screens/chat/ChatScreen.kt`

- [ ] **Step 1: Add imports**

Add to imports (after existing imports):

```kotlin
import android.content.ContentValues
import android.graphics.BitmapFactory
import android.provider.MediaStore
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.launch
```

- [ ] **Step 2: Add stego state collection in ChatScreen composable**

Add after existing state variables (~line 33):

```kotlin
val stegoMode by chatViewModel.stegoMode.collectAsState()
val inviteCodesLoaded by chatViewModel.inviteCodesLoaded.collectAsState()
val maxCapacity by chatViewModel.maxCapacity.collectAsState()
val stegoLoading by chatViewModel.stegoLoading.collectAsState()
val scope = rememberCoroutineScope()
val context = LocalContext.current

// Load invite codes on mount
LaunchedEffect(contactId) {
    chatViewModel.loadInviteCodes(contactId)
}

val inputByteLength = remember(inputText) {
    inputText.toByteArray(Charsets.UTF_8).size
}
val overCapacity = stegoMode && maxCapacity > 0 && inputByteLength > maxCapacity
val canSend = inputText.isNotBlank() && !stegoLoading && !(stegoMode && overCapacity)
```

- [ ] **Step 3: Update bottom bar with stego toggle and capacity indicator**

Replace the bottom bar section (the `Row` with `OutlinedTextField` and Send button, ~lines 52-78):

```kotlin
Column {
    // Capacity indicator
    if (stegoMode && maxCapacity > 0) {
        Text(
            text = "秘密消息: $inputByteLength/$maxCapacity 字节",
            fontSize = 12.sp,
            color = if (overCapacity) Color.Red else Color.Gray,
            modifier = Modifier.padding(start = 56.dp, top = 4.dp)
        )
    }

    Row(
        modifier = Modifier.padding(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Stego mode toggle
        IconButton(
            onClick = { chatViewModel.toggleStegoMode() },
            enabled = inviteCodesLoaded
        ) {
            Text(
                text = if (stegoMode) "🔒" else "💬",
                fontSize = 20.sp
            )
        }

        OutlinedTextField(
            value = inputText,
            onValueChange = { inputText = it },
            placeholder = { Text(if (stegoMode) "输入秘密消息..." else "输入消息...") },
            modifier = Modifier.weight(1f),
            singleLine = true
        )
        if (stegoLoading) {
            CircularProgressIndicator(
                modifier = Modifier.padding(8.dp).size(24.dp),
                strokeWidth = 2.dp
            )
        } else {
            IconButton(
                onClick = {
                    if (stegoMode) {
                        chatViewModel.sendStegoMessage(contactId, inputText.trim(), currentUserId, currentUsername)
                    } else {
                        chatViewModel.sendTextMessage(contactId, inputText.trim(), currentUserId, currentUsername)
                    }
                    inputText = ""
                },
                enabled = canSend
            ) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
            }
        }
    }
}
```

- [ ] **Step 4: Update MessageBubble composable with stego image display and long-press menu**

Replace the `MessageBubble` composable (lines 93-142):

```kotlin
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MessageBubble(message: MessageEntity, chatViewModel: ChatViewModel) {
    val isSent = message.direction == "sent"
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var showMenu by remember { mutableStateOf(false) }
    var extractedText by remember { mutableStateOf<String?>(null) }
    var extracting by remember { mutableStateOf(false) }

    val stegoBitmap = remember(message.stegoImage) {
        message.stegoImage?.let {
            try {
                val base64 = if (it.startsWith("data:")) it.substringAfter(",") else it
                val bytes = android.util.Base64.decode(base64, android.util.Base64.DEFAULT)
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            } catch (_: Exception) { null }
        }
    }

    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp),
        horizontalArrangement = if (isSent) Arrangement.End else Arrangement.Start
    ) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = if (isSent) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.surfaceVariant,
            tonalElevation = 1.dp
        ) {
            Column(modifier = Modifier.padding(10.dp).widthIn(max = 260.dp)) {
                // Stego image
                if (message.contentType == "stego" && stegoBitmap != null) {
                    Box {
                        Image(
                            bitmap = stegoBitmap.asImageBitmap(),
                            contentDescription = "隐写图像",
                            modifier = Modifier
                                .fillMaxWidth()
                                .combinedClickable(
                                    onClick = {},
                                    onLongClick = { showMenu = true }
                                )
                        )
                        DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                            DropdownMenuItem(
                                text = { Text("提取秘密消息") },
                                onClick = {
                                    showMenu = false
                                    if (!extracting && message.stegoImage != null) {
                                        extracting = true
                                        scope.launch {
                                            val isOut = message.direction == "sent"
                                            extractedText = chatViewModel.extractMessage(message.stegoImage, isOut)
                                            extracting = false
                                        }
                                    }
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("保存图像") },
                                onClick = {
                                    showMenu = false
                                    message.stegoImage?.let { img ->
                                        scope.launch {
                                            saveImageToGallery(context, img, message.id)
                                        }
                                    }
                                }
                            )
                        }
                    }

                    // Extraction result
                    if (extracting) {
                        Text("提取中...", fontSize = 12.sp, color = Color.Gray,
                            modifier = Modifier.padding(top = 4.dp))
                    } else if (extractedText != null) {
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.7f),
                            modifier = Modifier.padding(top = 4.dp)
                        ) {
                            Text(extractedText!!, fontSize = 13.sp,
                                modifier = Modifier.padding(6.dp))
                        }
                    }
                }

                // Text content (only if non-empty)
                if (message.content.isNotBlank()) {
                    Text(
                        text = message.content,
                        color = if (isSent) MaterialTheme.colorScheme.onPrimary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 15.sp
                    )
                }

                // Status
                Row(
                    modifier = Modifier.align(Alignment.End),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = formatTimestamp(message.createdAt),
                        fontSize = 11.sp,
                        color = if (isSent) MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.7f)
                                else Color.Gray
                    )
                    if (isSent) {
                        val icon = when (message.status) {
                            "sending" -> "⏳"; "sent" -> "✓"; "delivered" -> "✓✓"
                            "read" -> "👁"; "failed" -> "❌"; else -> ""
                        }
                        Text(icon, fontSize = 11.sp, modifier = Modifier.padding(start = 4.dp))
                    }
                }
            }
        }
    }
}
```

- [ ] **Step 5: Add helper functions**

Add after `MessageBubble` composable:

```kotlin
private fun formatTimestamp(ts: String): String {
    return try {
        val epoch = ts.toLongOrNull() ?: return ts
        val sdf = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
        sdf.format(java.util.Date(epoch * 1000))
    } catch (_: Exception) { ts }
}

private suspend fun saveImageToGallery(context: android.content.Context, base64: String, msgId: String) {
    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        try {
            val clean = if (base64.startsWith("data:")) base64.substringAfter(",") else base64
            val bytes = android.util.Base64.decode(clean, android.util.Base64.DEFAULT)
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "stego_$msgId.png")
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/StegoApp")
            }
            val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            uri?.let {
                context.contentResolver.openOutputStream(it)?.use { out -> out.write(bytes) }
            }
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                Toast.makeText(context, "图像已保存", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                Toast.makeText(context, "保存失败: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
```

- [ ] **Step 6: Update LazyColumn to pass chatViewModel to MessageBubble**

Update the `MessageBubble` call in the `LazyColumn` to pass `chatViewModel`:

```kotlin
MessageBubble(message = msg, chatViewModel = chatViewModel)
```

- [ ] **Step 7: Commit**

```bash
git add android/app/src/main/java/com/stegoapp/app/ui/screens/chat/ChatScreen.kt
git commit -m "feat(android): add stego mode, image display, extract menu, and save to ChatScreen"
```

---

## Chunk 6: Verification & Integration

### Task 11: End-to-end verification

- [ ] **Step 1: Start the server**

```bash
cd server && python main.py
```

Verify the new endpoints:
- `curl http://localhost:8000/api/v1/stego/max-capacity?key=testkey` → should return `{"max_capacity": <number>}`
- Invite user-code endpoint requires auth, verify no 500 errors

- [ ] **Step 2: Test Web flow**

1. Open web app, log in
2. Open a chat with a contact
3. Verify stego mode toggle works and is disabled until invite codes load
4. Switch to stego mode → capacity indicator appears
5. Type text → byte count updates
6. Send → stego image appears in chat bubble
7. Right-click image → context menu with "提取秘密消息" and "保存图像"
8. Click extract → extracted text appears below image
9. Click save → PNG downloads

- [ ] **Step 3: Test Android flow**

1. Build and run Android app
2. Open a chat with a contact
3. Verify stego toggle appears and is disabled until codes load
4. Switch to stego mode → capacity indicator appears
5. Type text → byte count updates
6. Send → loading indicator → stego image appears in bubble
7. Long-press image → dropdown menu
8. Tap extract → extracted text appears below image
9. Tap save → image saved to gallery

- [ ] **Step 4: Test cross-platform**

1. Send stego message from Web → verify it displays on Android
2. Send stego message from Android → verify it displays on Web
3. Extract on both sides → should show same secret message

- [ ] **Step 5: Final commit**

If any fixes were needed during testing, commit them.
