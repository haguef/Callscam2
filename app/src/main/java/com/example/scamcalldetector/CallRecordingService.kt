package com.example.scamcalldetector

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Environment
import android.os.IBinder
import android.util.Log
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class CallRecordingService : Service() {
    private var mediaRecorder: MediaRecorder? = null
    private var outputFile: File? = null
    private val TAG = "mytag"

    private var audioFocusRequest: AudioFocusRequest? = null

    private fun isMicLive(): Boolean {
        val bufferSize = AudioRecord.getMinBufferSize(
            44100,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )

        val recorder = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            44100,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize
        )

        val buffer = ShortArray(bufferSize)
        recorder.startRecording()
        val read = recorder.read(buffer, 0, buffer.size)
        recorder.stop()
        recorder.release()

        return read > 0 && buffer.any { it != 0.toShort() }
    }

    override fun onCreate() {
        super.onCreate()
        startForegroundService()
//        requestAudioFocus()

            startRecording()

        var job = GlobalScope.launch {
            delay(10000)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }

        job.start()
    }

    private fun startForegroundService() {
        val channelId = "CallRecordChannel"
        val notificationManager =
            getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val channel = NotificationChannel(
            channelId, "Call Recording", NotificationManager.IMPORTANCE_LOW
        )
        notificationManager.createNotificationChannel(channel)

        val notification = Notification.Builder(this, channelId).setContentTitle("Recording Call")
            .setContentText("Recording in progress")
            .setSmallIcon(android.R.drawable.sym_action_call).build()

        startForeground(101, notification)
    }

    private fun startRecording() {
        try {
            val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val dir = File(getExternalFilesDir(Environment.DIRECTORY_RECORDINGS), "call_recordings")
            if (!dir.exists()) dir.mkdirs()

            val fileName = SimpleDateFormat(
                "yyyyMMdd_HHmmss", Locale.getDefault()
            ).format(Date()) + "_local.mp4"
            outputFile = File(dir, fileName)

            var isLive = isMicLive()

            Log.i(TAG, "isMicLive ${isLive}")

            if (!isLive) else {
                return
            }

            mediaRecorder = MediaRecorder().apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioEncodingBitRate(128000)
                setAudioSamplingRate(44100)
                setOutputFile(outputFile!!.absolutePath)

                try {
                    prepare()
                    start()
                    Log.i(TAG, "Recording started: ${outputFile!!.absolutePath}")
                } catch (e: Exception) {
                    Log.e(TAG, "Recorder failed to start", e)
                    stopSelf()
                }
            }

            audioManager.isSpeakerphoneOn = true

        } catch (e: Exception) {
            Log.e(TAG, "Recording setup failed", e)
            stopSelf()
        }
    }

    private fun requestAudioFocus() {
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
            ).setAcceptsDelayedFocusGain(false).setOnAudioFocusChangeListener { }.build()
        val result = audioManager.requestAudioFocus(focusRequest)
        if (result != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            Log.i(TAG, "Audio focus not granted...")
        } else {
            audioFocusRequest = focusRequest
            Log.i(TAG, "Focus granted...")
        }
    }

    private fun abandonAudioFocus() {
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audioFocusRequest?.let {
            audioManager.abandonAudioFocusRequest(it)
        }
    }

    private fun stopRecording() {
        try {
            mediaRecorder?.apply {
                stop()
                reset()
                release()
                abandonAudioFocus()
            }
            Log.i(TAG, "Recording stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping recorder", e)
        } finally {
            mediaRecorder = null
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopRecording()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}