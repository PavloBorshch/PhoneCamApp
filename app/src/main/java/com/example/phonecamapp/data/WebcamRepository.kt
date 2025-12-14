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
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer

class WebcamRepository(
    private val settingsDao: SettingsDao,
    private val logDao: LogDao,
    private val apiService: IpApiService,
    private val nsdManager: NsdServiceManager
) {

    val settingsFlow: Flow<SettingsEntity?> = settingsDao.getSettings()

    private val audioStreamer = AudioStreamer()

    // --- TCP Video Server Variables ---
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

    // --- ВИПРАВЛЕНО: Отримання ЛОКАЛЬНОЇ IP-адреси ---
    suspend fun getPublicIp(): String = withContext(Dispatchers.IO) {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()

                // Фільтруємо: нас цікавлять не loopback (127.0.0.1) і тільки підняті інтерфейси
                if (networkInterface.isLoopback || !networkInterface.isUp) continue

                // Часто мобільний інтернет це rmnet, а wifi це wlan
                // Але простіше взяти першу IPv4 адресу, яка не є локальною
                val addresses = networkInterface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val address = addresses.nextElement()

                    // Беремо тільки IPv4 (не IPv6) і не 127.0.0.1
                    if (address is Inet4Address && !address.isLoopbackAddress) {
                        val ip = address.hostAddress
                        // Додаткова перевірка, щоб це була схожа на локальну адресу
                        if (ip != null && (ip.startsWith("192.168.") || ip.startsWith("10.") || ip.startsWith("172."))) {
                            return@withContext ip
                        }
                        // Якщо не знайшли стандартну локальну, повернемо хоча б якусь IPv4 (наприклад, точка доступу)
                        return@withContext ip ?: "Помилка IP"
                    }
                }
            }
            return@withContext "Немає мережі"
        } catch (e: Exception) {
            e.printStackTrace()
            return@withContext "Помилка IP"
        }
    }

    fun startDiscovery(port: Int) {
        nsdManager.registerService(port)
    }

    fun stopDiscovery() {
        nsdManager.tearDown()
    }

    // --- TCP Server Logic (VIDEO & AUDIO) ---
    suspend fun startTcpServer(videoPort: Int, audioPort: Int) = withContext(Dispatchers.IO) {
        audioStreamer.startServer(audioPort)

        if (isTcpServerRunning) return@withContext
        try {
            serverSocket = ServerSocket(videoPort)
            isTcpServerRunning = true
            addLog("TCP Servers: Video=$videoPort, Audio=$audioPort")
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
                        try { clientSocket?.close() } catch(e: Exception) {}

                        clientSocket = socket
                        outputStream = socket.getOutputStream()
                        Log.d("TCP", "Video Client connected: ${socket.inetAddress}")
                    }
                } catch (e: Exception) {
                    if (isTcpServerRunning) Log.e("TCP", "Accept error", e)
                }
            }
        }.start()
    }

    suspend fun stopTcpServer() = withContext(Dispatchers.IO) {
        audioStreamer.stopServer()

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
        addLog("Трансляцію зупинено")
    }

    fun sendFrame(jpegData: ByteArray, rotation: Int) {
        if (!isTcpServerRunning || outputStream == null) return
        if (jpegData.isEmpty()) return

        try {
            val size = jpegData.size
            val sizeBytes = ByteBuffer.allocate(4).putInt(size).array()
            val rotBytes = ByteBuffer.allocate(4).putInt(rotation).array()

            synchronized(this) {
                outputStream?.write(sizeBytes)
                outputStream?.write(rotBytes)
                outputStream?.write(jpegData)
                outputStream?.flush()
            }
        } catch (e: Exception) {
            Log.e("TCP", "Send error: ${e.message}")
            try { clientSocket?.close() } catch (_: Exception) {}
            clientSocket = null
            outputStream = null
        }
    }
}