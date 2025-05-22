package com.example.scamcalldetector

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.telecom.Call
import android.telecom.InCallService
import android.view.Gravity
import android.view.LayoutInflater
import android.view.WindowManager
import android.widget.PopupWindow
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
import kotlinx.coroutines.flow.flow
import java.util.concurrent.atomic.AtomicBoolean

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

    private val scamKeywords = listOf(
        "urgent",
        "gift card",
        "transfer",
        "social security",
        "warranty",
        "irs",
        "tax",
        "fraud department",
        "microsoft support",
        "apple support"
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
    }

    override fun onCallRemoved(call: Call) {
        super.onCallRemoved(call)
        stopRecording()
        currentCall = null
        speechClient?.close()
        speechClient = null
        dismissWarning()
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
            // For testing without credentials
            speechClient = SpeechClient.create()
            
            // TODO: For production, uncomment and use proper credentials
            /*
            val credentials = GoogleCredentials.fromStream(resources.openRawResource(R.raw.credentials))
            val speechSettings = SpeechSettings.newBuilder()
                .setCredentialsProvider { credentials }
                .build()
            speechClient = SpeechClient.create(speechSettings)
            */
        } catch (e: Exception) {
            e.printStackTrace()
            showNotification("Error", "Failed to initialize speech recognition")
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

    private fun processAudioStream(): Flow<String> = flow {
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
                    emit(transcription)
                }
            }

            override fun onError(t: Throwable) {
                t.printStackTrace()
            }

            override fun onCompleted() {
                // Stream completed
            }
        }

        val requestObserver = speechClient?.streamingRecognizeCallable()
            ?.bidiStreamingCall(responseObserver)

        requestObserver?.onNext(
            StreamingRecognizeRequest.newBuilder()
                .setStreamingConfig(streamingConfig)
                .build()
        )

        val buffer = ByteArray(2048)
        while (isRecording.get()) {
            val readSize = audioRecord?.read(buffer, 0, buffer.size) ?: -1
            if (readSize > 0) {
                requestObserver?.onNext(
                    StreamingRecognizeRequest.newBuilder()
                        .setAudioContent(ByteString.copyFrom(buffer, 0, readSize))
                        .build()
                )
            }
        }

        requestObserver?.onCompleted()
    }

    private fun checkForScamKeywords(transcription: String) {
        val lowercaseTranscription = transcription.toLowerCase()
        
        for (keyword in scamKeywords) {
            if (lowercaseTranscription.contains(keyword)) {
                showScamWarning(keyword)
                vibrate()
                return
            }
        }

        // Update transcription text
        binding.transcriptionText.post {
            binding.transcriptionText.text = transcription
        }
    }

    private fun showScamWarning(keyword: String) {
        showNotification(
            "⚠️ Potential Scam Detected!",
            "Suspicious keyword detected: $keyword"
        )
    }

    private fun showNotification(title: String, message: String) {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(message)
            .setSmallIcon(R.drawable.ic_warning)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    private fun dismissWarning() {
        binding.warningCard.post {
            binding.warningCard.visibility = android.view.View.GONE
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