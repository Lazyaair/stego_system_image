package com.stegoapp.app.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
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
    private val wsClient = WsClient.instance

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

    fun connectWebSocket(token: String) {
        wsClient.connect(token)
        viewModelScope.launch {
            wsClient.messages.collect { msg ->
                handleWsMessage(msg)
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
                _pendingRequests.value = _pendingRequests.value + PendingRequest(
                    userId = fromUserId,
                    username = fromUsername,
                    messages = listOf(msg)
                )
            }
            return
        }

        val entity = MessageEntity(
            id = msg.id ?: UUID.randomUUID().toString(),
            contactId = fromUserId,
            direction = "received",
            content = payload["content"] as? String ?: "",
            contentType = payload["content_type"] as? String ?: "text",
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
            val id = UUID.randomUUID().toString()
            val now = System.currentTimeMillis()

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
                    "content" to content,
                    "content_type" to "text",
                    "burn_after" to 0,
                    "is_first_contact" to false
                )
            ))
        }
    }

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

    suspend fun extractMessage(stegoImageBase64: String, isOutgoing: Boolean): String {
        return withContext(Dispatchers.IO) {
            try {
                val key = getStegoKey(isOutgoing)
                val base64Clean = if (stegoImageBase64.startsWith("data:")) {
                    stegoImageBase64.substringAfter(",")
                } else {
                    stegoImageBase64
                }
                val bytes = android.util.Base64.decode(base64Clean, android.util.Base64.DEFAULT)
                val tempFile = File.createTempFile("stego_extract", ".png", getApplication<android.app.Application>().cacheDir)
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
}
