# 可证安全图像隐写系统 Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 构建一个跨平台图像隐写演示系统，包含 Python 后端 API 和 Android/Web 前端

**Architecture:** Monorepo 结构，Python FastAPI 提供硬编码演示 API，Android Kotlin 和 Vue.js 前端分别调用 API 实现嵌入/提取功能

**Tech Stack:** Python 3.11 + FastAPI, Kotlin + Retrofit, Vue.js 3 + TypeScript + Axios

**Spec:** `docs/superpowers/specs/2026-03-24-stego-system-design.md`

---

## Chunk 1: Python Server

### Task 1: 项目初始化

**Files:**
- Create: `server/requirements.txt`
- Create: `server/main.py`
- Create: `server/demo_assets/.gitkeep`

- [ ] **Step 1: 创建 server 目录结构**

```bash
mkdir -p server/api/v1 server/demo_assets
```

- [ ] **Step 2: 创建 requirements.txt**

```text
fastapi==0.109.0
uvicorn[standard]==0.27.0
python-multipart==0.0.6
pillow==10.2.0
```

- [ ] **Step 3: 创建 main.py**

```python
from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware

app = FastAPI(title="Stego API", version="0.1.0")

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

@app.get("/health")
async def health_check():
    return {"status": "ok"}

if __name__ == "__main__":
    import uvicorn
    uvicorn.run(app, host="0.0.0.0", port=8000)
```

- [ ] **Step 4: 创建占位图像**

下载或创建一个 512x512 PNG 图像，保存为 `server/demo_assets/stego_demo.png`

- [ ] **Step 5: 验证服务启动**

```bash
cd server && pip install -r requirements.txt && python main.py
```

访问 http://localhost:8000/health 应返回 `{"status": "ok"}`

- [ ] **Step 6: Commit**

```bash
git add server/
git commit -m "feat(server): init FastAPI project structure"
```

---

### Task 2: 实现 embed API

**Files:**
- Create: `server/api/__init__.py`
- Create: `server/api/v1/__init__.py`
- Create: `server/api/v1/stego.py`
- Modify: `server/main.py`

- [ ] **Step 1: 创建 api 模块初始化文件**

`server/api/__init__.py` 和 `server/api/v1/__init__.py` 为空文件

- [ ] **Step 2: 创建 stego.py 路由**

```python
from fastapi import APIRouter, UploadFile, File, Form, HTTPException
from fastapi.responses import JSONResponse
import base64
import os

router = APIRouter(prefix="/api/v1/stego", tags=["Steganography"])

DEMO_STEGO_PATH = os.path.join(os.path.dirname(__file__), "../../demo_assets/stego_demo.png")

@router.post("/embed")
async def embed_message(
    cover_image: UploadFile = File(...),
    secret_message: str = Form(...),
    key: str = Form(...),
    embed_rate: float = Form(0.5)
):
    """
    消息嵌入接口（演示阶段返回占位图）
    """
    if not cover_image.content_type.startswith("image/"):
        raise HTTPException(status_code=400, detail="请上传有效的图像文件")

    if len(secret_message) == 0:
        raise HTTPException(status_code=400, detail="秘密消息不能为空")

    if not os.path.exists(DEMO_STEGO_PATH):
        raise HTTPException(status_code=500, detail="演示资源未找到")

    with open(DEMO_STEGO_PATH, "rb") as f:
        stego_base64 = base64.b64encode(f.read()).decode("utf-8")

    return JSONResponse(content={
        "status": "success",
        "stego_image": f"data:image/png;base64,{stego_base64}",
        "is_demo": True
    })
```

- [ ] **Step 3: 在 main.py 中注册路由**

```python
from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware
from api.v1.stego import router as stego_router

app = FastAPI(title="Stego API", version="0.1.0")

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

app.include_router(stego_router)

@app.get("/health")
async def health_check():
    return {"status": "ok"}

if __name__ == "__main__":
    import uvicorn
    uvicorn.run(app, host="0.0.0.0", port=8000)
```

- [ ] **Step 4: 测试 embed API**

```bash
curl -X POST http://localhost:8000/api/v1/stego/embed \
  -F "cover_image=@test.png" \
  -F "secret_message=hello" \
  -F "key=test123" \
  -F "embed_rate=0.5"
```

预期返回包含 `status: success` 和 `stego_image` 的 JSON

- [ ] **Step 5: Commit**

```bash
git add server/
git commit -m "feat(server): add embed API endpoint"
```

---

### Task 3: 实现 extract API

**Files:**
- Modify: `server/api/v1/stego.py`

- [ ] **Step 1: 在 stego.py 中添加 extract 端点**

```python
@router.post("/extract")
async def extract_message(
    stego_image: UploadFile = File(...),
    key: str = Form(...)
):
    """
    消息提取接口（演示阶段返回固定消息）
    """
    if not stego_image.content_type.startswith("image/"):
        raise HTTPException(status_code=400, detail="请上传有效的图像文件")

    return JSONResponse(content={
        "status": "success",
        "secret_message": "演示提取消息 - Hello from Demo!",
        "is_demo": True
    })
```

- [ ] **Step 2: 测试 extract API**

```bash
curl -X POST http://localhost:8000/api/v1/stego/extract \
  -F "stego_image=@test.png" \
  -F "key=test123"
```

预期返回 `{"status": "success", "secret_message": "演示提取消息 - Hello from Demo!", "is_demo": true}`

- [ ] **Step 3: Commit**

```bash
git add server/
git commit -m "feat(server): add extract API endpoint"
```

---

## Chunk 2: Android App

### Task 4: 创建 Android 项目

**Files:**
- Create: `android/` (Android Studio 项目)

- [ ] **Step 1: 使用 Android Studio 创建项目**

- 项目名称: `StegoApp`
- Package: `com.stego.app`
- 语言: Kotlin
- Minimum SDK: API 24 (Android 7.0)
- 模板: Empty Activity

将项目创建在 `bishe/android/` 目录

- [ ] **Step 2: 添加依赖到 build.gradle.kts (app)**

```kotlin
dependencies {
    // Retrofit
    implementation("com.squareup.retrofit2:retrofit:2.9.0")
    implementation("com.squareup.retrofit2:converter-gson:2.9.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // ViewModel
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")

    // Activity Result API
    implementation("androidx.activity:activity-ktx:1.8.2")

    // 其他默认依赖...
}
```

- [ ] **Step 3: 添加网络权限到 AndroidManifest.xml**

```xml
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.READ_EXTERNAL_STORAGE" />
<uses-permission android:name="android.permission.READ_MEDIA_IMAGES" />

<application
    android:usesCleartextTraffic="true"
    ...>
```

- [ ] **Step 4: Sync Gradle 并确保编译通过**

- [ ] **Step 5: Commit**

```bash
git add android/
git commit -m "feat(android): init Android project with dependencies"
```

---

### Task 5: 创建 API 服务层

**Files:**
- Create: `android/app/src/main/java/com/stego/app/api/StegoApi.kt`
- Create: `android/app/src/main/java/com/stego/app/api/ApiClient.kt`
- Create: `android/app/src/main/java/com/stego/app/model/ApiResponse.kt`

- [ ] **Step 1: 创建 ApiResponse 数据类**

```kotlin
package com.stego.app.model

data class EmbedResponse(
    val status: String,
    val stego_image: String?,
    val is_demo: Boolean
)

data class ExtractResponse(
    val status: String,
    val secret_message: String?,
    val is_demo: Boolean
)
```

- [ ] **Step 2: 创建 StegoApi 接口**

```kotlin
package com.stego.app.api

import com.stego.app.model.EmbedResponse
import com.stego.app.model.ExtractResponse
import okhttp3.MultipartBody
import okhttp3.RequestBody
import retrofit2.Response
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part

interface StegoApi {
    @Multipart
    @POST("/api/v1/stego/embed")
    suspend fun embed(
        @Part cover_image: MultipartBody.Part,
        @Part("secret_message") message: RequestBody,
        @Part("key") key: RequestBody,
        @Part("embed_rate") embedRate: RequestBody
    ): Response<EmbedResponse>

    @Multipart
    @POST("/api/v1/stego/extract")
    suspend fun extract(
        @Part stego_image: MultipartBody.Part,
        @Part("key") key: RequestBody
    ): Response<ExtractResponse>
}
```

- [ ] **Step 3: 创建 ApiClient 单例**

```kotlin
package com.stego.app.api

import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

object ApiClient {
    private const val BASE_URL = "http://10.0.2.2:8000/"  // Android 模拟器访问本机

    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .addInterceptor(HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BODY
        })
        .build()

    private val retrofit = Retrofit.Builder()
        .baseUrl(BASE_URL)
        .client(okHttpClient)
        .addConverterFactory(GsonConverterFactory.create())
        .build()

    val stegoApi: StegoApi = retrofit.create(StegoApi::class.java)
}
```

- [ ] **Step 4: Commit**

```bash
git add android/
git commit -m "feat(android): add API service layer"
```

---

### Task 6: 创建嵌入页面 UI

**Files:**
- Create: `android/app/src/main/java/com/stego/app/ui/embed/EmbedFragment.kt`
- Create: `android/app/src/main/java/com/stego/app/ui/embed/EmbedViewModel.kt`
- Create: `android/app/src/main/res/layout/fragment_embed.xml`

- [ ] **Step 1: 创建 fragment_embed.xml 布局**

```xml
<?xml version="1.0" encoding="utf-8"?>
<ScrollView xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:padding="16dp">

    <LinearLayout
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:orientation="vertical">

        <TextView
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:text="消息嵌入"
            android:textSize="24sp"
            android:textStyle="bold" />

        <Button
            android:id="@+id/btnSelectImage"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:layout_marginTop="16dp"
            android:text="选择载体图像" />

        <ImageView
            android:id="@+id/ivCoverImage"
            android:layout_width="match_parent"
            android:layout_height="200dp"
            android:layout_marginTop="8dp"
            android:scaleType="centerInside"
            android:background="#E0E0E0" />

        <EditText
            android:id="@+id/etMessage"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:layout_marginTop="16dp"
            android:hint="输入秘密消息"
            android:minLines="3" />

        <EditText
            android:id="@+id/etKey"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:layout_marginTop="8dp"
            android:hint="输入密钥"
            android:inputType="textPassword" />

        <Button
            android:id="@+id/btnEmbed"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:layout_marginTop="16dp"
            android:text="生成含密图像" />

        <ProgressBar
            android:id="@+id/progressBar"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:layout_gravity="center"
            android:layout_marginTop="8dp"
            android:visibility="gone" />

        <TextView
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:layout_marginTop="16dp"
            android:text="生成结果："
            android:textStyle="bold" />

        <ImageView
            android:id="@+id/ivStegoImage"
            android:layout_width="match_parent"
            android:layout_height="200dp"
            android:layout_marginTop="8dp"
            android:scaleType="centerInside"
            android:background="#E0E0E0" />

        <TextView
            android:id="@+id/tvDemoTag"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:layout_marginTop="4dp"
            android:text="[演示模式]"
            android:textColor="#FF9800"
            android:visibility="gone" />

    </LinearLayout>
</ScrollView>
```

- [ ] **Step 2: 创建 EmbedViewModel**

```kotlin
package com.stego.app.ui.embed

import android.net.Uri
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stego.app.api.ApiClient
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File

class EmbedViewModel : ViewModel() {

    private val _coverImageUri = MutableLiveData<Uri?>()
    val coverImageUri: LiveData<Uri?> = _coverImageUri

    private val _stegoImageBase64 = MutableLiveData<String?>()
    val stegoImageBase64: LiveData<String?> = _stegoImageBase64

    private val _isLoading = MutableLiveData(false)
    val isLoading: LiveData<Boolean> = _isLoading

    private val _isDemo = MutableLiveData(false)
    val isDemo: LiveData<Boolean> = _isDemo

    private val _error = MutableLiveData<String?>()
    val error: LiveData<String?> = _error

    fun setCoverImage(uri: Uri) {
        _coverImageUri.value = uri
    }

    fun embed(imageFile: File, message: String, key: String) {
        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null

            try {
                val imagePart = MultipartBody.Part.createFormData(
                    "cover_image",
                    imageFile.name,
                    imageFile.asRequestBody("image/*".toMediaTypeOrNull())
                )
                val messagePart = message.toRequestBody("text/plain".toMediaTypeOrNull())
                val keyPart = key.toRequestBody("text/plain".toMediaTypeOrNull())
                val ratePart = "0.5".toRequestBody("text/plain".toMediaTypeOrNull())

                val response = ApiClient.stegoApi.embed(imagePart, messagePart, keyPart, ratePart)

                if (response.isSuccessful && response.body()?.status == "success") {
                    _stegoImageBase64.value = response.body()?.stego_image
                    _isDemo.value = response.body()?.is_demo ?: false
                } else {
                    _error.value = "嵌入失败"
                }
            } catch (e: Exception) {
                _error.value = e.message ?: "网络错误"
            } finally {
                _isLoading.value = false
            }
        }
    }
}
```

- [ ] **Step 3: 创建 EmbedFragment**

```kotlin
package com.stego.app.ui.embed

import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.util.Base64
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import com.stego.app.R
import java.io.File
import java.io.FileOutputStream

class EmbedFragment : Fragment() {

    private val viewModel: EmbedViewModel by viewModels()
    private var selectedImageFile: File? = null

    private val pickImage = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let {
            viewModel.setCoverImage(it)
            copyUriToFile(it)
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_embed, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val ivCover = view.findViewById<ImageView>(R.id.ivCoverImage)
        val ivStego = view.findViewById<ImageView>(R.id.ivStegoImage)
        val etMessage = view.findViewById<EditText>(R.id.etMessage)
        val etKey = view.findViewById<EditText>(R.id.etKey)
        val btnSelect = view.findViewById<Button>(R.id.btnSelectImage)
        val btnEmbed = view.findViewById<Button>(R.id.btnEmbed)
        val progressBar = view.findViewById<ProgressBar>(R.id.progressBar)
        val tvDemo = view.findViewById<TextView>(R.id.tvDemoTag)

        btnSelect.setOnClickListener { pickImage.launch("image/*") }

        btnEmbed.setOnClickListener {
            val file = selectedImageFile
            val msg = etMessage.text.toString()
            val key = etKey.text.toString()

            if (file == null) {
                Toast.makeText(context, "请先选择图像", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (msg.isEmpty() || key.isEmpty()) {
                Toast.makeText(context, "请填写消息和密钥", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            viewModel.embed(file, msg, key)
        }

        viewModel.coverImageUri.observe(viewLifecycleOwner) { uri ->
            uri?.let { ivCover.setImageURI(it) }
        }

        viewModel.stegoImageBase64.observe(viewLifecycleOwner) { base64 ->
            base64?.let {
                val imageData = it.substringAfter("base64,")
                val bytes = Base64.decode(imageData, Base64.DEFAULT)
                val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                ivStego.setImageBitmap(bitmap)
            }
        }

        viewModel.isLoading.observe(viewLifecycleOwner) { loading ->
            progressBar.visibility = if (loading) View.VISIBLE else View.GONE
            btnEmbed.isEnabled = !loading
        }

        viewModel.isDemo.observe(viewLifecycleOwner) { demo ->
            tvDemo.visibility = if (demo) View.VISIBLE else View.GONE
        }

        viewModel.error.observe(viewLifecycleOwner) { err ->
            err?.let { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() }
        }
    }

    private fun copyUriToFile(uri: Uri) {
        val inputStream = requireContext().contentResolver.openInputStream(uri)
        val file = File(requireContext().cacheDir, "cover_image.png")
        FileOutputStream(file).use { output ->
            inputStream?.copyTo(output)
        }
        selectedImageFile = file
    }
}
```

- [ ] **Step 4: Commit**

```bash
git add android/
git commit -m "feat(android): add embed fragment UI and ViewModel"
```

---

### Task 7: 创建提取页面 UI

**Files:**
- Create: `android/app/src/main/java/com/stego/app/ui/extract/ExtractFragment.kt`
- Create: `android/app/src/main/java/com/stego/app/ui/extract/ExtractViewModel.kt`
- Create: `android/app/src/main/res/layout/fragment_extract.xml`

- [ ] **Step 1: 创建 fragment_extract.xml 布局**

```xml
<?xml version="1.0" encoding="utf-8"?>
<ScrollView xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:padding="16dp">

    <LinearLayout
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:orientation="vertical">

        <TextView
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:text="消息提取"
            android:textSize="24sp"
            android:textStyle="bold" />

        <Button
            android:id="@+id/btnSelectStego"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:layout_marginTop="16dp"
            android:text="选择含密图像" />

        <ImageView
            android:id="@+id/ivStegoImage"
            android:layout_width="match_parent"
            android:layout_height="200dp"
            android:layout_marginTop="8dp"
            android:scaleType="centerInside"
            android:background="#E0E0E0" />

        <EditText
            android:id="@+id/etKey"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:layout_marginTop="16dp"
            android:hint="输入密钥"
            android:inputType="textPassword" />

        <Button
            android:id="@+id/btnExtract"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:layout_marginTop="16dp"
            android:text="提取消息" />

        <ProgressBar
            android:id="@+id/progressBar"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:layout_gravity="center"
            android:layout_marginTop="8dp"
            android:visibility="gone" />

        <TextView
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:layout_marginTop="16dp"
            android:text="提取结果："
            android:textStyle="bold" />

        <TextView
            android:id="@+id/tvExtractedMessage"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:layout_marginTop="8dp"
            android:padding="12dp"
            android:background="#F5F5F5"
            android:textSize="16sp" />

        <TextView
            android:id="@+id/tvDemoTag"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:layout_marginTop="4dp"
            android:text="[演示模式]"
            android:textColor="#FF9800"
            android:visibility="gone" />

    </LinearLayout>
</ScrollView>
```

- [ ] **Step 2: 创建 ExtractViewModel**

```kotlin
package com.stego.app.ui.extract

import android.net.Uri
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stego.app.api.ApiClient
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File

class ExtractViewModel : ViewModel() {

    private val _stegoImageUri = MutableLiveData<Uri?>()
    val stegoImageUri: LiveData<Uri?> = _stegoImageUri

    private val _extractedMessage = MutableLiveData<String?>()
    val extractedMessage: LiveData<String?> = _extractedMessage

    private val _isLoading = MutableLiveData(false)
    val isLoading: LiveData<Boolean> = _isLoading

    private val _isDemo = MutableLiveData(false)
    val isDemo: LiveData<Boolean> = _isDemo

    private val _error = MutableLiveData<String?>()
    val error: LiveData<String?> = _error

    fun setStegoImage(uri: Uri) {
        _stegoImageUri.value = uri
    }

    fun extract(imageFile: File, key: String) {
        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null

            try {
                val imagePart = MultipartBody.Part.createFormData(
                    "stego_image",
                    imageFile.name,
                    imageFile.asRequestBody("image/*".toMediaTypeOrNull())
                )
                val keyPart = key.toRequestBody("text/plain".toMediaTypeOrNull())

                val response = ApiClient.stegoApi.extract(imagePart, keyPart)

                if (response.isSuccessful && response.body()?.status == "success") {
                    _extractedMessage.value = response.body()?.secret_message
                    _isDemo.value = response.body()?.is_demo ?: false
                } else {
                    _error.value = "提取失败"
                }
            } catch (e: Exception) {
                _error.value = e.message ?: "网络错误"
            } finally {
                _isLoading.value = false
            }
        }
    }
}
```

- [ ] **Step 3: 创建 ExtractFragment**

```kotlin
package com.stego.app.ui.extract

import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import com.stego.app.R
import java.io.File
import java.io.FileOutputStream

class ExtractFragment : Fragment() {

    private val viewModel: ExtractViewModel by viewModels()
    private var selectedImageFile: File? = null

    private val pickImage = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let {
            viewModel.setStegoImage(it)
            copyUriToFile(it)
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_extract, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val ivStego = view.findViewById<ImageView>(R.id.ivStegoImage)
        val etKey = view.findViewById<EditText>(R.id.etKey)
        val btnSelect = view.findViewById<Button>(R.id.btnSelectStego)
        val btnExtract = view.findViewById<Button>(R.id.btnExtract)
        val progressBar = view.findViewById<ProgressBar>(R.id.progressBar)
        val tvMessage = view.findViewById<TextView>(R.id.tvExtractedMessage)
        val tvDemo = view.findViewById<TextView>(R.id.tvDemoTag)

        btnSelect.setOnClickListener { pickImage.launch("image/*") }

        btnExtract.setOnClickListener {
            val file = selectedImageFile
            val key = etKey.text.toString()

            if (file == null) {
                Toast.makeText(context, "请先选择图像", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (key.isEmpty()) {
                Toast.makeText(context, "请填写密钥", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            viewModel.extract(file, key)
        }

        viewModel.stegoImageUri.observe(viewLifecycleOwner) { uri ->
            uri?.let { ivStego.setImageURI(it) }
        }

        viewModel.extractedMessage.observe(viewLifecycleOwner) { msg ->
            tvMessage.text = msg ?: ""
        }

        viewModel.isLoading.observe(viewLifecycleOwner) { loading ->
            progressBar.visibility = if (loading) View.VISIBLE else View.GONE
            btnExtract.isEnabled = !loading
        }

        viewModel.isDemo.observe(viewLifecycleOwner) { demo ->
            tvDemo.visibility = if (demo) View.VISIBLE else View.GONE
        }

        viewModel.error.observe(viewLifecycleOwner) { err ->
            err?.let { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() }
        }
    }

    private fun copyUriToFile(uri: Uri) {
        val inputStream = requireContext().contentResolver.openInputStream(uri)
        val file = File(requireContext().cacheDir, "stego_image.png")
        FileOutputStream(file).use { output ->
            inputStream?.copyTo(output)
        }
        selectedImageFile = file
    }
}
```

- [ ] **Step 4: Commit**

```bash
git add android/
git commit -m "feat(android): add extract fragment UI and ViewModel"
```

---

### Task 8: 设置主界面导航

**Files:**
- Modify: `android/app/src/main/java/com/stego/app/MainActivity.kt`
- Modify: `android/app/src/main/res/layout/activity_main.xml`
- Create: `android/app/src/main/res/menu/bottom_nav_menu.xml`
- Create: `android/app/src/main/res/navigation/nav_graph.xml`

- [ ] **Step 1: 添加 Navigation 依赖到 build.gradle.kts**

```kotlin
implementation("androidx.navigation:navigation-fragment-ktx:2.7.6")
implementation("androidx.navigation:navigation-ui-ktx:2.7.6")
```

- [ ] **Step 2: 创建 bottom_nav_menu.xml**

```xml
<?xml version="1.0" encoding="utf-8"?>
<menu xmlns:android="http://schemas.android.com/apk/res/android">
    <item
        android:id="@+id/embedFragment"
        android:icon="@android:drawable/ic_menu_edit"
        android:title="嵌入" />
    <item
        android:id="@+id/extractFragment"
        android:icon="@android:drawable/ic_menu_view"
        android:title="提取" />
</menu>
```

- [ ] **Step 3: 创建 nav_graph.xml**

```xml
<?xml version="1.0" encoding="utf-8"?>
<navigation xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto"
    android:id="@+id/nav_graph"
    app:startDestination="@id/embedFragment">

    <fragment
        android:id="@+id/embedFragment"
        android:name="com.stego.app.ui.embed.EmbedFragment"
        android:label="嵌入" />

    <fragment
        android:id="@+id/extractFragment"
        android:name="com.stego.app.ui.extract.ExtractFragment"
        android:label="提取" />
</navigation>
```

- [ ] **Step 4: 更新 activity_main.xml**

```xml
<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:orientation="vertical">

    <androidx.fragment.app.FragmentContainerView
        android:id="@+id/nav_host_fragment"
        android:name="androidx.navigation.fragment.NavHostFragment"
        android:layout_width="match_parent"
        android:layout_height="0dp"
        android:layout_weight="1"
        app:defaultNavHost="true"
        app:navGraph="@navigation/nav_graph" />

    <com.google.android.material.bottomnavigation.BottomNavigationView
        android:id="@+id/bottom_nav"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        app:menu="@menu/bottom_nav_menu" />

</LinearLayout>
```

- [ ] **Step 5: 更新 MainActivity.kt**

```kotlin
package com.stego.app

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.setupWithNavController
import com.google.android.material.bottomnavigation.BottomNavigationView

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val navHostFragment = supportFragmentManager
            .findFragmentById(R.id.nav_host_fragment) as NavHostFragment
        val navController = navHostFragment.navController

        findViewById<BottomNavigationView>(R.id.bottom_nav)
            .setupWithNavController(navController)
    }
}
```

- [ ] **Step 6: 编译运行测试**

确保 App 可以正常启动，底部导航可切换嵌入/提取页面

- [ ] **Step 7: Commit**

```bash
git add android/
git commit -m "feat(android): setup bottom navigation"
```

---

## Chunk 3: Vue.js Web

### Task 9: 创建 Vue 项目

**Files:**
- Create: `web/` (Vite + Vue3 项目)

- [ ] **Step 1: 初始化 Vue 项目**

```bash
cd /home/zya/bishe
npm create vite@latest web -- --template vue-ts
cd web
npm install
```

- [ ] **Step 2: 安装依赖**

```bash
npm install axios vue-router@4 pinia
```

- [ ] **Step 3: 验证项目启动**

```bash
npm run dev
```

访问 http://localhost:5173 确认页面正常

- [ ] **Step 4: Commit**

```bash
git add web/
git commit -m "feat(web): init Vue.js project"
```

---

### Task 10: 创建 API 服务层

**Files:**
- Create: `web/src/api/stego.ts`
- Create: `web/src/api/index.ts`

- [ ] **Step 1: 创建 api/index.ts**

```typescript
import axios from 'axios'

const apiClient = axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL || 'http://localhost:8000',
  timeout: 30000
})

export default apiClient
```

- [ ] **Step 2: 创建 api/stego.ts**

```typescript
import apiClient from './index'

export interface EmbedResponse {
  status: string
  stego_image: string
  is_demo: boolean
}

export interface ExtractResponse {
  status: string
  secret_message: string
  is_demo: boolean
}

export const stegoApi = {
  async embed(coverImage: File, message: string, key: string, embedRate: number = 0.5): Promise<EmbedResponse> {
    const formData = new FormData()
    formData.append('cover_image', coverImage)
    formData.append('secret_message', message)
    formData.append('key', key)
    formData.append('embed_rate', embedRate.toString())

    const response = await apiClient.post<EmbedResponse>('/api/v1/stego/embed', formData)
    return response.data
  },

  async extract(stegoImage: File, key: string): Promise<ExtractResponse> {
    const formData = new FormData()
    formData.append('stego_image', stegoImage)
    formData.append('key', key)

    const response = await apiClient.post<ExtractResponse>('/api/v1/stego/extract', formData)
    return response.data
  }
}
```

- [ ] **Step 3: Commit**

```bash
git add web/
git commit -m "feat(web): add API service layer"
```

---

### Task 11: 创建路由和页面结构

**Files:**
- Create: `web/src/router/index.ts`
- Create: `web/src/views/EmbedView.vue`
- Create: `web/src/views/ExtractView.vue`
- Modify: `web/src/App.vue`
- Modify: `web/src/main.ts`

- [ ] **Step 1: 创建 router/index.ts**

```typescript
import { createRouter, createWebHistory } from 'vue-router'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', redirect: '/embed' },
    { path: '/embed', name: 'Embed', component: () => import('../views/EmbedView.vue') },
    { path: '/extract', name: 'Extract', component: () => import('../views/ExtractView.vue') }
  ]
})

export default router
```

- [ ] **Step 2: 更新 main.ts**

```typescript
import { createApp } from 'vue'
import { createPinia } from 'pinia'
import App from './App.vue'
import router from './router'

const app = createApp(App)
app.use(createPinia())
app.use(router)
app.mount('#app')
```

- [ ] **Step 3: 更新 App.vue**

```vue
<script setup lang="ts">
</script>

<template>
  <div class="app">
    <nav class="nav">
      <router-link to="/embed">嵌入</router-link>
      <router-link to="/extract">提取</router-link>
    </nav>
    <main class="main">
      <router-view />
    </main>
  </div>
</template>

<style scoped>
.app {
  max-width: 800px;
  margin: 0 auto;
  padding: 20px;
}
.nav {
  display: flex;
  gap: 20px;
  margin-bottom: 20px;
  padding-bottom: 10px;
  border-bottom: 1px solid #eee;
}
.nav a {
  text-decoration: none;
  color: #333;
  font-weight: bold;
}
.nav a.router-link-active {
  color: #42b883;
}
</style>
```

- [ ] **Step 4: 创建占位 EmbedView.vue**

```vue
<template>
  <div>嵌入页面</div>
</template>
```

- [ ] **Step 5: 创建占位 ExtractView.vue**

```vue
<template>
  <div>提取页面</div>
</template>
```

- [ ] **Step 6: Commit**

```bash
git add web/
git commit -m "feat(web): setup router and page structure"
```

---

### Task 12: 实现嵌入页面

**Files:**
- Modify: `web/src/views/EmbedView.vue`

- [ ] **Step 1: 实现 EmbedView.vue**

```vue
<script setup lang="ts">
import { ref } from 'vue'
import { stegoApi } from '../api/stego'

const coverImage = ref<File | null>(null)
const coverPreview = ref<string>('')
const message = ref('')
const key = ref('')
const stegoImage = ref<string>('')
const isLoading = ref(false)
const isDemo = ref(false)
const error = ref('')

function onFileChange(e: Event) {
  const target = e.target as HTMLInputElement
  const file = target.files?.[0]
  if (file) {
    coverImage.value = file
    coverPreview.value = URL.createObjectURL(file)
  }
}

async function handleEmbed() {
  if (!coverImage.value || !message.value || !key.value) {
    error.value = '请填写所有字段'
    return
  }

  isLoading.value = true
  error.value = ''

  try {
    const result = await stegoApi.embed(coverImage.value, message.value, key.value)
    stegoImage.value = result.stego_image
    isDemo.value = result.is_demo
  } catch (e: any) {
    error.value = e.message || '请求失败'
  } finally {
    isLoading.value = false
  }
}
</script>

<template>
  <div class="embed-view">
    <h1>消息嵌入</h1>

    <div class="form-group">
      <label>选择载体图像</label>
      <input type="file" accept="image/*" @change="onFileChange" />
      <img v-if="coverPreview" :src="coverPreview" class="preview" />
    </div>

    <div class="form-group">
      <label>秘密消息</label>
      <textarea v-model="message" rows="3" placeholder="输入秘密消息..."></textarea>
    </div>

    <div class="form-group">
      <label>密钥</label>
      <input type="password" v-model="key" placeholder="输入密钥" />
    </div>

    <button @click="handleEmbed" :disabled="isLoading">
      {{ isLoading ? '生成中...' : '生成含密图像' }}
    </button>

    <p v-if="error" class="error">{{ error }}</p>

    <div v-if="stegoImage" class="result">
      <h3>生成结果：</h3>
      <img :src="stegoImage" class="preview" />
      <span v-if="isDemo" class="demo-tag">[演示模式]</span>
    </div>
  </div>
</template>

<style scoped>
.embed-view { max-width: 500px; }
.form-group { margin-bottom: 16px; }
.form-group label { display: block; margin-bottom: 4px; font-weight: bold; }
.form-group input, .form-group textarea { width: 100%; padding: 8px; box-sizing: border-box; }
.preview { max-width: 100%; max-height: 200px; margin-top: 8px; border: 1px solid #ddd; }
button { padding: 10px 20px; background: #42b883; color: white; border: none; cursor: pointer; }
button:disabled { background: #ccc; }
.error { color: red; }
.result { margin-top: 20px; }
.demo-tag { color: #ff9800; font-size: 14px; }
</style>
```

- [ ] **Step 2: Commit**

```bash
git add web/
git commit -m "feat(web): implement embed view"
```

---

### Task 13: 实现提取页面

**Files:**
- Modify: `web/src/views/ExtractView.vue`

- [ ] **Step 1: 实现 ExtractView.vue**

```vue
<script setup lang="ts">
import { ref } from 'vue'
import { stegoApi } from '../api/stego'

const stegoImageFile = ref<File | null>(null)
const stegoPreview = ref<string>('')
const key = ref('')
const extractedMessage = ref('')
const isLoading = ref(false)
const isDemo = ref(false)
const error = ref('')

function onFileChange(e: Event) {
  const target = e.target as HTMLInputElement
  const file = target.files?.[0]
  if (file) {
    stegoImageFile.value = file
    stegoPreview.value = URL.createObjectURL(file)
  }
}

async function handleExtract() {
  if (!stegoImageFile.value || !key.value) {
    error.value = '请选择图像并填写密钥'
    return
  }

  isLoading.value = true
  error.value = ''

  try {
    const result = await stegoApi.extract(stegoImageFile.value, key.value)
    extractedMessage.value = result.secret_message
    isDemo.value = result.is_demo
  } catch (e: any) {
    error.value = e.message || '请求失败'
  } finally {
    isLoading.value = false
  }
}
</script>

<template>
  <div class="extract-view">
    <h1>消息提取</h1>

    <div class="form-group">
      <label>选择含密图像</label>
      <input type="file" accept="image/*" @change="onFileChange" />
      <img v-if="stegoPreview" :src="stegoPreview" class="preview" />
    </div>

    <div class="form-group">
      <label>密钥</label>
      <input type="password" v-model="key" placeholder="输入密钥" />
    </div>

    <button @click="handleExtract" :disabled="isLoading">
      {{ isLoading ? '提取中...' : '提取消息' }}
    </button>

    <p v-if="error" class="error">{{ error }}</p>

    <div v-if="extractedMessage" class="result">
      <h3>提取结果：</h3>
      <p class="message-box">{{ extractedMessage }}</p>
      <span v-if="isDemo" class="demo-tag">[演示模式]</span>
    </div>
  </div>
</template>

<style scoped>
.extract-view { max-width: 500px; }
.form-group { margin-bottom: 16px; }
.form-group label { display: block; margin-bottom: 4px; font-weight: bold; }
.form-group input { width: 100%; padding: 8px; box-sizing: border-box; }
.preview { max-width: 100%; max-height: 200px; margin-top: 8px; border: 1px solid #ddd; }
button { padding: 10px 20px; background: #42b883; color: white; border: none; cursor: pointer; }
button:disabled { background: #ccc; }
.error { color: red; }
.result { margin-top: 20px; }
.message-box { padding: 12px; background: #f5f5f5; border-radius: 4px; }
.demo-tag { color: #ff9800; font-size: 14px; }
</style>
```

- [ ] **Step 2: Commit**

```bash
git add web/
git commit -m "feat(web): implement extract view"
```

---

## 完成检查

- [ ] Python Server 运行正常，`/embed` 和 `/extract` 返回演示数据
- [ ] Android App 可编译安装，嵌入/提取页面功能完整
- [ ] Vue.js Web 可运行，嵌入/提取页面功能完整
- [ ] 所有改动已提交到 git
