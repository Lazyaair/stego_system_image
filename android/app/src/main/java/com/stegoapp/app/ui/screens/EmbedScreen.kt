package com.stegoapp.app.ui.screens

import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.stegoapp.app.api.ApiClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.FileOutputStream

@Composable
fun EmbedScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var selectedImageUri by remember { mutableStateOf<Uri?>(null) }
    var selectedImageFile by remember { mutableStateOf<File?>(null) }
    var message by remember { mutableStateOf("") }
    var key by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    var stegoImageBase64 by remember { mutableStateOf<String?>(null) }
    var isDemo by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    val imagePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let {
            selectedImageUri = it
            // Copy to cache file
            val inputStream = context.contentResolver.openInputStream(it)
            val file = File(context.cacheDir, "cover_image.png")
            FileOutputStream(file).use { output ->
                inputStream?.copyTo(output)
            }
            selectedImageFile = file
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

        Button(
            onClick = { imagePicker.launch("image/*") },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("选择载体图像")
        }

        Spacer(modifier = Modifier.height(8.dp))

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(200.dp)
                .background(Color.LightGray),
            contentAlignment = Alignment.Center
        ) {
            if (selectedImageUri != null) {
                AsyncImage(
                    model = selectedImageUri,
                    contentDescription = "载体图像",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit
                )
            } else {
                Text("未选择图像", color = Color.Gray)
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        OutlinedTextField(
            value = message,
            onValueChange = { message = it },
            label = { Text("秘密消息") },
            modifier = Modifier.fillMaxWidth(),
            minLines = 3
        )

        Spacer(modifier = Modifier.height(8.dp))

        OutlinedTextField(
            value = key,
            onValueChange = { key = it },
            label = { Text("密钥") },
            modifier = Modifier.fillMaxWidth(),
            visualTransformation = PasswordVisualTransformation()
        )

        Spacer(modifier = Modifier.height(16.dp))

        Button(
            onClick = {
                scope.launch {
                    val file = selectedImageFile
                    if (file == null) {
                        errorMessage = "请先选择图像"
                        return@launch
                    }
                    if (message.isEmpty() || key.isEmpty()) {
                        errorMessage = "请填写消息和密钥"
                        return@launch
                    }

                    isLoading = true
                    errorMessage = null

                    try {
                        val imagePart = MultipartBody.Part.createFormData(
                            "cover_image",
                            file.name,
                            file.asRequestBody("image/*".toMediaTypeOrNull())
                        )
                        val messagePart = message.toRequestBody("text/plain".toMediaTypeOrNull())
                        val keyPart = key.toRequestBody("text/plain".toMediaTypeOrNull())
                        val ratePart = "0.5".toRequestBody("text/plain".toMediaTypeOrNull())

                        val response = withContext(Dispatchers.IO) {
                            ApiClient.stegoApi.embed(imagePart, messagePart, keyPart, ratePart)
                        }

                        if (response.isSuccessful && response.body()?.status == "success") {
                            stegoImageBase64 = response.body()?.stego_image
                            isDemo = response.body()?.is_demo ?: false
                        } else {
                            errorMessage = "嵌入失败"
                        }
                    } catch (e: Exception) {
                        errorMessage = e.message ?: "网络错误"
                    } finally {
                        isLoading = false
                    }
                }
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = !isLoading
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

            bitmap?.let {
                Image(
                    bitmap = it.asImageBitmap(),
                    contentDescription = "含密图像",
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp),
                    contentScale = ContentScale.Fit
                )
            }

            if (isDemo) {
                Text(
                    text = "[演示模式]",
                    color = Color(0xFFFF9800)
                )
            }
        }
    }
}
