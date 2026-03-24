package com.stegoapp.app.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.stegoapp.app.api.ApiClient
import com.stegoapp.app.api.Model
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.FileOutputStream

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExtractScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var models by remember { mutableStateOf<List<Model>>(emptyList()) }
    var selectedModel by remember { mutableStateOf("celebahq") }
    var expanded by remember { mutableStateOf(false) }
    var selectedImageUri by remember { mutableStateOf<Uri?>(null) }
    var selectedImageFile by remember { mutableStateOf<File?>(null) }
    var key by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    var extractedMessage by remember { mutableStateOf<String?>(null) }
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

    fun handleExtract() {
        if (!validateKey()) return

        val file = selectedImageFile
        if (file == null) {
            errorMessage = "请先选择含密图像"
            return
        }

        scope.launch {
            isLoading = true
            errorMessage = null
            extractedMessage = null

            try {
                val imagePart = MultipartBody.Part.createFormData(
                    "stego_image",
                    file.name,
                    file.asRequestBody("image/png".toMediaTypeOrNull())
                )
                val keyPart = key.toRequestBody("text/plain".toMediaTypeOrNull())
                val modelPart = selectedModel.toRequestBody("text/plain".toMediaTypeOrNull())

                val response = withContext(Dispatchers.IO) {
                    ApiClient.stegoApi.extract(imagePart, keyPart, modelPart)
                }

                if (response.isSuccessful && response.body()?.status == "success") {
                    extractedMessage = response.body()?.secret_message
                } else {
                    errorMessage = response.body()?.error ?: "提取失败"
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
            text = "消息提取",
            style = MaterialTheme.typography.headlineMedium
        )

        Spacer(modifier = Modifier.height(16.dp))

        // 模型选择
        Text(
            text = "选择模型 (需与嵌入时使用的模型相同)",
            style = MaterialTheme.typography.labelLarge
        )
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

        // 图片选择
        Button(
            onClick = { imagePicker.launch("image/png") },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("选择含密图像")
        }

        Spacer(modifier = Modifier.height(8.dp))

        if (selectedImageUri != null) {
            AsyncImage(
                model = selectedImageUri,
                contentDescription = "含密图像",
                modifier = Modifier
                    .fillMaxWidth()
                    .height(200.dp),
                contentScale = ContentScale.Fit
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // 密钥输入
        OutlinedTextField(
            value = key,
            onValueChange = { if (it.length <= 64) key = it },
            label = { Text("密钥 (需与嵌入时使用的密钥相同)") },
            modifier = Modifier.fillMaxWidth(),
            visualTransformation = PasswordVisualTransformation()
        )

        Spacer(modifier = Modifier.height(16.dp))

        // 提取按钮
        Button(
            onClick = { handleExtract() },
            modifier = Modifier.fillMaxWidth(),
            enabled = !isLoading && selectedImageFile != null && key.isNotEmpty()
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
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = MaterialTheme.shapes.small
            ) {
                Text(
                    text = msg,
                    modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodyLarge
                )
            }
        }
    }
}
