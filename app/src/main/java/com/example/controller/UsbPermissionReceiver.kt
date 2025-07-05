package com.example.controller

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.util.Log

class UsbPermissionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context?, intent: Intent?) {
        // このレシーバーは MainActivity からの権限リクエストの結果のみを処理する
        if (context == null || intent == null || intent.action != MainActivity.ACTION_USB_PERMISSION) {
            return
        }

        val device: UsbDevice? = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
        }

        // 権限が許可された場合のみ、MainActivityを再開してデバイス情報を渡す
        if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
            device?.let {
                Log.i("UsbPermissionReceiver", "Permission GRANTED for ${it.deviceName}")
                // MainActivityに処理を依頼
                val launchIntent = Intent(context, MainActivity::class.java).apply {
                    action = MainActivity.ACTION_USB_PERMISSION_GRANTED // 独自のアクション
                    putExtra(UsbManager.EXTRA_DEVICE, it)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                }
                context.startActivity(launchIntent)
            }
        } else {
            Log.w("UsbPermissionReceiver", "Permission DENIED for device ${device?.deviceName}")
            // 拒否された場合はMainActivityに通知を送ることも可能だが、今回はログ出力のみ
        }
    }
}