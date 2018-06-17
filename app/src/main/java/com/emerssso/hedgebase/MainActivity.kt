package com.emerssso.hedgebase

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.Activity
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.animation.LinearInterpolator
import android.widget.TextView
import com.google.android.things.contrib.driver.ht16k33.AlphanumericDisplay
import com.google.android.things.contrib.driver.pwmspeaker.Speaker
import com.google.android.things.pio.Gpio
import com.google.android.things.pio.PeripheralManager
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreSettings
import com.sensirion.libsmartgadget.smartgadget.GadgetManagerFactory
import java.io.IOException


class MainActivity : Activity(), TemperatureDisplay {

    private lateinit var gadgetCallback: TemperatureDataRouter

    private var display: AlphanumericDisplay? = null
    private var speaker: Speaker? = null

    private var relaySwitch: Gpio? = null
    private var alwaysOn: Gpio? = null

    private lateinit var text: TextView

    private val firestore: FirebaseFirestore = FirebaseFirestore.getInstance()

    init {
        val settings = FirebaseFirestoreSettings.Builder()
                .setTimestampsInSnapshotsEnabled(true)
                .build()

        firestore.firestoreSettings = settings
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.activity_main)
        text = findViewById(R.id.textView)
    }

    override fun onStart() {
        super.onStart()

        setUpDisplay()
        setUpSpeaker()
        setUpRelaySwitch()

        setupGadgetConnection()
    }

    private fun setUpRelaySwitch() {
        val peripheralManager = PeripheralManager.getInstance()

        //set translation target GPIO to always on.
        alwaysOn = peripheralManager.openGpio(GPIO_ALWAYS_ON)?.also {
            it.setDirection(Gpio.DIRECTION_OUT_INITIALLY_HIGH)
        }

        //Get reference to relay switch
        relaySwitch = peripheralManager.openGpio(GPIO_RELAY_SWITCH)

        relaySwitch?.run {
            setActiveType(Gpio.ACTIVE_HIGH)
            setDirection(Gpio.DIRECTION_OUT_INITIALLY_LOW)
        } ?: run {
            Log.w(TAG, "Unable to find relay switch, lamp control unavailable")
        }
    }

    /**
     * Initializes [GadgetManagerFactory] (BLE temperature sensor)
     * with [gadgetCallback], so that when we're ready to search
     * for gadgets, we can start right away.
     */
    private fun setupGadgetConnection() {
        gadgetCallback = TemperatureDataRouter(firestore, relaySwitch,
                temperatureDisplay = this)

        gadgetCallback.setupGadgetConnection(applicationContext)
    }

    /**
     * Connects an [AlphanumericDisplay], like that used by a Rainbow HAT
     */
    private fun setUpDisplay() = try {
        display = AlphanumericDisplay(i2cBus).apply {
            setEnabled(true)
            clear()
        }

        Log.d(TAG, "Initialized I2C Display")
    } catch (e: IOException) {
        Log.e(TAG, "Error initializing display", e)
        Log.d(TAG, "Display disabled")
        display = null
    }

    private fun setUpSpeaker() {
        try {
            speaker = Speaker(speakerPwmPin)
        } catch (e: IOException) {
            Log.w(TAG, "Unable to connect to speaker", e)
        }
    }

    override fun onStop() {
        super.onStop()

        tearDownGadgetConnection()
        tearDownDisplay()

        speaker?.close()
        alwaysOn?.close()
        relaySwitch?.close()
    }

    /**
     * Destroy any existing connections to BLE devices
     */
    private fun tearDownGadgetConnection() = gadgetCallback.tearDownGadgetConnection()

    /**
     * Releases our hold on the [AlphanumericDisplay]
     */
    private fun tearDownDisplay() = display?.run {
        try {
            clear()
            setEnabled(false)
            close()
        } catch (e: IOException) {
            Log.e(TAG, "Error disabling display", e)
        } finally {
            display = null
        }
    }

    override fun updateDisplay(temp: Float) {
        display?.run {
            try {
                display(temp.toDouble())
            } catch (e: IOException) {
                Log.e(TAG, "Error setting display", e)
            }
        }

        text.text = getString(R.string.format_temp, temp)
    }

    override fun onSensorConnected() {
        playSlide(440F, (440 * 4F))

        text.text = getString(R.string.sensor_connected)
    }

    override fun noDevicesAvailable() {
        text.text = getString(R.string.unable_to_discover)
    }

    override fun onBelowComfort() = text.setTextColor(Color.valueOf(0f, 0f, 1f).toArgb())

    override fun onAboveComfort() = text.setTextColor(Color.valueOf(1f, 0f, 0f).toArgb())

    override fun onNotSafe() = playSlide(440F, 440F * 4, 50)

    override fun onComfortable() {
        speaker?.stop()
        text.setTextColor(getColor(android.R.color.white))
    }

    private fun playSlide(start: Float, end: Float, repeat: Int = 1) {
        val slide = ValueAnimator.ofFloat(start, end)
        slide.duration = 150
        slide.repeatCount = repeat
        slide.interpolator = LinearInterpolator()
        slide.addUpdateListener { animation ->
            try {
                val v = animation.animatedValue as Float
                speaker?.play(v.toDouble())
            } catch (e: IOException) {
                throw RuntimeException("Error sliding speaker", e)
            }
        }
        slide.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                try {
                    speaker?.stop()
                } catch (e: IOException) {
                    throw RuntimeException("Error sliding speaker", e)
                }
            }
        })
        runOnUiThread({ slide.start() })
    }

    override fun onSensorDisconnected() {
        playSlide(440F * 4, 440F)
        text.text = getString(R.string.sensor_disconnected)
    }
}

private const val TAG = "MainActivity"

val i2cBus: String
    get() = when (Build.DEVICE) {
        DEVICE_RPI3 -> "I2C1"
        DEVICE_IMX6UL_PICO -> "I2C2"
        DEVICE_IMX7D_PICO -> "I2C1"
        else -> throw IllegalArgumentException("Unknown device: " + Build.DEVICE)
    }

val speakerPwmPin: String
    get() = when (Build.DEVICE) {
        DEVICE_RPI3 -> "PWM1"
        DEVICE_IMX6UL_PICO -> "PWM8"
        DEVICE_IMX7D_PICO -> "PWM2"
        else -> throw IllegalArgumentException("Unknown device: " + Build.DEVICE)
    }