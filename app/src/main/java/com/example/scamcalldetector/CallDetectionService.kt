package com.example.scamcalldetector

import android.app.AlertDialog
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.telecom.Call
import android.telecom.CallAudioState
import android.telecom.InCallService
import android.view.Gravity
import android.view.LayoutInflater
import android.view.WindowManager
import android.widget.PopupWindow
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.getSystemService
import com.example.scamcalldetector.databinding.ActivityMainBinding
import com.google.auth.oauth2.GoogleCredentials
import com.google.cloud.speech.v1.RecognitionConfig
import com.google.cloud.speech.v1.SpeechClient
import com.google.cloud.speech.v1.SpeechSettings
import com.google.cloud.speech.v1.StreamingRecognitionConfig
import com.google.cloud.speech.v1.StreamingRecognizeRequest
import com.google.cloud.speech.v1.StreamingRecognizeResponse
import com.google.api.gax.rpc.ApiStreamObserver
import com.google.protobuf.ByteString
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.channelFlow
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.channels.awaitClose
import com.google.api.gax.rpc.BidiStreamingCallable

class CallDetectionService : InCallService() {
    private var currentCall: Call? = null
    private var audioRecord: AudioRecord? = null
    private var isRecording = AtomicBoolean(false)
    private var recordingJob: Job? = null
    private var speechClient: SpeechClient? = null
    private var popupWindow: PopupWindow? = null
    private lateinit var binding: ActivityMainBinding
    private val NOTIFICATION_CHANNEL_ID = "ScamDetectorChannel"
    private val NOTIFICATION_ID = 1
    private val mainHandler = Handler(Looper.getMainLooper())

    private val scamKeywords = listOf(
        "gift card"
    )

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        initializeSpeechClient()
    }

    override fun onCallAdded(call: Call) {
        super.onCallAdded(call)
        currentCall = call
        setupCallStateCallback(call)
        showSpeakerphonePrompt()
    }

    override fun onCallRemoved(call: Call) {
        super.onCallRemoved(call)
        stopRecording()
        currentCall = null
        speechClient?.close()
        speechClient = null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Scam Call Detection",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Shows warnings about potential scam calls"
            }
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun showSpeakerphonePrompt() {
        mainHandler.post {
            Toast.makeText(this, "Would you like to enable speaker phone?", Toast.LENGTH_LONG).show()
            AlertDialog.Builder(this)
                .setTitle("Speaker Phone")
                .setMessage("Would you like to enable speaker phone?")
                .setPositiveButton("Yes") { _, _ ->
                    toggleSpeakerphone(true)
                }
                .setNegativeButton("No") { dialog, _ ->
                    dialog.dismiss()
                }
                .show()
        }
    }

    private fun toggleSpeakerphone(enabled: Boolean) {
        val audioState = callAudioState
        if (audioState != null) {
            setAudioRoute(if (enabled) CallAudioState.ROUTE_SPEAKER else CallAudioState.ROUTE_WIRED_OR_EARPIECE)
        }
    }

    private fun setupCallStateCallback(call: Call) {
        call.registerCallback(object : Call.Callback() {
            override fun onStateChanged(call: Call, state: Int) {
                when (state) {
                    Call.STATE_ACTIVE -> startRecording()
                    Call.STATE_DISCONNECTED -> stopRecording()
                }
            }
        })
    }

    private fun initializeSpeechClient() {
        try {
            speechClient = SpeechClient.create()
        } catch (e: Exception) {
            e.printStackTrace()
            showToast("Error: Failed to initialize speech recognition")
        }
    }

    private fun showToast(message: String) {
        mainHandler.post {
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        }
    }

    private fun showScamAlert() {
        mainHandler.post {
            AlertDialog.Builder(this)
                .setTitle("⚠️ SCAM CALL ALERT!")
                .setMessage("Gift card scam detected! Would you like to end the call?")
                .setPositiveButton("End Call") { _, _ ->
                    currentCall?.disconnect()
                }
                .setNegativeButton("Continue") { dialog, _ ->
                    dialog.dismiss()
                }
                .setCancelable(false)
                .show()
        }
    }

    private fun startRecording() {
        if (isRecording.get()) return

        val bufferSize = AudioRecord.getMinBufferSize(
            16000,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )

        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            16000,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize
        )

        isRecording.set(true)
        recordingJob = CoroutineScope(Dispatchers.IO).launch {
            processAudioStream().collect { transcription ->
                checkForScamKeywords(transcription)
            }
        }

        audioRecord?.startRecording()
    }

    private fun stopRecording() {
        isRecording.set(false)
        recordingJob?.cancel()
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
    }

    private fun processAudioStream(): Flow<String> = channelFlow {
        val recognitionConfig = RecognitionConfig.newBuilder()
            .setEncoding(RecognitionConfig.AudioEncoding.LINEAR16)
            .setSampleRateHertz(16000)
            .setLanguageCode("en-US")
            .build()

        val streamingConfig = StreamingRecognitionConfig.newBuilder()
            .setConfig(recognitionConfig)
            .setInterimResults(true)
            .build()

        val responseObserver = object : ApiStreamObserver<StreamingRecognizeResponse> {
            override fun onNext(response: StreamingRecognizeResponse) {
                val transcription = response.resultsList
                    .flatMap { it.alternativesList }
                    .firstOrNull()
                    ?.transcript ?: ""
                if (transcription.isNotEmpty()) {
                    trySend(transcription)
                }
            }

            override fun onError(t: Throwable) {
                t.printStackTrace()
                close(t)
            }

            override fun onCompleted() {
                close()
            }
        }

        val callable: BidiStreamingCallable<StreamingRecognizeRequest, StreamingRecognizeResponse> =
            speechClient?.streamingRecognizeCallable() ?: throw IllegalStateException("Speech client not initialized")
        
        val requestObserver = callable.bidiStreamingCall(responseObserver, null)

        requestObserver.onNext(
            StreamingRecognizeRequest.newBuilder()
                .setStreamingConfig(streamingConfig)
                .build()
        )

        val buffer = ByteArray(2048)
        while (isRecording.get()) {
            val readSize = audioRecord?.read(buffer, 0, buffer.size) ?: -1
            if (readSize > 0) {
                requestObserver.onNext(
                    StreamingRecognizeRequest.newBuilder()
                        .setAudioContent(ByteString.copyFrom(buffer, 0, readSize))
                        .build()
                )
            }
        }

        requestObserver.onCompleted()
        
        awaitClose {
            requestObserver.onCompleted()
        }
    }

    private fun checkForScamKeywords(transcription: String) {
        val lowercaseTranscription = transcription.lowercase()
        
        // Show real-time transcription
        showToast("Transcription: $transcription")
        
        for (keyword in scamKeywords) {
            if (lowercaseTranscription.contains(keyword)) {
                showScamAlert()
                vibrate()
                return
            }
        }
    }

    private fun vibrate() {
        val vibrator = getSystemService<Vibrator>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator?.vibrate(VibrationEffect.createOneShot(500, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            vibrator?.vibrate(500)
        }
    }
} 