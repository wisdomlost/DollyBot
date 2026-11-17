package com.example.dollybot

import android.os.Bundle
import android.annotation.SuppressLint
import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.ViewModelProvider
import com.example.dollybot.connection.DollyBotViewModel
import com.example.dollybot.ui.DollyBotActions
import com.example.dollybot.ui.DollyBotApp
import com.example.dollybot.ui.theme.DollybotTheme

class MainActivity : ComponentActivity() {
    private val session by lazy { ViewModelProvider(this)[DollyBotViewModel::class.java] }
    private var pendingBluetoothAction: () -> Unit = {}
    private val permissionRequest = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        session.refreshDevices()
        if (session.hasPermission) pendingBluetoothAction()
        pendingBluetoothAction = {}
    }
    private val enableBluetooth = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        session.refreshDevices()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val actions = object : DollyBotActions by session {
            override fun refreshDevices() = withBluetoothPermission { session.refreshDevices() }
            @SuppressLint("MissingPermission")
            override fun connect() = withBluetoothPermission {
                val adapter = getSystemService(BluetoothManager::class.java)?.adapter
                if (adapter != null && !adapter.isEnabled) {
                    enableBluetooth.launch(android.content.Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
                } else session.connect()
            }
        }
        setContent {
            val state by session.state.collectAsState()
            DollybotTheme {
                DollyBotApp(state, actions)
            }
        }
    }

    private fun withBluetoothPermission(action: () -> Unit) {
        if (Build.VERSION.SDK_INT >= 31 && !session.hasPermission) {
            pendingBluetoothAction = action
            permissionRequest.launch(arrayOf(Manifest.permission.BLUETOOTH_CONNECT))
        } else action()
    }

    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) session.onBackground()
    }
}
