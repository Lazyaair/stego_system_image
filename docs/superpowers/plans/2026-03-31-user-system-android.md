# User System Android Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add user auth, contacts, chat with WebSocket messaging, and local Room database to the Android app.

**Architecture:** Extend the existing Jetpack Compose app with Room for local persistence, OkHttp WebSocket for real-time messaging, DataStore for token storage, and new screens for auth/chat/contacts. Add auth interceptor to Retrofit client.

**Tech Stack:** Kotlin, Jetpack Compose, Room, OkHttp WebSocket, DataStore, Navigation Compose

**Depends on:** Server plan (`2026-03-31-user-system-server.md`) must be completed first.

**Spec:** `docs/superpowers/specs/2026-03-31-user-system-design.md`

---

## File Structure

All files under `android/app/src/main/java/com/stegoapp/app/`

| Action | File | Responsibility |
|--------|------|----------------|
| Create | `data/local/AppDatabase.kt` | Room database |
| Create | `data/local/entity/ContactEntity.kt` | Contact table |
| Create | `data/local/entity/MessageEntity.kt` | Message table |
| Create | `data/local/entity/BlacklistEntity.kt` | Blacklist table |
| Create | `data/local/dao/ContactDao.kt` | Contact queries |
| Create | `data/local/dao/MessageDao.kt` | Message queries |
| Create | `data/local/dao/BlacklistDao.kt` | Blacklist queries |
| Create | `data/local/TokenStore.kt` | DataStore for JWT token |
| Create | `api/AuthApi.kt` | Auth Retrofit interface |
| Create | `api/InviteApi.kt` | Invite Retrofit interface |
| Modify | `api/ApiClient.kt` | Add auth interceptor, new APIs |
| Modify | `api/ApiResponse.kt` | Add auth/invite response types |
| Create | `data/remote/WsClient.kt` | OkHttp WebSocket client |
| Create | `ui/viewmodel/AuthViewModel.kt` | Auth state management |
| Create | `ui/viewmodel/ChatViewModel.kt` | Chat state management |
| Create | `ui/viewmodel/ContactViewModel.kt` | Contact state management |
| Create | `ui/screens/auth/LoginScreen.kt` | Login screen |
| Create | `ui/screens/auth/RegisterScreen.kt` | Register screen |
| Create | `ui/screens/chat/ChatListScreen.kt` | Chat list screen |
| Create | `ui/screens/chat/ChatScreen.kt` | Chat conversation screen |
| Create | `ui/screens/contact/ContactsScreen.kt` | Contact list screen |
| Create | `ui/screens/contact/AddContactScreen.kt` | Add contact screen |
| Create | `ui/screens/profile/ProfileScreen.kt` | Profile screen |
| Modify | `ui/navigation/NavGraph.kt` | Add new routes |
| Modify | `MainActivity.kt` | Update navigation and bottom bar |

---

## Chunk 1: Dependencies and Data Layer

### Task 1: Add Dependencies

**Files:**
- Modify: `android/gradle/libs.versions.toml`
- Modify: `android/app/build.gradle.kts`

- [ ] **Step 1: Add version catalog entries**

Add to `android/gradle/libs.versions.toml` under `[versions]`:
```toml
room = "2.6.1"
datastore = "1.1.1"
gson = "2.10.1"
```

Add under `[libraries]`:
```toml
room-runtime = { group = "androidx.room", name = "room-runtime", version.ref = "room" }
room-ktx = { group = "androidx.room", name = "room-ktx", version.ref = "room" }
room-compiler = { group = "androidx.room", name = "room-compiler", version.ref = "room" }
datastore-preferences = { group = "androidx.datastore", name = "datastore-preferences", version.ref = "datastore" }
gson = { group = "com.google.code.gson", name = "gson", version.ref = "gson" }
```

- [ ] **Step 2: Add dependencies to build.gradle.kts**

Add to `android/app/build.gradle.kts`:

In plugins block, add:
```kotlin
id("com.google.devtools.ksp") version "2.0.21-1.0.27"
```

In dependencies block, add:
```kotlin
implementation(libs.room.runtime)
implementation(libs.room.ktx)
ksp(libs.room.compiler)
implementation(libs.datastore.preferences)
implementation(libs.gson)
```

- [ ] **Step 3: Sync and build**

Run: `cd android && ./gradlew assembleDebug`

- [ ] **Step 4: Commit**

```bash
git add android/gradle/libs.versions.toml android/app/build.gradle.kts
git commit -m "feat(android): add Room, DataStore, and Gson dependencies"
```

---

### Task 2: Room Entities and DAOs

**Files:**
- Create: `data/local/entity/ContactEntity.kt`
- Create: `data/local/entity/MessageEntity.kt`
- Create: `data/local/entity/BlacklistEntity.kt`
- Create: `data/local/dao/ContactDao.kt`
- Create: `data/local/dao/MessageDao.kt`
- Create: `data/local/dao/BlacklistDao.kt`

- [ ] **Step 1: Create Contact entity and DAO**

`data/local/entity/ContactEntity.kt`:
```kotlin
package com.stegoapp.app.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "contacts")
data class ContactEntity(
    @PrimaryKey val userId: String,
    val username: String,
    val nickname: String = "",
    val status: String = "accepted", // "pending" | "accepted"
    val addedAt: String = ""
)
```

`data/local/dao/ContactDao.kt`:
```kotlin
package com.stegoapp.app.data.local.dao

import androidx.room.*
import com.stegoapp.app.data.local.entity.ContactEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ContactDao {
    @Query("SELECT * FROM contacts ORDER BY addedAt DESC")
    fun getAll(): Flow<List<ContactEntity>>

    @Query("SELECT * FROM contacts WHERE userId = :userId")
    suspend fun getById(userId: String): ContactEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(contact: ContactEntity)

    @Delete
    suspend fun delete(contact: ContactEntity)

    @Query("DELETE FROM contacts WHERE userId = :userId")
    suspend fun deleteById(userId: String)

    @Query("DELETE FROM contacts")
    suspend fun deleteAll()
}
```

- [ ] **Step 2: Create Message entity and DAO**

`data/local/entity/MessageEntity.kt`:
```kotlin
package com.stegoapp.app.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "messages")
data class MessageEntity(
    @PrimaryKey val id: String,
    val contactId: String,
    val direction: String, // "sent" | "received"
    val content: String,
    val contentType: String = "text", // "text" | "stego"
    val stegoImage: String? = null,
    val status: String = "sending", // "sending"|"sent"|"delivered"|"read"|"failed"
    val burnAfter: Int = 0,
    val burned: Boolean = false,
    val revoked: Boolean = false,
    val createdAt: String = ""
)
```

`data/local/dao/MessageDao.kt`:
```kotlin
package com.stegoapp.app.data.local.dao

import androidx.room.*
import com.stegoapp.app.data.local.entity.MessageEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface MessageDao {
    @Query("SELECT * FROM messages WHERE contactId = :contactId ORDER BY createdAt ASC")
    fun getByContact(contactId: String): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE id = :id")
    suspend fun getById(id: String): MessageEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(message: MessageEntity)

    @Query("UPDATE messages SET status = :status WHERE id = :id")
    suspend fun updateStatus(id: String, status: String)

    @Query("UPDATE messages SET revoked = 1, content = '' WHERE id = :id")
    suspend fun markRevoked(id: String)

    @Query("DELETE FROM messages WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM messages")
    suspend fun deleteAll()

    // For chat list: get last message per contact
    @Query("""
        SELECT * FROM messages WHERE id IN (
            SELECT id FROM messages GROUP BY contactId
            HAVING createdAt = MAX(createdAt)
        )
    """)
    fun getLastMessages(): Flow<List<MessageEntity>>
}
```

- [ ] **Step 3: Create Blacklist entity and DAO**

`data/local/entity/BlacklistEntity.kt`:
```kotlin
package com.stegoapp.app.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "blacklist")
data class BlacklistEntity(
    @PrimaryKey val userId: String,
    val username: String,
    val blockedAt: String = ""
)
```

`data/local/dao/BlacklistDao.kt`:
```kotlin
package com.stegoapp.app.data.local.dao

import androidx.room.*
import com.stegoapp.app.data.local.entity.BlacklistEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface BlacklistDao {
    @Query("SELECT * FROM blacklist")
    fun getAll(): Flow<List<BlacklistEntity>>

    @Query("SELECT COUNT(*) > 0 FROM blacklist WHERE userId = :userId")
    suspend fun isBlacklisted(userId: String): Boolean

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entry: BlacklistEntity)

    @Query("DELETE FROM blacklist WHERE userId = :userId")
    suspend fun deleteById(userId: String)

    @Query("DELETE FROM blacklist")
    suspend fun deleteAll()
}
```

- [ ] **Step 4: Commit**

```bash
git add android/app/src/main/java/com/stegoapp/app/data/local/
git commit -m "feat(android): add Room entities and DAOs for contacts, messages, blacklist"
```

---

### Task 3: Room Database and Token Store

**Files:**
- Create: `data/local/AppDatabase.kt`
- Create: `data/local/TokenStore.kt`

- [ ] **Step 1: Create AppDatabase**

```kotlin
package com.stegoapp.app.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.stegoapp.app.data.local.dao.BlacklistDao
import com.stegoapp.app.data.local.dao.ContactDao
import com.stegoapp.app.data.local.dao.MessageDao
import com.stegoapp.app.data.local.entity.BlacklistEntity
import com.stegoapp.app.data.local.entity.ContactEntity
import com.stegoapp.app.data.local.entity.MessageEntity

@Database(
    entities = [ContactEntity::class, MessageEntity::class, BlacklistEntity::class],
    version = 1,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun contactDao(): ContactDao
    abstract fun messageDao(): MessageDao
    abstract fun blacklistDao(): BlacklistDao

    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "stego-app.db"
                ).build().also { INSTANCE = it }
            }
    }
}
```

- [ ] **Step 2: Create TokenStore**

```kotlin
package com.stegoapp.app.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "auth")

class TokenStore(private val context: Context) {
    companion object {
        private val TOKEN_KEY = stringPreferencesKey("jwt_token")
        private val USER_ID_KEY = stringPreferencesKey("user_id")
        private val USERNAME_KEY = stringPreferencesKey("username")
    }

    val token: Flow<String?> = context.dataStore.data.map { it[TOKEN_KEY] }
    val userId: Flow<String?> = context.dataStore.data.map { it[USER_ID_KEY] }
    val username: Flow<String?> = context.dataStore.data.map { it[USERNAME_KEY] }

    suspend fun save(token: String, userId: String, username: String) {
        context.dataStore.edit {
            it[TOKEN_KEY] = token
            it[USER_ID_KEY] = userId
            it[USERNAME_KEY] = username
        }
    }

    suspend fun clear() {
        context.dataStore.edit { it.clear() }
    }
}
```

- [ ] **Step 3: Commit**

```bash
git add android/app/src/main/java/com/stegoapp/app/data/local/AppDatabase.kt
git add android/app/src/main/java/com/stegoapp/app/data/local/TokenStore.kt
git commit -m "feat(android): add Room database and DataStore token storage"
```

---

## Chunk 2: API Layer

### Task 4: Auth and Invite API Interfaces

**Files:**
- Create: `api/AuthApi.kt`
- Create: `api/InviteApi.kt`
- Modify: `api/ApiResponse.kt`

- [ ] **Step 1: Add response types to ApiResponse.kt**

Append to `api/ApiResponse.kt`:
```kotlin
data class AuthResponse(
    val user_id: String,
    val username: String,
    val token: String
)

data class UserInfoResponse(
    val user_id: String,
    val username: String,
    val created_at: String? = null
)

data class InviteCodeResponse(
    val code: String,
    val link: String,
    val created_at: String?
)

data class InviteLookupResponse(
    val user_id: String,
    val username: String
)

data class AuthRequest(
    val username: String,
    val password: String
)

data class MessageResponse(
    val message: String
)
```

- [ ] **Step 2: Create AuthApi**

`api/AuthApi.kt`:
```kotlin
package com.stegoapp.app.api

import retrofit2.http.*

interface AuthApi {
    @POST("/api/v1/auth/register")
    suspend fun register(@Body request: AuthRequest): AuthResponse

    @POST("/api/v1/auth/login")
    suspend fun login(@Body request: AuthRequest): AuthResponse

    @GET("/api/v1/auth/me")
    suspend fun me(): UserInfoResponse

    @POST("/api/v1/auth/logout")
    suspend fun logout(): MessageResponse
}
```

- [ ] **Step 3: Create InviteApi**

`api/InviteApi.kt`:
```kotlin
package com.stegoapp.app.api

import retrofit2.http.*

interface InviteApi {
    @GET("/api/v1/invite/my-code")
    suspend fun getMyCode(): InviteCodeResponse

    @POST("/api/v1/invite/reset")
    suspend fun resetCode(): InviteCodeResponse

    @GET("/api/v1/invite/{code}")
    suspend fun lookupCode(@Path("code") code: String): InviteLookupResponse
}
```

- [ ] **Step 4: Commit**

```bash
git add android/app/src/main/java/com/stegoapp/app/api/
git commit -m "feat(android): add auth and invite API interfaces"
```

---

### Task 5: Update ApiClient with Auth Interceptor

**Files:**
- Modify: `api/ApiClient.kt`

- [ ] **Step 1: Rewrite ApiClient with auth interceptor**

Replace `api/ApiClient.kt` with:
```kotlin
package com.stegoapp.app.api

import android.content.Context
import com.stegoapp.app.data.local.TokenStore
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

object ApiClient {
    private const val BASE_URL = "http://10.0.2.2:8000/"

    private var tokenStore: TokenStore? = null
    private var retrofit: Retrofit? = null

    fun init(context: Context) {
        tokenStore = TokenStore(context)
        retrofit = createRetrofit()
    }

    private fun createRetrofit(): Retrofit {
        val logging = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BODY
        }

        val authInterceptor = Interceptor { chain ->
            val token = runBlocking { tokenStore?.token?.firstOrNull() }
            val request = if (token != null) {
                chain.request().newBuilder()
                    .addHeader("Authorization", "Bearer $token")
                    .build()
            } else {
                chain.request()
            }
            chain.proceed(request)
        }

        val client = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(300, TimeUnit.SECONDS)
            .writeTimeout(300, TimeUnit.SECONDS)
            .addInterceptor(authInterceptor)
            .addInterceptor(logging)
            .build()

        return Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
    }

    val stegoApi: StegoApi by lazy { retrofit!!.create(StegoApi::class.java) }
    val authApi: AuthApi by lazy { retrofit!!.create(AuthApi::class.java) }
    val inviteApi: InviteApi by lazy { retrofit!!.create(InviteApi::class.java) }

    fun getOkHttpClient(): OkHttpClient {
        return OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(300, TimeUnit.SECONDS)
            .build()
    }

    fun getBaseUrl(): String = BASE_URL
    fun getTokenStore(): TokenStore? = tokenStore
}
```

- [ ] **Step 2: Commit**

```bash
git add android/app/src/main/java/com/stegoapp/app/api/ApiClient.kt
git commit -m "feat(android): add auth interceptor to ApiClient"
```

---

### Task 6: WebSocket Client

**Files:**
- Create: `data/remote/WsClient.kt`

- [ ] **Step 1: Create OkHttp WebSocket client**

```kotlin
package com.stegoapp.app.data.remote

import com.google.gson.Gson
import com.stegoapp.app.api.ApiClient
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import okhttp3.*
import java.util.concurrent.TimeUnit

data class WsMessage(
    val type: String,
    val id: String? = null,
    val timestamp: Long? = null,
    val payload: Map<String, Any?>? = null
)

class WsClient {
    private var ws: WebSocket? = null
    private val gson = Gson()
    private var shouldReconnect = false
    private var reconnectAttempts = 0
    private var token: String = ""

    private val _messages = MutableSharedFlow<WsMessage>(extraBufferCapacity = 64)
    val messages = _messages.asSharedFlow()

    private val _connected = MutableSharedFlow<Boolean>(extraBufferCapacity = 8)
    val connected = _connected.asSharedFlow()

    fun connect(token: String) {
        this.token = token
        shouldReconnect = true
        reconnectAttempts = 0
        doConnect()
    }

    private fun doConnect() {
        val baseUrl = ApiClient.getBaseUrl()
            .replace("http://", "ws://")
            .replace("https://", "wss://")
        val url = "${baseUrl}ws?token=$token"

        val client = OkHttpClient.Builder()
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .build()

        val request = Request.Builder().url(url).build()

        ws = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                reconnectAttempts = 0
                _connected.tryEmit(true)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val msg = gson.fromJson(text, WsMessage::class.java)
                    _messages.tryEmit(msg)
                } catch (_: Exception) {}
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                _connected.tryEmit(false)
                scheduleReconnect()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                _connected.tryEmit(false)
                scheduleReconnect()
            }
        })
    }

    private fun scheduleReconnect() {
        if (!shouldReconnect || reconnectAttempts >= 10) return
        val delay = minOf(1000L * (1 shl reconnectAttempts), 30000L)
        reconnectAttempts++
        Thread {
            Thread.sleep(delay)
            if (shouldReconnect) doConnect()
        }.start()
    }

    fun send(message: WsMessage) {
        ws?.send(gson.toJson(message))
    }

    fun disconnect() {
        shouldReconnect = false
        ws?.close(1000, "Bye")
        ws = null
    }

    companion object {
        val instance = WsClient()
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add android/app/src/main/java/com/stegoapp/app/data/remote/
git commit -m "feat(android): add OkHttp WebSocket client"
```

---

## Chunk 3: ViewModels

### Task 7: Auth ViewModel

**Files:**
- Create: `ui/viewmodel/AuthViewModel.kt`

- [ ] **Step 1: Create AuthViewModel**

```kotlin
package com.stegoapp.app.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.stegoapp.app.api.ApiClient
import com.stegoapp.app.api.AuthRequest
import com.stegoapp.app.data.local.AppDatabase
import com.stegoapp.app.data.local.TokenStore
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class AuthViewModel(app: Application) : AndroidViewModel(app) {
    private val tokenStore = TokenStore(app)

    val isAuthenticated: StateFlow<Boolean> = tokenStore.token
        .map { it != null }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val userId: StateFlow<String?> = tokenStore.userId
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val username: StateFlow<String?> = tokenStore.username
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading

    fun login(username: String, password: String, onSuccess: () -> Unit) {
        viewModelScope.launch {
            _loading.value = true
            _error.value = null
            try {
                val res = ApiClient.authApi.login(AuthRequest(username, password))
                tokenStore.save(res.token, res.user_id, res.username)
                onSuccess()
            } catch (e: Exception) {
                _error.value = e.message ?: "Login failed"
            } finally {
                _loading.value = false
            }
        }
    }

    fun register(username: String, password: String, onSuccess: () -> Unit) {
        viewModelScope.launch {
            _loading.value = true
            _error.value = null
            try {
                val res = ApiClient.authApi.register(AuthRequest(username, password))
                tokenStore.save(res.token, res.user_id, res.username)
                onSuccess()
            } catch (e: Exception) {
                _error.value = e.message ?: "Registration failed"
            } finally {
                _loading.value = false
            }
        }
    }

    fun logout(onDone: () -> Unit) {
        viewModelScope.launch {
            tokenStore.clear()
            val db = AppDatabase.getInstance(getApplication())
            db.contactDao().deleteAll()
            db.messageDao().deleteAll()
            db.blacklistDao().deleteAll()
            onDone()
        }
    }

    fun clearError() { _error.value = null }
}
```

- [ ] **Step 2: Commit**

```bash
git add android/app/src/main/java/com/stegoapp/app/ui/viewmodel/AuthViewModel.kt
git commit -m "feat(android): add auth view model"
```

---

### Task 8: Contact and Chat ViewModels

**Files:**
- Create: `ui/viewmodel/ContactViewModel.kt`
- Create: `ui/viewmodel/ChatViewModel.kt`

- [ ] **Step 1: Create ContactViewModel**

```kotlin
package com.stegoapp.app.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.stegoapp.app.api.ApiClient
import com.stegoapp.app.data.local.AppDatabase
import com.stegoapp.app.data.local.entity.ContactEntity
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class ContactViewModel(app: Application) : AndroidViewModel(app) {
    private val db = AppDatabase.getInstance(app)
    private val contactDao = db.contactDao()

    val contacts: StateFlow<List<ContactEntity>> = contactDao.getAll()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    fun addContactByCode(code: String, onSuccess: (ContactEntity) -> Unit) {
        viewModelScope.launch {
            try {
                val info = ApiClient.inviteApi.lookupCode(code)
                val existing = contactDao.getById(info.user_id)
                if (existing != null) {
                    onSuccess(existing)
                    return@launch
                }
                val contact = ContactEntity(
                    userId = info.user_id,
                    username = info.username,
                    status = "accepted",
                    addedAt = System.currentTimeMillis().toString()
                )
                contactDao.insert(contact)
                onSuccess(contact)
            } catch (e: Exception) {
                _error.value = e.message ?: "Failed to add contact"
            }
        }
    }

    fun acceptContact(userId: String, username: String) {
        viewModelScope.launch {
            contactDao.insert(ContactEntity(
                userId = userId,
                username = username,
                status = "accepted",
                addedAt = System.currentTimeMillis().toString()
            ))
        }
    }

    fun removeContact(userId: String) {
        viewModelScope.launch { contactDao.deleteById(userId) }
    }

    fun clearError() { _error.value = null }
}
```

- [ ] **Step 2: Create ChatViewModel**

```kotlin
package com.stegoapp.app.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.stegoapp.app.data.local.AppDatabase
import com.stegoapp.app.data.local.entity.MessageEntity
import com.stegoapp.app.data.remote.WsClient
import com.stegoapp.app.data.remote.WsMessage
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
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
        val message: WsMessage
    )

    fun getMessages(contactId: String): Flow<List<MessageEntity>> {
        return messageDao.getByContact(contactId)
    }

    fun getLastMessages(): Flow<List<MessageEntity>> {
        return messageDao.getLastMessages()
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
            _pendingRequests.value = _pendingRequests.value + PendingRequest(
                userId = fromUserId,
                username = fromUsername,
                message = msg
            )
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
            // Process the pending message
            handleChat(request.message)
            _pendingRequests.value = _pendingRequests.value.filter { it.userId != request.userId }
        }
    }

    fun rejectPendingRequest(request: PendingRequest) {
        _pendingRequests.value = _pendingRequests.value.filter { it.userId != request.userId }
    }
}
```

- [ ] **Step 3: Commit**

```bash
git add android/app/src/main/java/com/stegoapp/app/ui/viewmodel/
git commit -m "feat(android): add contact and chat view models"
```

---

## Chunk 4: UI Screens

### Task 9: Auth Screens

**Files:**
- Create: `ui/screens/auth/LoginScreen.kt`
- Create: `ui/screens/auth/RegisterScreen.kt`

- [ ] **Step 1: Create LoginScreen**

```kotlin
package com.stegoapp.app.ui.screens.auth

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.stegoapp.app.ui.viewmodel.AuthViewModel

@Composable
fun LoginScreen(
    authViewModel: AuthViewModel,
    onLoginSuccess: () -> Unit,
    onNavigateToRegister: () -> Unit
) {
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    val error by authViewModel.error.collectAsState()
    val loading by authViewModel.loading.collectAsState()

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Login", style = MaterialTheme.typography.headlineLarge)
        Spacer(modifier = Modifier.height(32.dp))

        OutlinedTextField(
            value = username,
            onValueChange = { username = it },
            label = { Text("Username") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        Spacer(modifier = Modifier.height(12.dp))

        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("Password") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password)
        )
        Spacer(modifier = Modifier.height(8.dp))

        if (error != null) {
            Text(error!!, color = MaterialTheme.colorScheme.error)
            Spacer(modifier = Modifier.height(8.dp))
        }

        Button(
            onClick = { authViewModel.login(username, password, onLoginSuccess) },
            modifier = Modifier.fillMaxWidth(),
            enabled = !loading && username.isNotBlank() && password.length >= 6
        ) {
            if (loading) CircularProgressIndicator(modifier = Modifier.size(20.dp))
            else Text("Login")
        }
        Spacer(modifier = Modifier.height(16.dp))

        TextButton(onClick = onNavigateToRegister) {
            Text("Don't have an account? Register")
        }
    }
}
```

- [ ] **Step 2: Create RegisterScreen**

```kotlin
package com.stegoapp.app.ui.screens.auth

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.stegoapp.app.ui.viewmodel.AuthViewModel

@Composable
fun RegisterScreen(
    authViewModel: AuthViewModel,
    onRegisterSuccess: () -> Unit,
    onNavigateToLogin: () -> Unit
) {
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    var localError by remember { mutableStateOf<String?>(null) }
    val error by authViewModel.error.collectAsState()
    val loading by authViewModel.loading.collectAsState()

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Register", style = MaterialTheme.typography.headlineLarge)
        Spacer(modifier = Modifier.height(32.dp))

        OutlinedTextField(
            value = username,
            onValueChange = { username = it },
            label = { Text("Username") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        Spacer(modifier = Modifier.height(12.dp))

        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("Password") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password)
        )
        Spacer(modifier = Modifier.height(12.dp))

        OutlinedTextField(
            value = confirmPassword,
            onValueChange = { confirmPassword = it },
            label = { Text("Confirm Password") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password)
        )
        Spacer(modifier = Modifier.height(8.dp))

        val displayError = localError ?: error
        if (displayError != null) {
            Text(displayError, color = MaterialTheme.colorScheme.error)
            Spacer(modifier = Modifier.height(8.dp))
        }

        Button(
            onClick = {
                localError = null
                if (password != confirmPassword) {
                    localError = "Passwords do not match"
                } else {
                    authViewModel.register(username, password, onRegisterSuccess)
                }
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = !loading && username.length >= 3 && password.length >= 6
        ) {
            if (loading) CircularProgressIndicator(modifier = Modifier.size(20.dp))
            else Text("Register")
        }
        Spacer(modifier = Modifier.height(16.dp))

        TextButton(onClick = onNavigateToLogin) {
            Text("Already have an account? Login")
        }
    }
}
```

- [ ] **Step 3: Commit**

```bash
git add android/app/src/main/java/com/stegoapp/app/ui/screens/auth/
git commit -m "feat(android): add login and register screens"
```

---

### Task 10: Chat Screens

**Files:**
- Create: `ui/screens/chat/ChatListScreen.kt`
- Create: `ui/screens/chat/ChatScreen.kt`

- [ ] **Step 1: Create ChatListScreen**

```kotlin
package com.stegoapp.app.ui.screens.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.stegoapp.app.data.local.entity.ContactEntity
import com.stegoapp.app.ui.viewmodel.ChatViewModel
import com.stegoapp.app.ui.viewmodel.ContactViewModel

@Composable
fun ChatListScreen(
    contactViewModel: ContactViewModel,
    chatViewModel: ChatViewModel,
    onOpenChat: (String) -> Unit
) {
    val contacts by contactViewModel.contacts.collectAsState()
    val lastMessages by chatViewModel.getLastMessages().collectAsState(initial = emptyList())
    val pendingRequests by chatViewModel.pendingRequests.collectAsState()

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(title = { Text("Messages") })

        if (pendingRequests.isNotEmpty()) {
            Card(
                modifier = Modifier.fillMaxWidth().padding(8.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
            ) {
                Text(
                    "${pendingRequests.size} friend request(s)",
                    modifier = Modifier.padding(12.dp),
                    fontWeight = FontWeight.Bold
                )
            }
        }

        if (contacts.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No conversations yet", color = Color.Gray)
            }
        } else {
            LazyColumn {
                items(contacts.filter { it.status == "accepted" }) { contact ->
                    val lastMsg = lastMessages.find { it.contactId == contact.userId }
                    ListItem(
                        headlineContent = {
                            Text(
                                contact.nickname.ifEmpty { contact.username },
                                fontWeight = FontWeight.Medium
                            )
                        },
                        supportingContent = {
                            Text(
                                lastMsg?.content ?: "No messages",
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                color = Color.Gray
                            )
                        },
                        leadingContent = {
                            Surface(
                                modifier = Modifier.size(44.dp).clip(CircleShape),
                                color = MaterialTheme.colorScheme.primary
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Text(
                                        (contact.nickname.ifEmpty { contact.username })
                                            .first().uppercase(),
                                        color = Color.White,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        },
                        modifier = Modifier.clickable { onOpenChat(contact.userId) }
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}
```

- [ ] **Step 2: Create ChatScreen**

```kotlin
package com.stegoapp.app.ui.screens.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.stegoapp.app.data.local.entity.MessageEntity
import com.stegoapp.app.ui.viewmodel.ChatViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    contactId: String,
    contactName: String,
    chatViewModel: ChatViewModel,
    currentUserId: String,
    currentUsername: String,
    onBack: () -> Unit
) {
    val messages by chatViewModel.getMessages(contactId).collectAsState(initial = emptyList())
    var inputText by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(contactName) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                }
            )
        },
        bottomBar = {
            Row(
                modifier = Modifier.fillMaxWidth().padding(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = inputText,
                    onValueChange = { inputText = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Type a message...") },
                    singleLine = true
                )
                Spacer(modifier = Modifier.width(8.dp))
                IconButton(
                    onClick = {
                        if (inputText.isNotBlank()) {
                            chatViewModel.sendTextMessage(
                                contactId, inputText.trim(), currentUserId, currentUsername
                            )
                            inputText = ""
                        }
                    }
                ) {
                    Icon(Icons.AutoMirrored.Filled.Send, "Send",
                        tint = MaterialTheme.colorScheme.primary)
                }
            }
        }
    ) { padding ->
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            items(messages) { msg ->
                MessageBubble(msg)
            }
        }
    }
}

@Composable
fun MessageBubble(message: MessageEntity) {
    val isSent = message.direction == "sent"

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isSent) Arrangement.End else Arrangement.Start
    ) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = if (isSent) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.widthIn(max = 280.dp)
        ) {
            Column(modifier = Modifier.padding(10.dp)) {
                if (message.revoked) {
                    Text(
                        "Message revoked",
                        color = if (isSent) Color.White.copy(alpha = 0.7f)
                                else Color.Gray,
                        fontStyle = androidx.compose.ui.text.font.FontStyle.Italic
                    )
                } else {
                    Text(
                        message.content,
                        color = if (isSent) Color.White
                                else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (isSent) {
                    val statusText = when (message.status) {
                        "sending" -> "..."
                        "sent" -> "✓"
                        "delivered" -> "✓✓"
                        "read" -> "✓✓"
                        "failed" -> "✗"
                        else -> ""
                    }
                    Text(
                        statusText,
                        fontSize = 10.sp,
                        color = if (message.status == "read") Color(0xFF4FC3F7)
                                else Color.White.copy(alpha = 0.6f),
                        modifier = Modifier.align(Alignment.End)
                    )
                }
            }
        }
    }
}
```

- [ ] **Step 3: Commit**

```bash
git add android/app/src/main/java/com/stegoapp/app/ui/screens/chat/
git commit -m "feat(android): add chat list and chat screens"
```

---

### Task 11: Contact and Profile Screens

**Files:**
- Create: `ui/screens/contact/ContactsScreen.kt`
- Create: `ui/screens/contact/AddContactScreen.kt`
- Create: `ui/screens/profile/ProfileScreen.kt`

- [ ] **Step 1: Create ContactsScreen**

```kotlin
package com.stegoapp.app.ui.screens.contact

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.stegoapp.app.ui.viewmodel.ContactViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactsScreen(
    contactViewModel: ContactViewModel,
    onOpenChat: (String) -> Unit,
    onAddContact: () -> Unit
) {
    val contacts by contactViewModel.contacts.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Contacts") },
                actions = {
                    IconButton(onClick = onAddContact) {
                        Icon(Icons.Default.Add, "Add contact")
                    }
                }
            )
        }
    ) { padding ->
        if (contacts.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Text("No contacts yet", color = Color.Gray)
            }
        } else {
            LazyColumn(modifier = Modifier.padding(padding)) {
                items(contacts) { contact ->
                    ListItem(
                        headlineContent = {
                            Text(contact.nickname.ifEmpty { contact.username }, fontWeight = FontWeight.Medium)
                        },
                        supportingContent = { Text("@${contact.username}", color = Color.Gray) },
                        leadingContent = {
                            Surface(
                                modifier = Modifier.size(40.dp).clip(CircleShape),
                                color = MaterialTheme.colorScheme.primary
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Text(
                                        contact.username.first().uppercase(),
                                        color = Color.White,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        },
                        modifier = Modifier.clickable { onOpenChat(contact.userId) }
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}
```

- [ ] **Step 2: Create AddContactScreen**

```kotlin
package com.stegoapp.app.ui.screens.contact

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.stegoapp.app.ui.viewmodel.ContactViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddContactScreen(
    contactViewModel: ContactViewModel,
    onBack: () -> Unit,
    onContactAdded: (String) -> Unit
) {
    var code by remember { mutableStateOf("") }
    val error by contactViewModel.error.collectAsState()
    var loading by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Add Contact") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp)
        ) {
            OutlinedTextField(
                value = code,
                onValueChange = { code = it },
                label = { Text("Invite Code") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            Spacer(modifier = Modifier.height(8.dp))

            if (error != null) {
                Text(error!!, color = MaterialTheme.colorScheme.error)
                Spacer(modifier = Modifier.height(8.dp))
            }

            Button(
                onClick = {
                    loading = true
                    contactViewModel.addContactByCode(code.trim()) { contact ->
                        loading = false
                        onContactAdded(contact.userId)
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = code.isNotBlank() && !loading
            ) {
                if (loading) CircularProgressIndicator(modifier = Modifier.size(20.dp))
                else Text("Add Contact")
            }
        }
    }
}
```

- [ ] **Step 3: Create ProfileScreen**

```kotlin
package com.stegoapp.app.ui.screens.profile

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.stegoapp.app.api.ApiClient
import com.stegoapp.app.ui.viewmodel.AuthViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(
    authViewModel: AuthViewModel,
    onLogout: () -> Unit
) {
    val username by authViewModel.username.collectAsState()
    var inviteCode by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current

    LaunchedEffect(Unit) {
        try {
            val res = ApiClient.inviteApi.getMyCode()
            inviteCode = res.code
        } catch (_: Exception) {}
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Profile") }) }
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp)
        ) {
            // Avatar and username
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    modifier = Modifier.size(60.dp).clip(CircleShape),
                    color = MaterialTheme.colorScheme.primary
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            username?.firstOrNull()?.uppercase() ?: "?",
                            color = Color.White,
                            fontSize = 24.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
                Spacer(modifier = Modifier.width(16.dp))
                Text(username ?: "", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            }

            Spacer(modifier = Modifier.height(32.dp))

            // Invite code section
            Text("My Invite Code", fontWeight = FontWeight.Medium, fontSize = 16.sp)
            Text("Share this code so friends can add you.", color = Color.Gray, fontSize = 13.sp)
            Spacer(modifier = Modifier.height(8.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = MaterialTheme.shapes.small
                ) {
                    Text(
                        inviteCode.ifEmpty { "Loading..." },
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                OutlinedButton(onClick = {
                    clipboard.setText(AnnotatedString(inviteCode))
                }) {
                    Text("Copy")
                }
                Spacer(modifier = Modifier.width(4.dp))
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            loading = true
                            try {
                                val res = ApiClient.inviteApi.resetCode()
                                inviteCode = res.code
                            } catch (_: Exception) {}
                            loading = false
                        }
                    },
                    enabled = !loading
                ) {
                    Text("Reset")
                }
            }

            Spacer(modifier = Modifier.weight(1f))

            Button(
                onClick = { authViewModel.logout(onLogout) },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error
                )
            ) {
                Text("Logout")
            }
        }
    }
}
```

- [ ] **Step 4: Commit**

```bash
git add android/app/src/main/java/com/stegoapp/app/ui/screens/contact/
git add android/app/src/main/java/com/stegoapp/app/ui/screens/profile/
git commit -m "feat(android): add contact and profile screens"
```

---

## Chunk 5: Navigation and Integration

### Task 12: Update Navigation and MainActivity

**Files:**
- Modify: `ui/navigation/NavGraph.kt`
- Modify: `MainActivity.kt`

- [ ] **Step 1: Update NavGraph with all routes**

Replace `ui/navigation/NavGraph.kt` with:
```kotlin
package com.stegoapp.app.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import com.stegoapp.app.ui.screens.EmbedScreen
import com.stegoapp.app.ui.screens.ExtractScreen
import com.stegoapp.app.ui.screens.auth.LoginScreen
import com.stegoapp.app.ui.screens.auth.RegisterScreen
import com.stegoapp.app.ui.screens.chat.ChatListScreen
import com.stegoapp.app.ui.screens.chat.ChatScreen
import com.stegoapp.app.ui.screens.contact.AddContactScreen
import com.stegoapp.app.ui.screens.contact.ContactsScreen
import com.stegoapp.app.ui.screens.profile.ProfileScreen
import com.stegoapp.app.ui.viewmodel.AuthViewModel
import com.stegoapp.app.ui.viewmodel.ChatViewModel
import com.stegoapp.app.ui.viewmodel.ContactViewModel

sealed class Screen(val route: String) {
    object Login : Screen("login")
    object Register : Screen("register")
    object ChatList : Screen("chats")
    object Chat : Screen("chat/{contactId}/{contactName}") {
        fun createRoute(contactId: String, contactName: String) =
            "chat/$contactId/$contactName"
    }
    object Contacts : Screen("contacts")
    object AddContact : Screen("contacts/add")
    object Profile : Screen("profile")
    object Embed : Screen("embed")
    object Extract : Screen("extract")
}

@Composable
fun NavGraph(
    navController: NavHostController,
    startDestination: String,
    authViewModel: AuthViewModel,
    chatViewModel: ChatViewModel,
    contactViewModel: ContactViewModel,
    currentUserId: String,
    currentUsername: String
) {
    NavHost(navController = navController, startDestination = startDestination) {
        composable(Screen.Login.route) {
            LoginScreen(
                authViewModel = authViewModel,
                onLoginSuccess = {
                    navController.navigate(Screen.ChatList.route) {
                        popUpTo(Screen.Login.route) { inclusive = true }
                    }
                },
                onNavigateToRegister = { navController.navigate(Screen.Register.route) }
            )
        }
        composable(Screen.Register.route) {
            RegisterScreen(
                authViewModel = authViewModel,
                onRegisterSuccess = {
                    navController.navigate(Screen.ChatList.route) {
                        popUpTo(Screen.Register.route) { inclusive = true }
                    }
                },
                onNavigateToLogin = { navController.popBackStack() }
            )
        }
        composable(Screen.ChatList.route) {
            ChatListScreen(
                contactViewModel = contactViewModel,
                chatViewModel = chatViewModel,
                onOpenChat = { userId ->
                    val name = contactViewModel.contacts.value
                        .find { it.userId == userId }?.let {
                            it.nickname.ifEmpty { it.username }
                        } ?: "Chat"
                    navController.navigate(Screen.Chat.createRoute(userId, name))
                }
            )
        }
        composable(Screen.Chat.route) { backStackEntry ->
            val contactId = backStackEntry.arguments?.getString("contactId") ?: return@composable
            val contactName = backStackEntry.arguments?.getString("contactName") ?: "Chat"
            ChatScreen(
                contactId = contactId,
                contactName = contactName,
                chatViewModel = chatViewModel,
                currentUserId = currentUserId,
                currentUsername = currentUsername,
                onBack = { navController.popBackStack() }
            )
        }
        composable(Screen.Contacts.route) {
            ContactsScreen(
                contactViewModel = contactViewModel,
                onOpenChat = { userId ->
                    val name = contactViewModel.contacts.value
                        .find { it.userId == userId }?.let {
                            it.nickname.ifEmpty { it.username }
                        } ?: "Chat"
                    navController.navigate(Screen.Chat.createRoute(userId, name))
                },
                onAddContact = { navController.navigate(Screen.AddContact.route) }
            )
        }
        composable(Screen.AddContact.route) {
            AddContactScreen(
                contactViewModel = contactViewModel,
                onBack = { navController.popBackStack() },
                onContactAdded = { userId ->
                    navController.popBackStack()
                    val name = contactViewModel.contacts.value
                        .find { it.userId == userId }?.username ?: "Chat"
                    navController.navigate(Screen.Chat.createRoute(userId, name))
                }
            )
        }
        composable(Screen.Profile.route) {
            ProfileScreen(
                authViewModel = authViewModel,
                onLogout = {
                    navController.navigate(Screen.Login.route) {
                        popUpTo(0) { inclusive = true }
                    }
                }
            )
        }
        composable(Screen.Embed.route) { EmbedScreen() }
        composable(Screen.Extract.route) { ExtractScreen() }
    }
}
```

- [ ] **Step 2: Update MainActivity**

Replace `MainActivity.kt` with:
```kotlin
package com.stegoapp.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.lifecycleScope
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.stegoapp.app.api.ApiClient
import com.stegoapp.app.ui.navigation.NavGraph
import com.stegoapp.app.ui.navigation.Screen
import com.stegoapp.app.ui.theme.StegoAppTheme
import com.stegoapp.app.ui.viewmodel.AuthViewModel
import com.stegoapp.app.ui.viewmodel.ChatViewModel
import com.stegoapp.app.ui.viewmodel.ContactViewModel
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val authViewModel: AuthViewModel by viewModels()
    private val chatViewModel: ChatViewModel by viewModels()
    private val contactViewModel: ContactViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        ApiClient.init(this)

        setContent {
            StegoAppTheme {
                MainApp(authViewModel, chatViewModel, contactViewModel)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        chatViewModel.disconnectWebSocket()
    }
}

data class NavItem(val route: String, val label: String, val icon: ImageVector)

@Composable
fun MainApp(
    authViewModel: AuthViewModel,
    chatViewModel: ChatViewModel,
    contactViewModel: ContactViewModel
) {
    val navController = rememberNavController()
    val isAuthenticated by authViewModel.isAuthenticated.collectAsState()
    val userId by authViewModel.userId.collectAsState()
    val username by authViewModel.username.collectAsState()

    // Connect WebSocket when authenticated
    LaunchedEffect(isAuthenticated) {
        if (isAuthenticated) {
            val token = com.stegoapp.app.data.local.TokenStore(
                navController.context
            ).token.firstOrNull()
            if (token != null) {
                chatViewModel.connectWebSocket(token)
            }
        }
    }

    val startDestination = if (isAuthenticated) Screen.ChatList.route else Screen.Login.route

    val navItems = listOf(
        NavItem(Screen.ChatList.route, "Messages", Icons.Default.Email),
        NavItem(Screen.Contacts.route, "Contacts", Icons.Default.Person),
        NavItem(Screen.Embed.route, "Stego", Icons.Default.Lock),
        NavItem(Screen.Profile.route, "Profile", Icons.Default.AccountCircle),
    )

    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    val showBottomBar = isAuthenticated && currentRoute in navItems.map { it.route }

    Scaffold(
        bottomBar = {
            if (showBottomBar) {
                NavigationBar {
                    navItems.forEach { item ->
                        NavigationBarItem(
                            icon = { Icon(item.icon, contentDescription = item.label) },
                            label = { Text(item.label) },
                            selected = currentRoute == item.route,
                            onClick = {
                                navController.navigate(item.route) {
                                    popUpTo(Screen.ChatList.route) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            }
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        Box(modifier = Modifier.padding(innerPadding)) {
            NavGraph(
                navController = navController,
                startDestination = startDestination,
                authViewModel = authViewModel,
                chatViewModel = chatViewModel,
                contactViewModel = contactViewModel,
                currentUserId = userId ?: "",
                currentUsername = username ?: ""
            )
        }
    }
}
```

- [ ] **Step 3: Build and verify**

Run: `cd android && ./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add android/app/src/main/java/com/stegoapp/app/ui/navigation/NavGraph.kt
git add android/app/src/main/java/com/stegoapp/app/MainActivity.kt
git commit -m "feat(android): update navigation with auth, chat, contacts, and profile"
```

---

## Summary

After completing all tasks, the Android app will have:

| Screen | Route |
|--------|-------|
| Login | `login` |
| Register | `register` |
| Chat list | `chats` |
| Chat conversation | `chat/{id}/{name}` |
| Contacts | `contacts` |
| Add contact | `contacts/add` |
| Profile & invite | `profile` |
| Stego embed (existing) | `embed` |
| Stego extract (existing) | `extract` |

Bottom navigation: Messages | Contacts | Stego | Profile
