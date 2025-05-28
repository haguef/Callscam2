package com.example.scamcalldetector

import android.app.AlertDialog
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.MediaScannerConnection
import android.os.Binder
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.telecom.Call
import android.telecom.CallAudioState
import android.telecom.InCallService
import android.telecom.VideoProfile
import android.util.Log
import android.widget.PopupWindow
import android.widget.Toast
import androidx.core.content.getSystemService
import com.example.scamcalldetector.databinding.ActivityMainBinding
import com.google.api.gax.rpc.ApiStreamObserver
import com.google.api.gax.rpc.BidiStreamingCallable
import com.google.auth.oauth2.GoogleCredentials
import com.google.cloud.speech.v1.RecognitionConfig
import com.google.cloud.speech.v1.SpeechClient
import com.google.cloud.speech.v1.SpeechSettings
import com.google.cloud.speech.v1.StreamingRecognitionConfig
import com.google.cloud.speech.v1.StreamingRecognizeRequest
import com.google.cloud.speech.v1.StreamingRecognizeResponse
import com.google.protobuf.ByteString
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

class CallDetectionService : InCallService() {
    private var currentCall: Call? = null
    private var audioRecord: AudioRecord? = null
    private var recorder: MediaRecorder? = null

    private var isRecording = AtomicBoolean(false)
    private var recordingJob: Job? = null
    private var speechClient: SpeechClient? = null
    private var popupWindow: PopupWindow? = null
    private lateinit var binding: ActivityMainBinding
    private val NOTIFICATION_CHANNEL_ID = "ScamDetectorChannel"
    private val NOTIFICATION_ID = 1
    private val mainHandler = Handler(Looper.getMainLooper())

    private val TAG: String = "mytag"

    private var binder: IBinder = LocalBinder()

    inner class LocalBinder : Binder() {
        fun getService(): CallDetectionService {
            return this@CallDetectionService
        }
    }

    private val scamKeywords = listOf(
        "gift card"
    )

    override fun onBind(intent: Intent?): IBinder? {
        Log.i(TAG, "Binded by : ${intent}")
        if (intent?.action == "DirectBind") {
            return binder
        }
        return super.onBind(intent)
    }

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "Service Started.")
        createNotificationChannel()
        initializeSpeechClient()
    }

    override fun onCallAdded(call: Call) {
        super.onCallAdded(call)
        Log.i(TAG, "Call added.")
        currentCall = call

        setupCallStateCallback(call)
       }

    override fun onCallRemoved(call: Call) {
        super.onCallRemoved(call)
        Log.i(TAG, "Call intercepted.")

        currentCall = null
        speechClient?.close()
        speechClient = null
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID, "Scam Call Detection", NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Shows warnings about potential scam calls"
        }
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.createNotificationChannel(channel)
    }

    private fun toggleSpeakerphone(enabled: Boolean) {
        val audioState = callAudioState
        if (audioState != null) {
            setAudioRoute(if (enabled) CallAudioState.ROUTE_SPEAKER else CallAudioState.ROUTE_WIRED_OR_EARPIECE)
        }
    }

    private fun setupCallStateCallback(call: Call) {
        call.answer(VideoProfile.STATE_AUDIO_ONLY)
        call.registerCallback(object : Call.Callback() {
            override fun onStateChanged(call: Call, state: Int) {
                when (state) {
                    Call.STATE_RINGING -> {
                        Log.i(TAG, "Incoming call is ringing")
                    }

                    Call.STATE_ACTIVE -> {
                        Log.i(TAG, "Call is active")
                        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
                        audioManager.mode = AudioManager.MODE_IN_CALL
                        audioManager.isSpeakerphoneOn = true
                        startRecording()
                    }

                    Call.STATE_DISCONNECTED -> {
                        Log.i(TAG, "Call ended")
                        stopRecording()
                    }

                    Call.STATE_AUDIO_PROCESSING -> {
                        Log.i(TAG, "STATE_AUDIO_PROCESSING")
                    }

                    Call.STATE_CONNECTING -> {
                        Log.i(TAG, "STATE_CONNECTING")
                    }

                    Call.STATE_DIALING -> {
                        Log.i(TAG, "STATE_DIALING")
                    }

                    Call.STATE_DISCONNECTING -> {
                        Log.i(TAG, "STATE_DISCONNECTING")
                    }

                    Call.STATE_HOLDING -> {
                        Log.i(TAG, "STATE_HOLDING")
                    }

                    Call.STATE_NEW -> {
                        Log.i(TAG, "STATE_NEW")
                    }

                    Call.STATE_PULLING_CALL -> {
                        Log.i(TAG, "STATE_PULLING_CALL")
                    }

                    Call.STATE_SELECT_PHONE_ACCOUNT -> {
                        Log.i(TAG, "STATE_SELECT_PHONE_ACCOUNT")
                    }

                    Call.STATE_SIMULATED_RINGING -> {
                        Log.i(TAG, "STATE_SIMULATED_RINGING")
                    }
                }
            }
        })
    }

    private fun initializeSpeechClient() {
        try {
            // Load credentials from assets
            val inputStream = assets.open("credentials.json")
            val credentials = try {
                GoogleCredentials.fromStream(inputStream)
            } catch (e: Exception) {
                throw IllegalStateException("Failed to parse credentials.json: ${e.message}", e)
            } finally {
                inputStream.close()
            }

            // Create speech settings with credentials
            val speechSettings =
                SpeechSettings.newBuilder().setCredentialsProvider { credentials }.build()

            // Initialize speech client with credentials
            speechClient = SpeechClient.create(speechSettings)
            showToast("Speech client initialized successfully")

        } catch (e: Exception) {
            val errorMessage = "Speech recognition initialization failed: ${e.message}"
            e.printStackTrace()
            showToast(errorMessage)
        }
    }

    private fun showToast(message: String) {
        mainHandler.post {
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        }
    }

    private fun showScamAlert() {
        mainHandler.post {
            AlertDialog.Builder(this).setTitle("⚠️ SCAM CALL ALERT!")
                .setMessage("Gift card scam detected! Would you like to end the call?")
                .setPositiveButton("End Call") { _, _ ->
                    currentCall?.disconnect()
                }.setNegativeButton("Continue") { dialog, _ ->
                    dialog.dismiss()
                }.setCancelable(false).show()
        }
    }

    private lateinit var outputFile: File

    private fun startRecording() {
        val outputDir =
            getExternalFilesDir(Environment.DIRECTORY_RECORDINGS)  // Or DIRECTORY_RECORDINGS
        var filename =
            SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date()) + "_local.mp4"
        outputFile = File(outputDir, filename)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            recorder = MediaRecorder(this).apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioEncodingBitRate(128000)
                setAudioSamplingRate(44100)
                setOutputFile(outputFile.absolutePath)
                prepare()
                start()
                Log.i(TAG, "startRecording ${outputFile.absolutePath}")
            }
        }
    }

    private fun stopRecording() {
        recorder?.apply {
            stop()
            release()
            Log.i(TAG, "stopRecording")
            MediaScannerConnection.scanFile(
                this@CallDetectionService, arrayOf(outputFile.absolutePath), null, null
            )
        }
        recorder = null
    }

    private fun processAudioStream(): Flow<String> = channelFlow {
        val recognitionConfig =
            RecognitionConfig.newBuilder().setEncoding(RecognitionConfig.AudioEncoding.LINEAR16)
                .setSampleRateHertz(16000).setLanguageCode("en-US").build()

        val streamingConfig = StreamingRecognitionConfig.newBuilder().setConfig(recognitionConfig)
            .setInterimResults(true).build()

        val responseObserver = object : ApiStreamObserver<StreamingRecognizeResponse> {
            override fun onNext(response: StreamingRecognizeResponse) {
                val transcription =
                    response.resultsList.flatMap { it.alternativesList }.firstOrNull()?.transcript
                        ?: ""
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
            speechClient?.streamingRecognizeCallable()
                ?: throw IllegalStateException("Speech client not initialized")

        val requestObserver = callable.bidiStreamingCall(responseObserver, null)

        requestObserver.onNext(
            StreamingRecognizeRequest.newBuilder().setStreamingConfig(streamingConfig).build()
        )

        val buffer = ByteArray(2048)
        while (isRecording.get()) {
            val readSize = audioRecord?.read(buffer, 0, buffer.size) ?: -1
            if (readSize > 0) {
                val maxAmplitude = buffer.maxOrNull()?.toInt() ?: 0
                Log.i("AudioDebug Service", "Max amplitude: $maxAmplitude")
                requestObserver.onNext(
                    StreamingRecognizeRequest.newBuilder()
                        .setAudioContent(ByteString.copyFrom(buffer, 0, readSize)).build()
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
        Log.i(TAG, "checkForScamKeywords: $transcription")

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
        vibrator?.vibrate(VibrationEffect.createOneShot(500, VibrationEffect.DEFAULT_AMPLITUDE))
    }
} 