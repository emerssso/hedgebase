package com.emerssso.hedgebase

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PorterDuff
import android.net.wifi.WifiManager
import android.os.Bundle
import android.widget.ImageView
import android.widget.TextView
import android.widget.ToggleButton
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.LiveData
import androidx.lifecycle.ViewModelProviders
import androidx.lifecycle.get
import kotlinx.android.synthetic.main.activity_main.*


class MainActivity : AppCompatActivity() {

    private lateinit var tempViewModel: TemperatureViewModel

    private lateinit var text: TextView
    private lateinit var tempToggle: ToggleButton
    private var wifiState: ImageView? = null

    private val wifiReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            showWifiStatus()
        }
    }

    private fun showWifiStatus() {
        wifiState?.run {
            vectorTint = if (checkWifiOnAndConnected()) {
                getColor(android.R.color.white)
            } else {
                RED.toArgb()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.activity_main)
        text = findViewById(R.id.textView)
        tempToggle = findViewById(R.id.toggleButton)
        wifiState = findViewById(R.id.wifiIndicator)

        setTempToggleListener()

        showWifiStatus()
    }

    private fun setTempToggleListener() {

        //TODO: Should this still work this way?
        tempToggle.setOnCheckedChangeListener { _, isChecked ->
            tempViewModel.requestHeatLampOn(isChecked)
        }
    }

    private fun disableTempToggleListener() {
        tempToggle.setOnCheckedChangeListener(null)
    }

    override fun onStart() {
        super.onStart()

        setupTempViewModel()

        registerReceiver(wifiReceiver,
                IntentFilter(android.net.wifi.WifiManager.WIFI_STATE_CHANGED_ACTION))
    }

    private fun setupTempViewModel() {
        tempViewModel = ViewModelProviders.of(this).get()

        with(tempViewModel) {
            displayText.observe { text -> textView.text = text }
            displayTextColor.observe { color -> textView.setTextColor(color.toArgb()) }
            heaterToggleEnabled.observe { enabled -> toggleButton.enabled = enabled }
            heaterStatus.observe { status ->
                disableTempToggleListener()
                toggleButton.isChecked = status
                setTempToggleListener()
            }
        }
    }

    override fun onStop() {
        super.onStop()

        unregisterReceiver(wifiReceiver)
    }

    //This is a hack to reduce the amount of instantiation being done
    private val fetchLifecycle = { lifecycle }

    //make the observe function more Kotlin-friendly
    private fun <T> LiveData<T>.observe(call: (T) -> Unit) {
        observe(fetchLifecycle, call)
    }
}

/** convenience wrapper around isEnabled that also sets alpha */
private var ToggleButton.enabled: Boolean
    get() = isEnabled
    set(value) {
        isEnabled = value
        alpha = if (value) 1f else 0.25f
    }

//Based on https://stackoverflow.com/a/34904367/3390459
private fun Context.checkWifiOnAndConnected(): Boolean {
    val wifiManager = getSystemService(Context.WIFI_SERVICE) as WifiManager

    return if (wifiManager.isWifiEnabled) { // Wi-Fi adapter is ON
        wifiManager.connectionInfo.networkId != -1

    } else {
        false // Wi-Fi adapter is OFF
    }
}

private var ImageView.vectorTint: Int
    get() = 0
    set(value) {
        setColorFilter(value, PorterDuff.Mode.SRC_IN)
    }
