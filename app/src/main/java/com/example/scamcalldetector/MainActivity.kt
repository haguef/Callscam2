package com.example.scamcalldetector

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.IBinder
import android.provider.Settings
import android.telecom.TelecomManager
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.scamcalldetector.databinding.ActivityMainBinding
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.properties.Delegates


class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private val PERMISSIONS_REQUEST_CODE = 123

    private lateinit var mService: CallDetectionService
    private var mBound by Delegates.notNull<Boolean>()

    private lateinit var outputFile: File
    private var recorder: MediaRecorder? = null

    private var TAG: String = "mytag"

    private val connection: ServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(className: ComponentName, service: IBinder) {
            val binder: CallDetectionService.LocalBinder =
                service as CallDetectionService.LocalBinder
            mService = binder.getService()
            mBound = true
        }

        override fun onServiceDisconnected(arg0: ComponentName) {
            mBound = false
        }
    }

    private val requiredPermissions = arrayOf(
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.MODIFY_AUDIO_SETTINGS,
        Manifest.permission.READ_PHONE_STATE,
        Manifest.permission.ANSWER_PHONE_CALLS,
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        checkAndRequestPermissions()

        registerMyReceiver()

        val intent = Intent(TelecomManager.ACTION_CHANGE_DEFAULT_DIALER).apply {
            putExtra(TelecomManager.EXTRA_CHANGE_DEFAULT_DIALER_PACKAGE_NAME, packageName)
        }
        startActivity(intent)

        initListeners()
    }

    private fun initListeners() {
        binding.btnStart.setOnClickListener {
            startForegroundService(Intent(this, CallRecordingService::class.java))
        }

        binding.btnStop.setOnClickListener {
        }
    }

    fun registerMyReceiver() {
        val r = MyReceiver()
        val i = IntentFilter("android.permission.READ_PHONE_STATE")
        registerReceiver(r, i)
    }

    private fun checkAndRequestPermissions() {
        val permissionsToRequest = mutableListOf<String>()

        for (permission in requiredPermissions) {
            if (ContextCompat.checkSelfPermission(
                    this, permission
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                permissionsToRequest.add(permission)
            }
        }

        if (permissionsToRequest.isNotEmpty()) {
            ActivityCompat.requestPermissions(
                this, permissionsToRequest.toTypedArray(), PERMISSIONS_REQUEST_CODE
            )
        } else {
            startCallDetectionService()
//            val i = Intent(this@MainActivity, CallerActivity::class.java)
//            intent.setFlags(FLAG_ACTIVITY_NEW_TASK)
//            startActivity(i)
        }
    }

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
                this@MainActivity, arrayOf(outputFile.absolutePath), null, null
            )
        }
        recorder = null
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSIONS_REQUEST_CODE) {
            if (grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                startCallDetectionService()
//                val i = Intent(this@MainActivity, CallerActivity::class.java)
//                intent.setFlags(FLAG_ACTIVITY_NEW_TASK)
//                startActivity(i)
            } else {
                Toast.makeText(
                    this,
                    "All permissions are required for the app to function properly",
                    Toast.LENGTH_LONG
                ).show()

                // Open app settings so user can grant permissions
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                intent.data = android.net.Uri.parse("package:$packageName")
                startActivity(intent)
            }
        }
    }

    private fun startCallDetectionService() {
        val intent = Intent(TelecomManager.ACTION_CHANGE_DEFAULT_DIALER)
        intent.putExtra(TelecomManager.EXTRA_CHANGE_DEFAULT_DIALER_PACKAGE_NAME, packageName)
        startActivity(intent)
    }
}
