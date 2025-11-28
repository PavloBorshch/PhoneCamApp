package com.example.phonecamapp.data

import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import com.example.phonecamapp.network.IpApiService
import kotlinx.coroutines.flow.Flow

// Клас, що виступає посередником між даними
class WebcamRepository(
    private val settingsDao: SettingsDao,
    private val logDao: LogDao,
    private val apiService: IpApiService
) {

    val settingsFlow: Flow<SettingsEntity?> = settingsDao.getSettings()

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
}