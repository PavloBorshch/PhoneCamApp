package com.example.phonecamapp

import android.Manifest
import android.content.pm.ActivityInfo
import android.os.Bundle
import android.view.Surface
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.AspectRatio
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// Основна программа
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Жорстка фіксація портретної орієнтації додатку
        this.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT

        setContent {
            PhoneCamTheme(darkTheme = false) {
                RequestCameraPermission()

                val context = LocalContext.current
                val application = context.applicationContext as android.app.Application

                val database = AppDatabase.getDatabase(context)
                val dao = database.settingsDao()
                val logDao = database.logDao()
                val api = RetrofitInstance.api

                // Ініціалізація NSD Manager
                val nsdManager = remember { NsdServiceManager(context) }

                val repository = remember { WebcamRepository(dao, logDao, api, nsdManager) }
                val viewModel: WebcamViewModel = viewModel(
                    factory = WebcamViewModelFactory(application, repository)
                )

                AppNavigation(viewModel)
            }
        }
    }

    @Composable
    fun RequestCameraPermission() {
        val launcher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.RequestPermission(),
            onResult = { }
        )
        LaunchedEffect(Unit) {
            launcher.launch(Manifest.permission.CAMERA)
        }
    }
}

data class ParameterItem(val label: String, val value: String)

// Navigation Component
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
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Webcam Controller") },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            )
        },
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
                    onSwitchCamera = { viewModel.toggleCameraLens() }
                )
            }
            composable("history") { LogHistoryScreen(viewModel) }
            composable("settings") {
                SettingsScreen(
                    state = uiState,
                    onOrientationChange = { isLandscape -> viewModel.toggleOrientation(isLandscape) }
                )
            }
        }
    }
}

// SETTINGS SCREEN
@Composable
fun SettingsScreen(
    state: WebcamUiState,
    onOrientationChange: (Boolean) -> Unit
) {
    val parametersList = listOf(
        ParameterItem("Камера", state.cameraSettings.cameraName),
        ParameterItem("Роздільна здатність", "${state.cameraSettings.width}x${state.cameraSettings.height}"),
        ParameterItem("FPS", "${state.cameraSettings.fps}"),
        ParameterItem("IP Адреса", state.publicIp)
    )

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("Налаштування камери", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(16.dp))

        // Блок керування орієнтацією
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "Орієнтація камери",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = if (state.isLandscape) "Горизонтальна (Landscape 16:9)" else "Вертикальна (Portrait 9:16)",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Switch(
                    checked = state.isLandscape,
                    onCheckedChange = { onOrientationChange(it) }
                )
            }
        }

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

// HOME SCREEN
@Composable
fun MainContent(
    state: WebcamUiState,
    onToggleStream: () -> Unit,
    onProtocolClick: (String) -> Unit,
    onSwitchCamera: () -> Unit,
    modifier: Modifier = Modifier
) {
    val protocols = listOf("RTSP", "HTTP", "MJPEG", "USB")

    Column(modifier = modifier.fillMaxSize().padding(16.dp)) {
        // Статус
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
                        text = "rtsp://${state.publicIp}:554/live",
                        color = Color.White.copy(alpha = 0.9f),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Протоколи
        Text("Протокол трансляції:", style = MaterialTheme.typography.labelMedium, color = Color.Gray)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(protocols) { protocol ->
                SuggestionChip(
                    onClick = { onProtocolClick(protocol) },
                    label = { Text(protocol) },
                    colors = SuggestionChipDefaults.suggestionChipColors(
                        containerColor = if (protocol == state.currentProtocol)
                            MaterialTheme.colorScheme.primaryContainer else Color.Transparent
                    )
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // ОБЛАСТЬ КАМЕРИ
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            CameraPreviewScreen(
                isFrontCamera = state.isFrontCamera,
                isLandscape = state.isLandscape
            )

            FloatingActionButton(
                onClick = onSwitchCamera,
                modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
                containerColor = MaterialTheme.colorScheme.primaryContainer
            ) {
                Icon(Icons.Default.Refresh, contentDescription = "Змінити камеру")
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

// CAMERA PREVIEW
@Composable
fun CameraPreviewScreen(
    isFrontCamera: Boolean,
    isLandscape: Boolean
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val context = LocalContext.current

    val cameraSelector = if (isFrontCamera) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA

    AndroidView(
        factory = { ctx ->
            PreviewView(ctx).apply {
                // Використовуємо TextureView (COMPATIBLE), щоб краще працювати з нестандартними ротаціями
                implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                scaleType = PreviewView.ScaleType.FIT_CENTER
            }
        },
        modifier = Modifier.fillMaxSize(),
        update = { previewView ->
            val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
            cameraProviderFuture.addListener({
                val cameraProvider = cameraProviderFuture.get()

                // Орієнтація
                // Якщо Landscape, повертаємо на 90.
                // Якщо Portrait, ставимо 0.
                val targetRotation = if (isLandscape) Surface.ROTATION_90 else Surface.ROTATION_0

                // Співвідношення
                // Завжди вимагаємо 16:9
                val resolutionSelector = ResolutionSelector.Builder()
                    .setAspectRatioStrategy(AspectRatioStrategy(AspectRatio.RATIO_16_9, AspectRatioStrategy.FALLBACK_RULE_AUTO))
                    .build()

                val preview = Preview.Builder()
                    .setTargetRotation(targetRotation)
                    .setResolutionSelector(resolutionSelector)
                    .build()
                    .also {
                        it.setSurfaceProvider(previewView.surfaceProvider)
                    }

                try {
                    cameraProvider.unbindAll()
                    cameraProvider.bindToLifecycle(
                        lifecycleOwner,
                        cameraSelector,
                        preview
                    )
                } catch (exc: Exception) {
                    // Log error
                }
            }, ContextCompat.getMainExecutor(context))
        }
    )
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