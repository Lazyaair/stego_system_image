package com.stegoapp.app.ui.screens

import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.stegoapp.app.api.ApiClient
import com.stegoapp.app.api.Model
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EmbedScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var models by remember { mutableStateOf<List<Model>>(emptyList()) }
    var selectedModel by remember { mutableStateOf("celebahq") }
    var expanded by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var key by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    var isCheckingCapacity by remember { mutableStateOf(false) }
    var stegoImageBase64 by remember { mutableStateOf<String?>(null) }
    var capacityInfo by remember { mutableStateOf<String?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    // 加载模型列表
    LaunchedEffect(Unit) {
        try {
            val response = withContext(Dispatchers.IO) {
                ApiClient.stegoApi.getModels()
            }
            if (response.isSuccessful) {
                models = response.body()?.models ?: emptyList()
                val defaultModel = models.find { it.default }
                if (defaultModel != null) {
                    selectedModel = defaultModel.id
                }
            }
        } catch (e: Exception) {
            errorMessage = "获取模型列表失败"
        }
    }

    fun validateKey(): Boolean {
        return when {
            key.isEmpty() -> {
                errorMessage = "密钥不能为空"
                false
            }
            key.length > 64 -> {
                errorMessage = "密钥长度不能超过 64 字符"
                false
            }
            else -> true
        }
    }

    fun checkCapacity() {
        if (!validateKey() || message.isEmpty()) return

        scope.launch {
            isCheckingCapacity = true
            errorMessage = null

            try {
                val response = withContext(Dispatchers.IO) {
                    ApiClient.stegoApi.checkCapacity(message, key, selectedModel)
                }

                if (response.isSuccessful) {
                    val body = response.body()
                    if (body != null) {
                        capacityInfo = "消息长度: ${body.message_length} bytes / 最大容量: ${body.max_capacity} bytes"
                        if (!body.valid) {
                            errorMessage = body.error ?: "消息超出容量"
                        }
                    }
                } else {
                    errorMessage = "容量检查失败"
                }
            } catch (e: Exception) {
                errorMessage = e.message ?: "网络错误"
            } finally {
                isCheckingCapacity = false
            }
        }
    }

    fun handleEmbed() {
        if (!validateKey() || message.isEmpty()) {
            if (message.isEmpty()) errorMessage = "请输入秘密消息"
            return
        }

        scope.launch {
            isLoading = true
            errorMessage = null
            stegoImageBase64 = null

            try {
                val response = withContext(Dispatchers.IO) {
                    ApiClient.stegoApi.embed(message, key, selectedModel)
                }

                if (response.isSuccessful && response.body()?.status == "success") {
                    stegoImageBase64 = response.body()?.stego_image
                } else {
                    val body = response.body()
                    errorMessage = body?.error ?: "嵌入失败"
                    if (body?.max_capacity != null) {
                        errorMessage += " (最大容量: ${body.max_capacity} bytes)"
                    }
                }
            } catch (e: Exception) {
                errorMessage = e.message ?: "网络错误"
            } finally {
                isLoading = false
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Text(
            text = "消息嵌入",
            style = MaterialTheme.typography.headlineMedium
        )

        Spacer(modifier = Modifier.height(16.dp))

        // 模型选择
        Text("选择模型", style = MaterialTheme.typography.labelLarge)
        Spacer(modifier = Modifier.height(4.dp))
        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { expanded = !expanded }
        ) {
            OutlinedTextField(
                value = models.find { it.id == selectedModel }?.name ?: "",
                onValueChange = {},
                readOnly = true,
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor()
            )
            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false }
            ) {
                models.forEach { model ->
                    DropdownMenuItem(
                        text = { Text(model.name) },
                        onClick = {
                            selectedModel = model.id
                            expanded = false
                        }
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // 秘密消息输入
        OutlinedTextField(
            value = message,
            onValueChange = { message = it },
            label = { Text("秘密消息") },
            modifier = Modifier.fillMaxWidth(),
            minLines = 4,
            placeholder = { Text("输入要隐藏的秘密消息...") }
        )

        capacityInfo?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = Color.Gray,
                modifier = Modifier.padding(top = 4.dp)
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        // 密钥输入
        OutlinedTextField(
            value = key,
            onValueChange = { if (it.length <= 64) key = it },
            label = { Text("密钥 (1-64 字符)") },
            modifier = Modifier.fillMaxWidth(),
            visualTransformation = PasswordVisualTransformation(),
            placeholder = { Text("输入密钥，用于加密和解密") }
        )

        Spacer(modifier = Modifier.height(16.dp))

        // 操作按钮
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                onClick = { checkCapacity() },
                enabled = !isCheckingCapacity && message.isNotEmpty() && key.isNotEmpty(),
                modifier = Modifier.weight(1f)
            ) {
                Text(if (isCheckingCapacity) "检查中..." else "检查容量")
            }

            Button(
                onClick = { handleEmbed() },
                enabled = !isLoading && message.isNotEmpty() && key.isNotEmpty(),
                modifier = Modifier.weight(1f)
            ) {
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = Color.White
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text(if (isLoading) "生成中..." else "生成含密图像")
            }
        }

        errorMessage?.let {
            Spacer(modifier = Modifier.height(8.dp))
            Text(text = it, color = Color.Red)
        }

        stegoImageBase64?.let { base64 ->
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "生成结果：",
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(modifier = Modifier.height(8.dp))

            val imageData = base64.substringAfter("base64,")
            val bytes = Base64.decode(imageData, Base64.DEFAULT)
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)

            bitmap?.let { bmp ->
                Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = "含密图像",
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(256.dp),
                    contentScale = ContentScale.Fit
                )

                Spacer(modifier = Modifier.height(8.dp))

                Button(
                    onClick = {
                        scope.launch {
                            val saved = withContext(Dispatchers.IO) {
                                saveImageToGallery(context, bmp, "stego_${System.currentTimeMillis()}.png")
                            }
                            if (saved) {
                                withContext(Dispatchers.Main) {
                                    Toast.makeText(context, "图像已保存到相册", Toast.LENGTH_SHORT).show()
                                }
                            } else {
                                withContext(Dispatchers.Main) {
                                    Toast.makeText(context, "保存失败", Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("保存到相册")
                }
            }
        }
    }
}

private fun saveImageToGallery(context: android.content.Context, bitmap: Bitmap, filename: String): Boolean {
    return try {
        val outputStream: OutputStream?
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val contentValues = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
                put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/StegoApp")
            }
            val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
            outputStream = uri?.let { context.contentResolver.openOutputStream(it) }
        } else {
            val imagesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
            val stegoDir = File(imagesDir, "StegoApp")
            if (!stegoDir.exists()) stegoDir.mkdirs()
            val imageFile = File(stegoDir, filename)
            outputStream = FileOutputStream(imageFile)
        }
        outputStream?.use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        true
    } catch (e: Exception) {
        e.printStackTrace()
        false
    }
}
