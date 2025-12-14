package com.example.phonecamapp

import android.Manifest
import android.content.pm.ActivityInfo
import android.os.Bundle
import android.view.OrientationEventListener
import android.view.Surface
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.AspectRatio
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.paging.compose.collectAsLazyPagingItems
import com.example.phonecamapp.data.WebcamRepository
import com.example.phonecamapp.data.AppDatabase
import com.example.phonecamapp.data.LogEntity
import com.example.phonecamapp.network.RetrofitInstance
import com.example.phonecamapp.network.NsdServiceManager
import com.example.phonecamapp.ui.theme.PhoneCamTheme
import kotlinx.coroutines.flow.collectLatest
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.util.Log

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        this.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT

        setContent {
            PhoneCamTheme(darkTheme = false) {
                RequestPermissions()

                val context = LocalContext.current
                val application = context.applicationContext as android.app.Application

                val database = AppDatabase.getDatabase(context)
                val dao = database.settingsDao()
                val logDao = database.logDao()
                val api = RetrofitInstance.api

                val nsdManager = remember { NsdServiceManager(context) }

                val repository = remember { WebcamRepository(dao, logDao, api, nsdManager) }
                val viewModel: WebcamViewModel = viewModel(
                    factory = WebcamViewModelFactory(application, repository)
                )

                val orientationEventListener = remember {
                    object : OrientationEventListener(context) {
                        override fun onOrientationChanged(orientation: Int) {
                            if (orientation == ORIENTATION_UNKNOWN) return
                            val exactRotation = when (orientation) {
                                in 45..135 -> 270
                                in 135..225 -> 180
                                in 225..315 -> 90
                                else -> 0
                            }
                            viewModel.updateDeviceRotation(exactRotation)
                            val isLandscape = (exactRotation == 90 || exactRotation == 270)
                            viewModel.updateAutoOrientation(isLandscape)
                        }
                    }
                }

                DisposableEffect(Unit) {
                    orientationEventListener.enable()
                    onDispose {
                        orientationEventListener.disable()
                    }
                }

                AppNavigation(viewModel)
            }
        }
    }

    @Composable
    fun RequestPermissions() {
        val launcher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.RequestMultiplePermissions(),
            onResult = { }
        )
        LaunchedEffect(Unit) {
            launcher.launch(arrayOf(
                Manifest.permission.CAMERA,
                Manifest.permission.RECORD_AUDIO
            ))
        }
    }
}

data class ParameterItem(val label: String, val value: String)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppNavigation(viewModel: WebcamViewModel) {
    val navController = rememberNavController()
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(key1 = true) {
        viewModel.eventFlow.collectLatest { message ->
            snackbarHostState.showSnackbar(message = message, withDismissAction = true)
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    icon = { Icon(Icons.Default.Home, contentDescription = null) },
                    label = { Text("Головна") },
                    selected = navController.currentDestination?.route == "home",
                    onClick = { navController.navigate("home") }
                )
                NavigationBarItem(
                    icon = { Icon(Icons.Default.List, contentDescription = null) },
                    label = { Text("Історія") },
                    selected = navController.currentDestination?.route == "history",
                    onClick = { navController.navigate("history") }
                )
                NavigationBarItem(
                    icon = { Icon(Icons.Default.Settings, contentDescription = null) },
                    label = { Text("Налаштування") },
                    selected = navController.currentDestination?.route == "settings",
                    onClick = { navController.navigate("settings") }
                )
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = "home",
            modifier = Modifier.padding(innerPadding)
        ) {
            composable("home") {
                MainContent(
                    state = uiState,
                    onToggleStream = { viewModel.toggleStreaming() },
                    onProtocolClick = { protocol -> viewModel.updateProtocol(protocol) },
                    onSwitchCamera = { viewModel.toggleCameraLens() },
                    onToggleLock = { viewModel.toggleOrientationLock() },
                    onFrameAvailable = { frame, rotation -> viewModel.processFrame(frame, rotation) },
                    deviceRotation = viewModel.currentDeviceRotation
                )
            }
            composable("history") { LogHistoryScreen(viewModel) }
            composable("settings") { SettingsScreen(state = uiState) }
        }
    }
}

@Composable
fun SettingsScreen(state: WebcamUiState) {
    val parametersList = listOf(
        ParameterItem("Камера", state.cameraSettings.cameraName),
        ParameterItem("Роздільна здатність", "${state.cameraSettings.width}x${state.cameraSettings.height}"),
        ParameterItem("FPS", "${state.cameraSettings.fps}"),
        ParameterItem("IP Адреса", state.publicIp)
    )

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("Налаштування камери", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(16.dp))
        Text("Технічні параметри", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(8.dp))

        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(parametersList) { item ->
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                    Row(modifier = Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(item.label, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(item.value, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
fun MainContent(
    state: WebcamUiState,
    onToggleStream: () -> Unit,
    onProtocolClick: (String) -> Unit,
    onSwitchCamera: () -> Unit,
    onToggleLock: () -> Unit,
    onFrameAvailable: (ByteArray, Int) -> Unit,
    deviceRotation: Int,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxSize().padding(16.dp)) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = if (state.isStreaming) Color(0xFF2E7D32) else MaterialTheme.colorScheme.secondaryContainer
            )
        ) {
            Column(
                modifier = Modifier.padding(16.dp).fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = if (state.isStreaming) "ТРАНСЛЯЦІЯ АКТИВНА" else "ТРАНСЛЯЦІЯ ЗУПИНЕНА",
                    fontWeight = FontWeight.Bold,
                    color = if (state.isStreaming) Color.White else MaterialTheme.colorScheme.onSecondaryContainer
                )
                if (state.isStreaming) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = if (state.currentProtocol == "USB") "TCP (ADB Forward): 8554/8555" else state.publicIp,
                        color = Color.White.copy(alpha = 0.9f),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Button(
                onClick = { onProtocolClick("RTSP") },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (state.currentProtocol != "USB") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = if (state.currentProtocol != "USB") MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                ),
                shape = RoundedCornerShape(12.dp)
            ) { Text("Мережа") }

            Button(
                onClick = { onProtocolClick("USB") },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (state.currentProtocol == "USB") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = if (state.currentProtocol == "USB") MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                ),
                shape = RoundedCornerShape(12.dp)
            ) { Text("USB") }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            CameraPreviewScreen(
                isFrontCamera = state.isFrontCamera,
                isLandscape = state.isLandscape,
                deviceRotation = deviceRotation,
                isOrientationLocked = state.isOrientationLocked,
                lockedOrientation = state.lockedOrientation, // ПЕРЕДАЄМО ЗБЕРЕЖЕНИЙ КУТ
                onFrameCaptured = onFrameAvailable
            )

            FloatingActionButton(
                onClick = onSwitchCamera,
                modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
                containerColor = MaterialTheme.colorScheme.primaryContainer
            ) { Icon(Icons.Default.Refresh, contentDescription = "Змінити камеру") }

            SmallFloatingActionButton(
                onClick = onToggleLock,
                modifier = Modifier.align(Alignment.TopEnd).padding(16.dp),
                containerColor = if (state.isOrientationLocked) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.surfaceVariant
            ) {
                Icon(
                    imageVector = if (state.isOrientationLocked) Icons.Default.Lock else Icons.Default.LockOpen,
                    contentDescription = "Заблокувати орієнтацію",
                    tint = if (state.isOrientationLocked) MaterialTheme.colorScheme.onTertiary else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Button(
            onClick = onToggleStream,
            modifier = Modifier.fillMaxWidth().height(56.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (state.isStreaming) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
            )
        ) {
            Icon(Icons.Default.PlayArrow, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text(if (state.isStreaming) "Зупинити трансляцію" else "Запустити трансляцію")
        }
    }
}

@Composable
fun CameraPreviewScreen(
    isFrontCamera: Boolean,
    isLandscape: Boolean,
    deviceRotation: Int,
    isOrientationLocked: Boolean,
    lockedOrientation: Int,
    onFrameCaptured: (ByteArray, Int) -> Unit
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val context = LocalContext.current
    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }

    DisposableEffect(Unit) {
        onDispose { cameraExecutor.shutdown() }
    }

    val cameraSelector = if (isFrontCamera) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA

    AndroidView(
        factory = { ctx ->
            PreviewView(ctx).apply {
                implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                scaleType = PreviewView.ScaleType.FIT_CENTER
            }
        },
        modifier = Modifier.fillMaxSize(),
        update = { previewView ->
            val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
            cameraProviderFuture.addListener({
                val cameraProvider = cameraProviderFuture.get()

                // Фіксуємо ROTATION_0, щоб прев'ю на телефоні не стрибало
                val targetRotation = Surface.ROTATION_0

                val resolutionSelector = ResolutionSelector.Builder()
                    .setAspectRatioStrategy(AspectRatioStrategy(AspectRatio.RATIO_16_9, AspectRatioStrategy.FALLBACK_RULE_AUTO))
                    .build()

                val preview = Preview.Builder()
                    .setTargetRotation(targetRotation)
                    .setResolutionSelector(resolutionSelector)
                    .build()
                    .also { it.setSurfaceProvider(previewView.surfaceProvider) }

                val imageAnalyzer = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setResolutionSelector(resolutionSelector)
                    .setTargetRotation(targetRotation) // Теж фіксуємо 0
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                    .build()
                    .also {
                        it.setAnalyzer(cameraExecutor) { imageProxy ->
                            // 1. Отримуємо базовий кут сенсора (зазвичай 90 для задньої камери)
                            val sensorRotation = imageProxy.imageInfo.rotationDegrees

                            // 2. ВИПРАВЛЕНО: Якщо заблоковано, використовуємо збережений кут, інакше - поточний
                            val physicalRotation = if (isOrientationLocked) lockedOrientation else deviceRotation

                            // 3. Розраховуємо кут, який треба відправити на ПК.
                            val rotationToSend = (sensorRotation - physicalRotation + 360) % 360

                            val jpegBytes = yuv420ToJpeg(imageProxy)
                            if (jpegBytes != null) {
                                onFrameCaptured(jpegBytes, rotationToSend)
                            }
                            imageProxy.close()
                        }
                    }

                try {
                    cameraProvider.unbindAll()
                    cameraProvider.bindToLifecycle(
                        lifecycleOwner,
                        cameraSelector,
                        preview,
                        imageAnalyzer
                    )
                } catch (exc: Exception) {
                    Log.e("Camera", "Bind failed", exc)
                }
            }, ContextCompat.getMainExecutor(context))
        }
    )
}

fun yuv420ToJpeg(image: ImageProxy): ByteArray? {
    try {
        val yBuffer = image.planes[0].buffer
        val uBuffer = image.planes[1].buffer
        val vBuffer = image.planes[2].buffer

        val ySize = yBuffer.remaining()
        val uSize = uBuffer.remaining()
        val vSize = vBuffer.remaining()

        val nv21 = ByteArray(ySize + uSize + vSize)

        yBuffer.get(nv21, 0, ySize)

        val vPixelStride = image.planes[2].pixelStride
        val uPixelStride = image.planes[1].pixelStride

        if (vPixelStride == 2 && uPixelStride == 2 && uBuffer == vBuffer) {
            val vPos = ySize
            vBuffer.get(nv21, vPos, vSize)
        } else {
            var pos = ySize
            val width = image.width
            val height = image.height
            val uPlane = image.planes[1]
            val vPlane = image.planes[2]
            val uBuffer = uPlane.buffer
            val vBuffer = vPlane.buffer
            val uRowStride = uPlane.rowStride
            val vRowStride = vPlane.rowStride

            for (row in 0 until height / 2) {
                for (col in 0 until width / 2) {
                    val uIndex = row * uRowStride + col * uPixelStride
                    val vIndex = row * vRowStride + col * vPixelStride

                    nv21[pos++] = vBuffer.get(vIndex)
                    nv21[pos++] = uBuffer.get(uIndex)
                }
            }
        }

        val yuvImage = YuvImage(nv21, ImageFormat.NV21, image.width, image.height, null)
        val out = ByteArrayOutputStream()
        yuvImage.compressToJpeg(Rect(0, 0, image.width, image.height), 60, out)
        return out.toByteArray()
    } catch (e: Exception) {
        Log.e("YUV", "Error converting: ${e.message}")
        return null
    }
}

@Composable
fun LogHistoryScreen(viewModel: WebcamViewModel) {
    val logItems = viewModel.logsPagingFlow.collectAsLazyPagingItems()
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Історія подій", style = MaterialTheme.typography.headlineSmall)
            IconButton(onClick = { viewModel.clearLogs() }) { Icon(Icons.Default.Delete, contentDescription = "Очистити", tint = MaterialTheme.colorScheme.error) }
        }
        Spacer(modifier = Modifier.height(16.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(count = logItems.itemCount, key = { index -> logItems[index]?.id ?: index }) { index ->
                logItems[index]?.let { LogItemCard(it) }
            }
        }
    }
}

@Composable
fun LogItemCard(log: LogEntity) {
    val date = Date(log.timestamp)
    val format = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Row(modifier = Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = log.message, style = MaterialTheme.typography.bodyMedium)
                Text(text = log.type, style = MaterialTheme.typography.labelSmall, color = Color.Gray)
            }
            Text(text = format.format(date), style = MaterialTheme.typography.labelSmall)
        }
    }
}
