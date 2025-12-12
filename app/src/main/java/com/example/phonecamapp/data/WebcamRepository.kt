package com.example.phonecamapp.data

import android.util.Log
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import com.example.phonecamapp.network.IpApiService
import com.example.phonecamapp.network.NsdServiceManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer

// Клас, що виступає посередником між даними
class WebcamRepository(
    private val settingsDao: SettingsDao,
    private val logDao: LogDao,
    private val apiService: IpApiService,
    private val nsdManager: NsdServiceManager
) {

    val settingsFlow: Flow<SettingsEntity?> = settingsDao.getSettings()

    // --- TCP Server Variables ---
    private var serverSocket: ServerSocket? = null
    private var clientSocket: Socket? = null
    private var outputStream: OutputStream? = null
    @Volatile private var isTcpServerRunning = false

    fun getPagedLogs(): Flow<PagingData<LogEntity>> {
        return Pager(
            config = PagingConfig(pageSize = 20, enablePlaceholders = false, initialLoadSize = 20),
            pagingSourceFactory = { logDao.getAllLogs() }
        ).flow
    }

    suspend fun addLog(message: String, type: String = "INFO") {
        logDao.insertLog(LogEntity(message = message, type = type))
    }

    suspend fun clearAllLogs() {
        logDao.clearLogs()
    }

    suspend fun saveSettings(settings: SettingsEntity) {
        settingsDao.saveSettings(settings)
    }

    suspend fun getPublicIp(): String {
        return try {
            val response = apiService.getPublicIp()
            response.ip
        } catch (e: Exception) {
            "Офлайн режим"
        }
    }

    // --- NSD Methods ---
    fun startDiscovery(port: Int) {
        nsdManager.registerService(port)
    }

    fun stopDiscovery() {
        nsdManager.tearDown()
    }

    // --- TCP Server Logic for USB Mode ---
    suspend fun startTcpServer(port: Int) = withContext(Dispatchers.IO) {
        if (isTcpServerRunning) return@withContext
        try {
            serverSocket = ServerSocket(port)
            isTcpServerRunning = true
            addLog("TCP Сервер запущено на порту $port")
            newClientLoop()
        } catch (e: Exception) {
            addLog("Помилка старту TCP: ${e.message}", "ERROR")
            e.printStackTrace()
        }
    }

    private fun newClientLoop() {
        Thread {
            while (isTcpServerRunning) {
                try {
                    val socket = serverSocket?.accept()
                    if (socket != null) {
                        // Якщо вже є активний клієнт, закриваємо старого (проста логіка 1-до-1)
                        try { clientSocket?.close() } catch(e: Exception) {}

                        clientSocket = socket
                        outputStream = socket.getOutputStream()
                        Log.d("TCP", "Client connected: ${socket.inetAddress}")
                    }
                } catch (e: Exception) {
                    if (isTcpServerRunning) Log.e("TCP", "Accept error", e)
                }
            }
        }.start()
    }

    suspend fun stopTcpServer() = withContext(Dispatchers.IO) {
        // Відправка повідомлення про завершення (EOS - End Of Stream)
        // Використовуємо size = 0 як сигнал
        if (isTcpServerRunning && outputStream != null) {
            try {
                synchronized(this@WebcamRepository) {
                    val eosSize = ByteBuffer.allocate(4).putInt(0).array()
                    val dummyRot = ByteBuffer.allocate(4).putInt(0).array()
                    outputStream?.write(eosSize)
                    outputStream?.write(dummyRot)
                    outputStream?.flush()
                }
            } catch (e: Exception) {
                Log.e("TCP", "Failed to send EOS: ${e.message}")
            }
        }

        isTcpServerRunning = false
        try {
            outputStream?.close()
            clientSocket?.close()
            serverSocket?.close()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        outputStream = null
        clientSocket = null
        serverSocket = null
        addLog("TCP Сервер зупинено")
    }

    // ОНОВЛЕНО: Додано аргумент rotation
    // Формат: [4 байти розміру][4 байти кута][байти зображення]
    fun sendFrame(jpegData: ByteArray, rotation: Int) {
        if (!isTcpServerRunning || outputStream == null) return

        // Ігноруємо порожні кадри, щоб не плутати з EOS (size 0)
        if (jpegData.isEmpty()) return

        try {
            val size = jpegData.size

            // 1. Розмір (4 байти)
            val sizeBytes = ByteBuffer.allocate(4).putInt(size).array()
            // 2. Кут (4 байти)
            val rotBytes = ByteBuffer.allocate(4).putInt(rotation).array()

            synchronized(this) {
                outputStream?.write(sizeBytes)
                outputStream?.write(rotBytes) // Відправка кута
                outputStream?.write(jpegData)
                outputStream?.flush()
            }
        } catch (e: Exception) {
            Log.e("TCP", "Send error: ${e.message}")
            try {
                clientSocket?.close()
            } catch (_: Exception) {}
            clientSocket = null
            outputStream = null
        }
    }
}