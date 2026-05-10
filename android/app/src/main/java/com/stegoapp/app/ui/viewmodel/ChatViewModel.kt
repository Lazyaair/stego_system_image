package com.stegoapp.app.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.stegoapp.app.crypto.CryptoUtils
import com.stegoapp.app.crypto.SealedMessage
import com.stegoapp.app.data.local.AppDatabase
import com.stegoapp.app.data.local.entity.ContactEntity
import com.stegoapp.app.data.local.entity.MessageEntity
import com.stegoapp.app.data.remote.WsClient
import com.stegoapp.app.data.remote.WsMessage
import com.stegoapp.app.api.ApiClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.util.UUID

class ChatViewModel(app: Application) : AndroidViewModel(app) {
    private val db = AppDatabase.getInstance(app)
    private val messageDao = db.messageDao()
    private val contactDao = db.contactDao()
    private val blacklistDao = db.blacklistDao()
    private val userSettingsDao = db.userSettingsDao()
    private val wsClient = WsClient.instance

    // Active chat session + per-session mkey. Kept in memory only, never
    // persisted (see spec §2 G2). null whenever self phrase or peer
    // phrase for this contact is missing.
    private val _activeContactId = MutableStateFlow<String?>(null)
    val activeContactId: StateFlow<String?> = _activeContactId.asStateFlow()

    private val _mkey = MutableStateFlow<ByteArray?>(null)
    val mkey: StateFlow<ByteArray?> = _mkey.asStateFlow()

    val isE2EEConfigured: StateFlow<Boolean> = _mkey
        .map { it != null }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    private val _selfPhraseConfigured = MutableStateFlow(false)
    val selfPhraseConfigured: StateFlow<Boolean> = _selfPhraseConfigured.asStateFlow()

    private val _peerPhraseConfigured = MutableStateFlow(false)
    val peerPhraseConfigured: StateFlow<Boolean> = _peerPhraseConfigured.asStateFlow()

    private val _snackbar = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val snackbar = _snackbar.asSharedFlow()

    init {
        // Recompute mkey whenever activeContactId, self settings, or the
        // contact row change (e.g. peer phrase updated from
        // ContactDetailScreen).
        viewModelScope.launch {
            combine(
                _activeContactId,
                userSettingsDao.observe(),
                contactDao.getAll(),
            ) { contactId, self, contacts ->
                Triple(contactId, self, contacts)
            }.collect { (contactId, self, contacts) ->
                _selfPhraseConfigured.value = self != null
                if (contactId == null || self == null) {
                    _peerPhraseConfigured.value = false
                    _mkey.value = null
                    return@collect
                }
                val contact = contacts.find { it.userId == contactId }
                val peerHex = contact?.peerUserKeyHex
                _peerPhraseConfigured.value = !peerHex.isNullOrEmpty()
                if (peerHex.isNullOrEmpty()) {
                    _mkey.value = null
                    return@collect
                }
                _mkey.value = withContext(Dispatchers.Default) {
                    runCatching {
                        val selfKey = hexToBytes(self.userKeyHex)
                        val peerKey = hexToBytes(peerHex)
                        CryptoUtils.deriveMkey(selfKey, peerKey)
                    }.getOrNull()
                }
            }
        }
    }

    fun setActiveContact(contactId: String?) {
        _activeContactId.value = contactId
    }

    private val _pendingRequests = MutableStateFlow<List<PendingRequest>>(emptyList())
    val pendingRequests: StateFlow<List<PendingRequest>> = _pendingRequests

    data class PendingRequest(
        val userId: String,
        val username: String,
        val messages: List<WsMessage>
    )

    fun getMessages(contactId: String): Flow<List<MessageEntity>> {
        return messageDao.getByContact(contactId)
    }

    fun getLastMessages(): Flow<List<MessageEntity>> {
        return messageDao.getLastMessages()
    }

    private val _kicked = MutableSharedFlow<Unit>(extraBufferCapacity = 4)
    val kicked = _kicked.asSharedFlow()

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

    fun loadInviteCodes(peerUserId: String) {
        viewModelScope.launch {
            try {
                val inviteApi = ApiClient.inviteApi
                val myCodeRes = inviteApi.getMyCode()
                val peerCodeRes = inviteApi.getUserCode(peerUserId)
                _myInviteCode.value = myCodeRes.code
                _peerInviteCode.value = peerCodeRes.code
                _inviteCodesLoaded.value = true
                fetchMaxCapacity()
            } catch (e: Exception) {
                _inviteCodesLoaded.value = false
            }
        }
    }

    private fun fetchMaxCapacity() {
        viewModelScope.launch {
            try {
                val key = getStegoKey()
                val stegoApi = ApiClient.stegoApi
                val res = stegoApi.getMaxCapacity(key)
                if (res.isSuccessful) {
                    val serverMax = res.body()?.max_capacity ?: 0
                    // Sealed payload adds ~37% (base64url + AES-GCM tag + JSON
                    // envelope, outer-base64url'd). Budget plaintext at 0.65 *
                    // server max so the sealed blob we actually send fits.
                    _maxCapacity.value = (serverMax * 0.65).toInt()
                }
            } catch (_: Exception) {}
        }
    }

    fun toggleStegoMode() {
        if (!_inviteCodesLoaded.value) return
        _stegoMode.value = !_stegoMode.value
    }

    /**
     * 隐写模型 seed 密钥 = invite_A 与 invite_B 按字节 XOR 的 hex 字符串。
     * XOR 对称,两端独立计算结果一致,因此无需区分方向。
     */
    fun getStegoKey(): String {
        val a = _myInviteCode.value.toByteArray(Charsets.UTF_8)
        val b = _peerInviteCode.value.toByteArray(Charsets.UTF_8)
        if (a.isEmpty() || b.isEmpty() || a.size != b.size) return ""
        return CryptoUtils.xorBytes(a, b).joinToString("") { "%02x".format(it) }
    }

    fun connectWebSocket(token: String) {
        wsClient.connect(token)
        viewModelScope.launch {
            wsClient.messages.collect { msg ->
                handleWsMessage(msg)
            }
        }
        // Fallback path: server force-closes socket with code 4001 when another
        // device logs in. We still emit kicked so the UI can redirect.
        viewModelScope.launch {
            wsClient.kicked.collect {
                _kicked.emit(Unit)
            }
        }
    }

    fun disconnectWebSocket() {
        wsClient.disconnect()
    }

    private suspend fun handleWsMessage(msg: WsMessage) {
        when (msg.type) {
            "kicked" -> {
                wsClient.disconnect()
                _kicked.emit(Unit)
            }
            "chat" -> handleChat(msg)
            "ack" -> handleAck(msg)
            "delivered" -> handleDelivered(msg)
            "read" -> handleRead(msg)
            "revoke" -> handleRevoke(msg)
        }
    }

    private suspend fun handleChat(msg: WsMessage) {
        val payload = msg.payload ?: return
        val fromUserId = payload["from_user_id"] as? String ?: return
        val fromUsername = payload["from_username"] as? String ?: ""

        if (blacklistDao.isBlacklisted(fromUserId)) return

        val contact = contactDao.getById(fromUserId)
        if (contact == null) {
            val existing = _pendingRequests.value.find { it.userId == fromUserId }
            if (existing != null) {
                _pendingRequests.value = _pendingRequests.value.map {
                    if (it.userId == fromUserId) it.copy(messages = it.messages + msg)
                    else it
                }
            } else {
                _pendingRequests.value += PendingRequest(
                    userId = fromUserId,
                    username = fromUsername,
                    messages = listOf(msg)
                )
            }
            return
        }

        val rawContent = payload["content"] as? String ?: ""
        val contentType = payload["content_type"] as? String ?: "text"

        // For text messages, try to decrypt with a freshly-derived mkey for
        // this sender (not necessarily the active chat's mkey — incoming
        // messages from other contacts must still decrypt correctly).
        // On failure, fall back to raw content (legacy plaintext).
        val displayedContent: String = if (contentType == "text") {
            decryptForContact(contact, rawContent) ?: rawContent
        } else {
            rawContent
        }

        val entity = MessageEntity(
            id = msg.id ?: UUID.randomUUID().toString(),
            contactId = fromUserId,
            direction = "received",
            content = displayedContent,
            contentType = contentType,
            stegoImage = payload["stego_image"] as? String,
            status = "delivered",
            burnAfter = (payload["burn_after"] as? Double)?.toInt() ?: 0,
            createdAt = System.currentTimeMillis().toString()
        )
        messageDao.insert(entity)

        // Send delivered receipt
        wsClient.send(WsMessage(
            type = "delivered",
            id = UUID.randomUUID().toString(),
            timestamp = System.currentTimeMillis() / 1000,
            payload = mapOf(
                "to_user_id" to fromUserId,
                "message_id" to msg.id
            )
        ))
    }

    private suspend fun handleAck(msg: WsMessage) {
        msg.id?.let { messageDao.updateStatus(it, "sent") }
    }

    private suspend fun handleDelivered(msg: WsMessage) {
        val messageId = (msg.payload?.get("message_id") as? String) ?: return
        messageDao.updateStatus(messageId, "delivered")
    }

    private suspend fun handleRead(msg: WsMessage) {
        val messageId = (msg.payload?.get("message_id") as? String) ?: return
        messageDao.updateStatus(messageId, "read")
    }

    private suspend fun handleRevoke(msg: WsMessage) {
        val messageId = (msg.payload?.get("message_id") as? String) ?: return
        messageDao.markRevoked(messageId)
    }

    fun sendTextMessage(toUserId: String, content: String, fromUserId: String, fromUsername: String) {
        viewModelScope.launch {
            // Gate: must have an mkey for the active chat. UI should have
            // already disabled input, but fail loudly if not.
            val activeMkey = _mkey.value
            if (activeMkey == null || _activeContactId.value != toUserId) {
                _snackbar.emit("端到端加密未配置,无法发送消息")
                return@launch
            }

            val sealed = try {
                withContext(Dispatchers.Default) {
                    SealedMessage.seal(activeMkey, content)
                }
            } catch (e: Exception) {
                _snackbar.emit("加密失败: ${e.message ?: "unknown"}")
                return@launch
            }

            val id = UUID.randomUUID().toString()
            val now = System.currentTimeMillis()

            // Local storage: keep PLAINTEXT for the sender's own history.
            // Sealed blob is wire-only.
            messageDao.insert(MessageEntity(
                id = id,
                contactId = toUserId,
                direction = "sent",
                content = content,
                contentType = "text",
                status = "sending",
                createdAt = now.toString()
            ))

            wsClient.send(WsMessage(
                type = "chat",
                id = id,
                timestamp = now / 1000,
                payload = mapOf(
                    "from_user_id" to fromUserId,
                    "from_username" to fromUsername,
                    "to_user_id" to toUserId,
                    "content" to sealed,
                    "content_type" to "text",
                    "burn_after" to 0,
                    "is_first_contact" to false
                )
            ))
        }
    }

    /**
     * Derive an mkey against the given contact (may differ from the
     * active chat contact) and attempt to decrypt. Returns null if
     * either side's key material is missing, or the blob is not a
     * valid sealed message.
     */
    private suspend fun decryptForContact(contact: ContactEntity, sealed: String): String? {
        if (sealed.isEmpty()) return null
        val self = userSettingsDao.get() ?: return null
        val peerHex = contact.peerUserKeyHex ?: return null
        return withContext(Dispatchers.Default) {
            runCatching {
                val selfKey = hexToBytes(self.userKeyHex)
                val peerKey = hexToBytes(peerHex)
                val mkey = CryptoUtils.deriveMkey(selfKey, peerKey)
                SealedMessage.tryOpen(mkey, sealed)
            }.getOrNull()
        }
    }

    private fun hexToBytes(hex: String): ByteArray {
        require(hex.length % 2 == 0) { "hex: odd length" }
        val out = ByteArray(hex.length / 2)
        for (i in out.indices) {
            val hi = Character.digit(hex[i * 2], 16)
            val lo = Character.digit(hex[i * 2 + 1], 16)
            out[i] = ((hi shl 4) or lo).toByte()
        }
        return out
    }

    fun sendStegoMessage(toUserId: String, secretMessage: String, fromUserId: String, fromUsername: String) {
        viewModelScope.launch {
            // Gate: stego embedding also needs a sealed payload. UI already
            // disables send when canSend is false (which requires e2eeReady).
            // Snackbar as defensive fallback.
            val activeMkey = _mkey.value
            if (activeMkey == null || _activeContactId.value != toUserId) {
                _snackbar.emit("端到端加密未配置,无法发送隐写消息")
                return@launch
            }

            _stegoLoading.value = true
            try {
                val sealed = withContext(Dispatchers.Default) {
                    SealedMessage.seal(activeMkey, secretMessage)
                }
                val key = getStegoKey()
                val stegoApi = ApiClient.stegoApi
                // Feed the sealed ASCII base64url string to /embed as the
                // `message` field. Server embeds the ciphertext bytes and
                // never sees plaintext.
                val res = stegoApi.embed(sealed, key)
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
                // Local DB: store PLAINTEXT for the sender's own history view.
                // Sealed blob only lives inside the stego image on the wire.
                val entity = MessageEntity(
                    id = msgId,
                    contactId = toUserId,
                    direction = "sent",
                    content = secretMessage,
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
            } catch (e: Exception) {
                _snackbar.emit("加密失败: ${e.message ?: "unknown"}")
            } finally {
                _stegoLoading.value = false
            }
        }
    }

    /**
     * Extract a stego image's payload and decrypt it with the active chat's
     * mkey. The extracted bytes are the sealed base64url ciphertext we
     * embedded on send; tryOpen recovers the original plaintext. Returns a
     * human-readable string for the bubble UI (plaintext on success,
     * explanation on failure).
     *
     * Uses the active chat's mkey (which is tied to the currently-open
     * contact). For history viewing from other contacts this would need
     * a per-contact derivation — but the current UI only extracts inside
     * the active ChatScreen so the active mkey is always correct.
     */
    suspend fun extractMessage(stegoImageBase64: String, @Suppress("UNUSED_PARAMETER") isOutgoing: Boolean): String {
        return withContext(Dispatchers.IO) {
            try {
                val key = getStegoKey()
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

                val stegoApi = ApiClient.stegoApi
                val res = stegoApi.extract(part, keyBody)
                tempFile.delete()

                if (!res.isSuccessful) {
                    return@withContext "提取失败: ${res.code()}"
                }
                val extracted = res.body()?.secret_message ?: ""
                if (extracted.isEmpty()) return@withContext "(空)"

                // Unseal the extracted ciphertext. Use the active mkey;
                // active chat is always the one whose stego we're viewing.
                val activeMkey = _mkey.value
                if (activeMkey == null) {
                    return@withContext "[无法解密,未配置端到端加密]"
                }
                val opened = withContext(Dispatchers.Default) {
                    SealedMessage.tryOpen(activeMkey, extracted)
                }
                opened ?: "[无法解密,双方助记词可能不一致]"
            } catch (e: Exception) {
                "提取失败: ${e.message}"
            }
        }
    }

    fun sendFirstContact(toUserId: String, fromUserId: String, fromUsername: String) {
        wsClient.send(WsMessage(
            type = "chat",
            id = UUID.randomUUID().toString(),
            timestamp = System.currentTimeMillis() / 1000,
            payload = mapOf(
                "from_user_id" to fromUserId,
                "from_username" to fromUsername,
                "to_user_id" to toUserId,
                "content" to "$fromUsername wants to be your friend",
                "content_type" to "text",
                "burn_after" to 0,
                "is_first_contact" to true
            )
        ))
    }

    fun sendReadReceipt(toUserId: String, messageId: String) {
        wsClient.send(WsMessage(
            type = "read",
            id = UUID.randomUUID().toString(),
            timestamp = System.currentTimeMillis() / 1000,
            payload = mapOf(
                "to_user_id" to toUserId,
                "message_id" to messageId
            )
        ))
    }

    fun acceptPendingRequest(request: PendingRequest) {
        viewModelScope.launch {
            contactDao.insert(ContactEntity(
                userId = request.userId,
                username = request.username,
                status = "accepted",
                addedAt = System.currentTimeMillis().toString()
            ))
            // Process all stored messages from this user
            for (msg in request.messages) {
                handleChat(msg)
            }
            _pendingRequests.value = _pendingRequests.value.filter { it.userId != request.userId }
        }
    }

    fun rejectPendingRequest(request: PendingRequest) {
        _pendingRequests.value = _pendingRequests.value.filter { it.userId != request.userId }
    }

    fun blockPendingRequest(request: PendingRequest) {
        viewModelScope.launch {
            blacklistDao.insert(
                com.stegoapp.app.data.local.entity.BlacklistEntity(
                    userId = request.userId,
                    username = request.username,
                    blockedAt = System.currentTimeMillis().toString(),
                ),
            )
            _pendingRequests.value = _pendingRequests.value.filter { it.userId != request.userId }
        }
    }
}
