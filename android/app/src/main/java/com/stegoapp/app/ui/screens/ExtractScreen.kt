package com.stegoapp.app.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
fun ExtractScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var selectedImageUri by remember { mutableStateOf<Uri?>(null) }
    var selectedImageFile by remember { mutableStateOf<File?>(null) }
    var key by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    var extractedMessage by remember { mutableStateOf<String?>(null) }
    var isDemo by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    val imagePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let {
            selectedImageUri = it
            val inputStream = context.contentResolver.openInputStream(it)
            val file = File(context.cacheDir, "stego_image.png")
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
            text = "消息提取",
            style = MaterialTheme.typography.headlineMedium
        )

        Spacer(modifier = Modifier.height(16.dp))

        Button(
            onClick = { imagePicker.launch("image/*") },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("选择含密图像")
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
                    contentDescription = "含密图像",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit
                )
            } else {
                Text("未选择图像", color = Color.Gray)
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

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
                    if (key.isEmpty()) {
                        errorMessage = "请填写密钥"
                        return@launch
                    }

                    isLoading = true
                    errorMessage = null

                    try {
                        val imagePart = MultipartBody.Part.createFormData(
                            "stego_image",
                            file.name,
                            file.asRequestBody("image/*".toMediaTypeOrNull())
                        )
                        val keyPart = key.toRequestBody("text/plain".toMediaTypeOrNull())

                        val response = withContext(Dispatchers.IO) {
                            ApiClient.stegoApi.extract(imagePart, keyPart)
                        }

                        if (response.isSuccessful && response.body()?.status == "success") {
                            extractedMessage = response.body()?.secret_message
                            isDemo = response.body()?.is_demo ?: false
                        } else {
                            errorMessage = "提取失败"
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
            Text(if (isLoading) "提取中..." else "提取消息")
        }

        errorMessage?.let {
            Spacer(modifier = Modifier.height(8.dp))
            Text(text = it, color = Color.Red)
        }

        extractedMessage?.let { msg ->
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "提取结果：",
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(modifier = Modifier.height(8.dp))

            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = Color(0xFFF5F5F5),
                shape = MaterialTheme.shapes.small
            ) {
                Text(
                    text = msg,
                    modifier = Modifier.padding(12.dp),
                    style = MaterialTheme.typography.bodyLarge
                )
            }

            if (isDemo) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "[演示模式]",
                    color = Color(0xFFFF9800)
                )
            }
        }
    }
}
