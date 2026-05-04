package com.stegoapp.app.ui.screens.stego

import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64 as AndroidBase64
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.EnhancedEncryption
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import androidx.compose.foundation.Image
import com.stegoapp.app.api.Algorithm
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
import java.io.OutputStream

private const val TAB_EMBED = 0
private const val TAB_EXTRACT = 1

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StegoToolScreen() {
    var activeTab by remember { mutableIntStateOf(TAB_EMBED) }
    val context = LocalContext.current
    var algorithms by remember { mutableStateOf<List<Algorithm>>(emptyList()) }
    var selectedAlgorithm by remember { mutableStateOf("") }
    var selectedModel by remember { mutableStateOf("") }
    val models = remember(algorithms, selectedAlgorithm) {
        algorithms.find { it.id == selectedAlgorithm }?.models ?: emptyList()
    }

    LaunchedEffect(Unit) {
        try {
            val response = withContext(Dispatchers.IO) { ApiClient.stegoApi.getAlgorithms() }
            if (response.isSuccessful) {
                val algos = response.body()?.algorithms ?: emptyList()
                algorithms = algos
                val defaultAlgo = algos.find { it.default } ?: algos.firstOrNull()
                if (defaultAlgo != null) {
                    selectedAlgorithm = defaultAlgo.id
                    selectedModel = (defaultAlgo.models.find { it.default }
                        ?: defaultAlgo.models.firstOrNull())?.id ?: ""
                }
            }
        } catch (_: Exception) {
            // Silent: default empty state; UI handles gracefully
        }
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        text = "隐写工具",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
        containerColor = MaterialTheme.colorScheme.surface,
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            Spacer(modifier = Modifier.height(8.dp))
            ModeSwitcher(activeTab = activeTab, onSelect = { activeTab = it })
            Spacer(modifier = Modifier.height(24.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                AlgorithmSelector(
                    algorithms = algorithms,
                    selectedAlgorithm = selectedAlgorithm,
                    onSelect = { newId ->
                        selectedAlgorithm = newId
                        val algo = algorithms.find { it.id == newId }
                        selectedModel = (algo?.models?.find { it.default }
                            ?: algo?.models?.firstOrNull())?.id ?: ""
                    },
                    modifier = Modifier.weight(1f),
                )
                ModelSelector(
                    models = models,
                    selectedModel = selectedModel,
                    onSelect = { selectedModel = it },
                    modifier = Modifier.weight(1f),
                )
            }
            if (activeTab == TAB_EXTRACT) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "算法和模型必须与嵌入时一致",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
            if (activeTab == TAB_EMBED) {
                EmbedPane(
                    context = context,
                    algorithmId = selectedAlgorithm,
                    modelId = selectedModel,
                )
            } else {
                ExtractPane(
                    context = context,
                    algorithmId = selectedAlgorithm,
                    modelId = selectedModel,
                )
            }
            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModeSwitcher(activeTab: Int, onSelect: (Int) -> Unit) {
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        SegmentedButton(
            selected = activeTab == TAB_EMBED,
            onClick = { onSelect(TAB_EMBED) },
            shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
            icon = {
                Icon(
                    imageVector = Icons.Filled.EnhancedEncryption,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
            },
            label = { Text("嵌入") },
        )
        SegmentedButton(
            selected = activeTab == TAB_EXTRACT,
            onClick = { onSelect(TAB_EXTRACT) },
            shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
            icon = {
                Icon(
                    imageVector = Icons.Filled.Visibility,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
            },
            label = { Text("提取") },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AlgorithmSelector(
    algorithms: List<Algorithm>,
    selectedAlgorithm: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = !expanded },
        modifier = modifier,
    ) {
        OutlinedTextField(
            value = algorithms.find { it.id == selectedAlgorithm }?.name ?: selectedAlgorithm,
            onValueChange = {},
            readOnly = true,
            label = { Text("算法") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            algorithms.forEach { algo ->
                DropdownMenuItem(
                    text = { Text(algo.name) },
                    onClick = {
                        onSelect(algo.id)
                        expanded = false
                    },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModelSelector(
    models: List<Model>,
    selectedModel: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = !expanded },
        modifier = modifier,
    ) {
        OutlinedTextField(
            value = models.find { it.id == selectedModel }?.name ?: selectedModel,
            onValueChange = {},
            readOnly = true,
            label = { Text("模型") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            models.forEach { model ->
                DropdownMenuItem(
                    text = { Text(model.name) },
                    onClick = {
                        onSelect(model.id)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
private fun EmbedPane(
    context: android.content.Context,
    algorithmId: String,
    modelId: String,
) {
    val scope = rememberCoroutineScope()
    var message by remember { mutableStateOf("") }
    var key by remember { mutableStateOf("") }
    var keyVisible by remember { mutableStateOf(false) }
    var capacityInfo by remember { mutableStateOf<String?>(null) }
    var isCheckingCapacity by remember { mutableStateOf(false) }
    var isEmbedding by remember { mutableStateOf(false) }
    var stegoImageBase64 by remember { mutableStateOf<String?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    Column {
        // 秘密消息
        OutlinedTextField(
            value = message,
            onValueChange = { message = it },
            label = { Text("秘密消息") },
            placeholder = { Text("输入要隐藏的秘密消息...") },
            shape = MaterialTheme.shapes.medium,
            minLines = 4,
            maxLines = 6,
            modifier = Modifier.fillMaxWidth(),
        )
        if (capacityInfo != null) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = capacityInfo!!,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Spacer(modifier = Modifier.height(16.dp))

        // 密钥
        KeyInput(
            value = key,
            onValueChange = { if (it.length <= 64) key = it },
            visible = keyVisible,
            onToggleVisible = { keyVisible = !keyVisible },
            helper = "密钥用于生成和解密,双方必须一致",
        )

        Spacer(modifier = Modifier.height(16.dp))

        // 错误
        ErrorLine(errorMessage)

        // 操作按钮:检查容量 + 生成
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedButton(
                onClick = {
                    if (!validateKey(key) { errorMessage = it }) return@OutlinedButton
                    if (message.isEmpty()) {
                        errorMessage = "请输入秘密消息"
                        return@OutlinedButton
                    }
                    scope.launch {
                        isCheckingCapacity = true
                        errorMessage = null
                        try {
                            val resp = withContext(Dispatchers.IO) {
                                ApiClient.stegoApi.checkCapacity(message, key, modelId, algorithmId)
                            }
                            if (resp.isSuccessful) {
                                val body = resp.body()
                                if (body != null) {
                                    capacityInfo =
                                        "消息长度 ${body.message_length} B / 最大容量 ${body.max_capacity} B"
                                    if (!body.valid) errorMessage = body.error ?: "消息超出容量"
                                }
                            } else errorMessage = "容量检查失败"
                        } catch (e: Exception) {
                            errorMessage = e.message ?: "网络错误"
                        } finally {
                            isCheckingCapacity = false
                        }
                    }
                },
                enabled = !isCheckingCapacity && message.isNotEmpty() && key.isNotEmpty(),
                shape = MaterialTheme.shapes.medium,
                contentPadding = PaddingValues(vertical = 12.dp),
                modifier = Modifier.weight(1f),
            ) {
                Text(if (isCheckingCapacity) "检查中..." else "检查容量")
            }
            Button(
                onClick = {
                    if (!validateKey(key) { errorMessage = it }) return@Button
                    if (message.isEmpty()) {
                        errorMessage = "请输入秘密消息"
                        return@Button
                    }
                    scope.launch {
                        isEmbedding = true
                        errorMessage = null
                        stegoImageBase64 = null
                        try {
                            val resp = withContext(Dispatchers.IO) {
                                ApiClient.stegoApi.embed(message, key, modelId, algorithmId)
                            }
                            val body = resp.body()
                            if (resp.isSuccessful && body?.status == "success") {
                                stegoImageBase64 = body.stego_image
                            } else {
                                errorMessage = body?.error ?: "嵌入失败"
                                body?.max_capacity?.let { errorMessage += " (最大容量 $it B)" }
                            }
                        } catch (e: Exception) {
                            errorMessage = e.message ?: "网络错误"
                        } finally {
                            isEmbedding = false
                        }
                    }
                },
                enabled = !isEmbedding && message.isNotEmpty() && key.isNotEmpty(),
                shape = MaterialTheme.shapes.medium,
                contentPadding = PaddingValues(vertical = 12.dp),
                modifier = Modifier.weight(1f),
            ) {
                if (isEmbedding) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("生成中...")
                } else {
                    Icon(Icons.Filled.AutoAwesome, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("生成含密图像")
                }
            }
        }

        // 结果
        stegoImageBase64?.let { base64 ->
            Spacer(modifier = Modifier.height(24.dp))
            EmbedResultCard(base64 = base64, context = context, scope = scope)
        }
    }
}

@Composable
private fun EmbedResultCard(
    base64: String,
    context: android.content.Context,
    scope: kotlinx.coroutines.CoroutineScope,
) {
    val imageData = base64.substringAfter("base64,")
    val bytes = AndroidBase64.decode(imageData, AndroidBase64.DEFAULT)
    val bitmap = remember(base64) { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "生成结果",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(modifier = Modifier.height(12.dp))
            bitmap?.let {
                Image(
                    bitmap = it.asImageBitmap(),
                    contentDescription = "含密图像",
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 180.dp, max = 360.dp),
                    contentScale = ContentScale.Fit,
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            Button(
                onClick = {
                    bitmap?.let { bmp ->
                        scope.launch {
                            val ok = withContext(Dispatchers.IO) {
                                saveImageToGallery(
                                    context,
                                    bmp,
                                    "stego_${System.currentTimeMillis()}.png",
                                )
                            }
                            withContext(Dispatchers.Main) {
                                Toast.makeText(
                                    context,
                                    if (ok) "已保存到相册" else "保存失败",
                                    Toast.LENGTH_SHORT,
                                ).show()
                            }
                        }
                    }
                },
                shape = MaterialTheme.shapes.medium,
                contentPadding = PaddingValues(vertical = 12.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Filled.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("保存到相册")
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "图像包含秘密消息,发送给接收者即可",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ExtractPane(
    context: android.content.Context,
    algorithmId: String,
    modelId: String,
) {
    val scope = rememberCoroutineScope()
    var selectedImageUri by remember { mutableStateOf<Uri?>(null) }
    var selectedImageFile by remember { mutableStateOf<File?>(null) }
    var key by remember { mutableStateOf("") }
    var keyVisible by remember { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(false) }
    var extractedMessage by remember { mutableStateOf<String?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    val clipboard = LocalClipboardManager.current

    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let {
            selectedImageUri = it
            val input = context.contentResolver.openInputStream(it)
            val file = File(context.cacheDir, "stego_picked_${System.currentTimeMillis()}.png")
            FileOutputStream(file).use { out -> input?.copyTo(out) }
            input?.close()
            selectedImageFile = file
        }
    }

    Column {
        // 图片选择/预览卡
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 160.dp),
        ) {
            if (selectedImageUri == null) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Image,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(48.dp),
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "点击选择含密图像",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Button(
                        onClick = { imagePicker.launch("image/*") },
                        shape = MaterialTheme.shapes.medium,
                    ) { Text("选择图像") }
                }
            } else {
                Box(modifier = Modifier.fillMaxWidth()) {
                    AsyncImage(
                        model = selectedImageUri,
                        contentDescription = "含密图像",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 280.dp),
                    )
                    FilledIconButton(
                        onClick = { imagePicker.launch("image/*") },
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                            contentColor = MaterialTheme.colorScheme.onSurface,
                        ),
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(8.dp),
                    ) {
                        Icon(Icons.Outlined.Image, contentDescription = "重新选择")
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        KeyInput(
            value = key,
            onValueChange = { if (it.length <= 64) key = it },
            visible = keyVisible,
            onToggleVisible = { keyVisible = !keyVisible },
            helper = "输入嵌入时使用的同一密钥",
        )

        Spacer(modifier = Modifier.height(16.dp))

        ErrorLine(errorMessage)

        Button(
            onClick = {
                if (!validateKey(key) { errorMessage = it }) return@Button
                val file = selectedImageFile
                if (file == null) {
                    errorMessage = "请先选择含密图像"
                    return@Button
                }
                scope.launch {
                    isLoading = true
                    errorMessage = null
                    extractedMessage = null
                    try {
                        val imagePart = MultipartBody.Part.createFormData(
                            "stego_image",
                            file.name,
                            file.asRequestBody("image/png".toMediaTypeOrNull()),
                        )
                        val keyPart = key.toRequestBody("text/plain".toMediaTypeOrNull())
                        val modelPart = modelId.toRequestBody("text/plain".toMediaTypeOrNull())
                        val algoPart = algorithmId.toRequestBody("text/plain".toMediaTypeOrNull())
                        val resp = withContext(Dispatchers.IO) {
                            ApiClient.stegoApi.extract(imagePart, keyPart, modelPart, algoPart)
                        }
                        if (resp.isSuccessful && resp.body()?.status == "success") {
                            extractedMessage = resp.body()?.secret_message
                        } else {
                            errorMessage = resp.body()?.error ?: "提取失败"
                        }
                    } catch (e: Exception) {
                        errorMessage = e.message ?: "网络错误"
                    } finally {
                        isLoading = false
                    }
                }
            },
            enabled = !isLoading && selectedImageFile != null && key.isNotEmpty(),
            shape = MaterialTheme.shapes.medium,
            contentPadding = PaddingValues(vertical = 14.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("提取中...")
            } else {
                Icon(Icons.Filled.LockOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("提取秘密消息")
            }
        }

        extractedMessage?.let { msg ->
            Spacer(modifier = Modifier.height(24.dp))
            ExtractResultCard(message = msg, onCopy = { clipboard.setText(AnnotatedString(msg)) })
        }
    }
}

@Composable
private fun ExtractResultCard(message: String, onCopy: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "提取结果",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                Surface(
                    color = MaterialTheme.colorScheme.tertiaryContainer,
                    shape = MaterialTheme.shapes.small,
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.Filled.CheckCircle,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onTertiaryContainer,
                            modifier = Modifier.size(14.dp),
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "成功",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
                Spacer(modifier = Modifier.width(8.dp))
                IconButton(onClick = onCopy) {
                    Icon(
                        imageVector = Icons.Outlined.ContentCopy,
                        contentDescription = "复制消息",
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = message,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "消息已从图像中解密,仅你可见",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun KeyInput(
    value: String,
    onValueChange: (String) -> Unit,
    visible: Boolean,
    onToggleVisible: () -> Unit,
    helper: String?,
) {
    Column {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            label = { Text("密钥 (1-64 字符)") },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Outlined.Key,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
            },
            trailingIcon = {
                IconButton(onClick = onToggleVisible) {
                    Icon(
                        imageVector = if (visible) Icons.Filled.Visibility else Icons.Outlined.VisibilityOff,
                        contentDescription = if (visible) "隐藏密钥" else "显示密钥",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
            singleLine = true,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth(),
        )
        if (helper != null) {
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = helper,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ErrorLine(message: String?) {
    if (message != null) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
        Spacer(modifier = Modifier.height(8.dp))
    }
}

private inline fun validateKey(key: String, crossinline onError: (String) -> Unit): Boolean =
    when {
        key.isEmpty() -> {
            onError("密钥不能为空")
            false
        }
        key.length > 64 -> {
            onError("密钥长度不能超过 64 字符")
            false
        }
        else -> true
    }

private fun saveImageToGallery(
    context: android.content.Context,
    bitmap: Bitmap,
    filename: String,
): Boolean = try {
    val outputStream: OutputStream?
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/StegoApp")
        }
        val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        outputStream = uri?.let { context.contentResolver.openOutputStream(it) }
    } else {
        val imagesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
        val dir = File(imagesDir, "StegoApp")
        if (!dir.exists()) dir.mkdirs()
        val imageFile = File(dir, filename)
        outputStream = FileOutputStream(imageFile)
    }
    outputStream?.use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    true
} catch (e: Exception) {
    e.printStackTrace()
    false
}

