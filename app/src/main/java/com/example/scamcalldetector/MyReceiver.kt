package com.example.scamcalldetector

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.Intent.FLAG_ACTIVITY_NEW_TASK
import android.telephony.PhoneStateListener
import android.telephony.TelephonyManager
import android.widget.Toast


class MyReceiver : BroadcastReceiver() {
    var c: Context? = null

    override fun onReceive(context: Context, intent: Intent?) {
        c = context
        try {
            val tmgr = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
            val PhoneListener = MyPhoneStateListener()
            tmgr.listen(PhoneListener, PhoneStateListener.LISTEN_CALL_STATE)
        } catch (e: Exception) {
            Toast.makeText(context, "oops!", Toast.LENGTH_SHORT).show()
        }
    }

    private inner class MyPhoneStateListener() : PhoneStateListener() {
        override fun onCallStateChanged(state: Int, incomingNumber: String) {
            if (state == 1) {
//                val intent = Intent(c, CallerActivity::class.java)
//                intent.setFlags(FLAG_ACTIVITY_NEW_TASK)
//                c?.startActivity(intent)
            } else if (state == 2) {
                val msg = "This is phone from : ${incomingNumber}"
                val duration = Toast.LENGTH_LONG
                val toast = Toast.makeText(c, msg, duration)
                toast.show()
            }
        }
    }
}