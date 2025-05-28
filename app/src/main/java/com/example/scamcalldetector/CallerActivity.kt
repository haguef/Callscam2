package com.example.scamcalldetector

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.telecom.Call
import android.util.Log
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.getSystemService
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
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
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

class CallerActivity : AppCompatActivity() {
    val TAG: String = "mytag"

    private var audioRecord: AudioRecord? = null
    private var isRecording = AtomicBoolean(false)
    private var recordingJob: Job? = null
    private var speechClient: SpeechClient? = null
    private var currentCall: Call? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private val scamKeywords = listOf(
        "gift card"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_caller)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        initializeSpeechClient()
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
            startRecording()

        } catch (e: Exception) {
            val errorMessage = "Speech recognition initialization failed: ${e.message}"
            e.printStackTrace()
            showToast(errorMessage)
        }
    }

    private fun startRecording() {
        Log.i(TAG, "startRecording... 1")
        if (isRecording.get()) return

        val bufferSize = AudioRecord.getMinBufferSize(
            16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )

        if (ActivityCompat.checkSelfPermission(
                this, Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            // TODO: Consider calling
            //    ActivityCompat#requestPermissions
            // here to request the missing permissions, and then overriding
            //   public void onRequestPermissionsResult(int requestCode, String[] permissions,
            //                                          int[] grantResults)
            // to handle the case where the user grants the permission. See the documentation
            // for ActivityCompat#requestPermissions for more details.
            return
        }
        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            16000,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize
        )

        Log.i(TAG, "startRecording...2")
        isRecording.set(true)
        recordingJob = GlobalScope.launch {
            Log.i(TAG, "Coroutine launched...")
            processAudioStream().collect { transcription ->
                Log.i(TAG, "Coroutine launched...1")
                checkForScamKeywords(transcription)
            }
        }

        audioRecord?.startRecording()
        Log.i(TAG, "startRecording...3")
    }

    private fun stopRecording() {
        isRecording.set(false)
        recordingJob?.cancel()
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
    }

    private fun checkForScamKeywords(transcription: String) {
        Log.i(TAG, "checkForScamKeywords...1")
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

        Log.i(TAG, "checkForScamKeywords...2")
    }

    private fun showScamAlert() {
        mainHandler.post {
            android.app.AlertDialog.Builder(this).setTitle("⚠️ SCAM CALL ALERT!")
                .setMessage("Gift card scam detected! Would you like to end the call?")
                .setPositiveButton("End Call") { _, _ ->
                    currentCall?.disconnect()
                }.setNegativeButton("Continue") { dialog, _ ->
                    dialog.dismiss()
                }.setCancelable(false).show()
        }
    }

    private fun showToast(message: String) {
        mainHandler.post {
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        }
    }

    private fun vibrate() {
        val vibrator = getSystemService<Vibrator>()
        vibrator?.vibrate(VibrationEffect.createOneShot(500, VibrationEffect.DEFAULT_AMPLITUDE))
    }

    private fun processAudioStream(): Flow<String> = channelFlow {
        Log.i(TAG, "processAudioStream 1")
        val recognitionConfig =
            RecognitionConfig.newBuilder().setEncoding(RecognitionConfig.AudioEncoding.LINEAR16)
                .setSampleRateHertz(16000).setLanguageCode("en-US").build()

        Log.i(TAG, "processAudioStream 2")
        val streamingConfig = StreamingRecognitionConfig.newBuilder().setConfig(recognitionConfig)
            .setInterimResults(true).build()

        Log.i(TAG, "processAudioStream 3")
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
        Log.i(TAG, "processAudioStream 4")
        val callable: BidiStreamingCallable<StreamingRecognizeRequest, StreamingRecognizeResponse> =
            speechClient?.streamingRecognizeCallable()
                ?: throw IllegalStateException("Speech client not initialized")

        val requestObserver = callable.bidiStreamingCall(responseObserver, null)

        requestObserver.onNext(
            StreamingRecognizeRequest.newBuilder().setStreamingConfig(streamingConfig).build()
        )

        Log.i(TAG, "processAudioStream 5")
        val buffer = ByteArray(2048)
        while (isRecording.get()) {
            val readSize = audioRecord?.read(buffer, 0, buffer.size) ?: -1
            if (readSize > 0) {
                val maxAmplitude = buffer.maxOrNull()?.toInt() ?: 0
                Log.i("AudioDebug Activity", "Max amplitude: $maxAmplitude")
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
}