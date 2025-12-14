package com.example.phonecamapp.data

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket

class AudioStreamer {
    private var serverSocket: ServerSocket? = null
    private var clientSocket: Socket? = null
    private var outputStream: OutputStream? = null
    private var audioRecord: AudioRecord? = null

    @Volatile
    private var isStreaming = false

    // Налаштування звуку: 44.1kHz, 16-bit, Mono
    // Це стандарт, який легко відтворити на ПК
    private val sampleRate = 44100
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT
    private val bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)

    // Запуск сервера очікування підключення
    suspend fun startServer(port: Int) = withContext(Dispatchers.IO) {
        if (isStreaming) return@withContext

        try {
            serverSocket = ServerSocket(port)
            isStreaming = true
            Log.d("Audio", "Audio Server started on port $port")

            // Окремий потік для прийняття клієнта та стрімінгу
            Thread {
                acceptAndStream()
            }.start()

        } catch (e: Exception) {
            Log.e("Audio", "Start error", e)
        }
    }

    private fun acceptAndStream() {
        while (isStreaming) {
            try {
                // Блокуюче очікування підключення ПК
                val socket = serverSocket?.accept()
                if (socket != null) {
                    clientSocket?.close()
                    clientSocket = socket
                    outputStream = socket.getOutputStream()
                    Log.d("Audio", "PC Connected for Audio")

                    // Починаємо запис і передачу
                    streamAudioLoop()
                }
            } catch (e: Exception) {
                if (isStreaming) Log.e("Audio", "Accept error", e)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun streamAudioLoop() {
        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                channelConfig,
                audioFormat,
                bufferSize
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                Log.e("Audio", "AudioRecord init failed")
                return
            }

            audioRecord?.startRecording()
            val buffer = ByteArray(bufferSize)

            // Цикл читання з мікрофона та відправки в мережу
            while (isStreaming && clientSocket?.isConnected == true && !clientSocket!!.isClosed) {
                val read = audioRecord?.read(buffer, 0, bufferSize) ?: 0
                if (read > 0) {
                    try {
                        outputStream?.write(buffer, 0, read)
                    } catch (e: Exception) {
                        Log.e("Audio", "Send error (client disconnected?)", e)
                        break
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("Audio", "Stream loop error", e)
        } finally {
            stopAudioRecord()
        }
    }

    private fun stopAudioRecord() {
        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        audioRecord = null
    }

    suspend fun stopServer() = withContext(Dispatchers.IO) {
        isStreaming = false
        stopAudioRecord()
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
    }
}