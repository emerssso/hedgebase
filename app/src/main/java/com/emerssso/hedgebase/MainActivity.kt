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
import com.google.firebase.Timestamp
import com.google.firebase.firestore.FirebaseFirestore
import com.sensirion.libsmartgadget.*
import com.sensirion.libsmartgadget.smartgadget.*
import java.io.IOException
import java.time.Instant
import java.time.temporal.ChronoUnit


class MainActivity : Activity() {
    private lateinit var gadgetManager: GadgetManager
    private val gadgetCallback = HedgebaseGadgetManagerCallback()
    private val firestore: FirebaseFirestore = FirebaseFirestore.getInstance()

    private var gadget: Gadget? = null

    private var display: AlphanumericDisplay? = null
    private var speaker: Speaker? = null

    private var relaySwitch: Gpio? = null
    private var alwaysOn: Gpio? = null

    private lateinit var text: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.activity_main)
        text = findViewById(R.id.textView)
    }

    override fun onStart() {
        super.onStart()

        setupGadgetConnection()
        setUpDisplay()
        setUpSpeaker()
        setUpRelaySwitch()
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
        gadgetManager = GadgetManagerFactory.create(gadgetCallback)
        gadgetManager.initialize(applicationContext)
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
    private fun tearDownGadgetConnection() {
        gadgetManager.stopGadgetDiscovery()
        gadgetManager.release(applicationContext)

        gadget?.run {
            disconnect()
        }
    }

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

    private fun updateDisplay(value: Float) {
        display?.run {
            try {
                display(value.toDouble())
            } catch (e: IOException) {
                Log.e(TAG, "Error setting display", e)
            }
        }

        text.text = getString(R.string.format_temp, value)
    }

    private fun onSensorConnected() {
        playSlide(440F, (440 * 4F))

        text.text = getString(R.string.sensor_connected)
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

    private fun onSensorDisconnected() {
        playSlide(440F * 4, 440F)
        text.text = getString(R.string.sensor_disconnected)
    }

    private inner class HedgebaseGadgetManagerCallback : GadgetManagerCallback {
        override fun onGadgetManagerInitialized() {
            Log.d(TAG, "manager initialized, start scanning")
            if (!gadgetManager.startGadgetDiscovery(SCAN_DURATION_MS, NAME_FILTER, UUID_FILTER)) {
                // Failed with starting a scan
                Log.e(TAG, "Could not start discovery")

                text.text = getString(R.string.unable_to_discover)
            }
        }

        override fun onGadgetDiscoveryFinished() {
            Log.d(TAG, "discovery finished")
        }

        override fun onGadgetManagerInitializationFailed() {
            Log.e(TAG, "gadget manager init failed")
        }

        override fun onGadgetDiscovered(newGadget: Gadget?, rssi: Int) {
            Log.d(TAG, "gadget $newGadget discovered")

            newGadget?.run {
                if (connect()) {
                    Log.d(TAG, "connected to device $newGadget")
                    addListener(SHTC1GadgetListener())
                    subscribeAll()

                    getServicesOfType(BatteryService::class.java)
                            .firstOrNull()
                            ?.lastValues
                            ?.forEach {
                                Log.d(TAG, "${it.value} ${it.unit} at ${it.timestamp}")
                            }

                    gadget = this

                } else {
                    Log.d(TAG, "unable to connect to device $newGadget")
                    Log.d(TAG, "unable to connect to device $newGadget")
                }
            }
        }

        override fun onGadgetDiscoveryFailed() {
            Log.w(TAG, "gadget discovery failed")
        }
    }

    private inner class SHTC1GadgetListener : GadgetListener {

        private var lastSavedTemp: Instant = Instant.MIN

        override fun onGadgetNewDataPoint(gadget: Gadget, service: GadgetService,
                                          dataPoint: GadgetDataPoint?) {
            Log.d(TAG, "new data point: " +
                    "${dataPoint?.temperature}${dataPoint?.temperatureUnit}")
            dataPoint?.run {
                updateDisplay(fahrenheit)

                checkTempThresholds(fahrenheit)

                logTemp(fahrenheit)
            }
        }

        /**
         * Check the [temp] against our thresholds:
         * if below [COMFORT_TEMP_LOW] when [relaySwitch] is off, color temp blue and turn on switch
         * if above [COMFORT_TEMP_HIGH] when [relaySwitch] is on, color temp red and turn off switch
         * if within comfort temp range, color text default and disable audible alarm
         * if outside [SAFE_TEMP_LOW] to [SAFE_TEMP_HIGH] play an audible alarm
         */
        private fun checkTempThresholds(temp: Float) {
            when {
                temp < COMFORT_TEMP_LOW && relaySwitch.on -> {
                    relaySwitch.on = false
                    text.setTextColor(Color.valueOf(0f, 0f, 1f).toArgb())
                    Log.d(TAG, "turn on heat")
                }
                temp > COMFORT_TEMP_HIGH && !relaySwitch.on -> {
                    relaySwitch.on = true
                    text.setTextColor(Color.valueOf(1f, 0f, 0f).toArgb())
                    Log.d(TAG, "turn off heat")
                }
                temp !in SAFE_TEMP_LOW..SAFE_TEMP_HIGH ->
                    playSlide(440F, 440F * 4, 50)
                temp in COMFORT_TEMP_LOW..COMFORT_TEMP_HIGH -> {
                    speaker?.stop()
                    text.setTextColor(getColor(android.R.color.white))
                }
            }
        }

        /**
         * Log [temp] with current timestamp to Firestore, once uniquely, and once in a "current"
         * position that is replaced with each logging.
         */
        private fun logTemp(temp: Float) {
            val now = Instant.now()
            if (lastSavedTemp.isBefore(now.minus(15L, ChronoUnit.MINUTES))) {
                Log.d(TAG, "Recording temperature $temp at $now")

                val data = mapOf(
                        "temp" to temp,
                        "time" to Timestamp(now.epochSecond, 0),
                        "lamp" to !relaySwitch.on
                )
                firestore.collection("temperatures")
                        .add(data)

                firestore.document("temperatures/current").set(data)

                lastSavedTemp = now
            }
        }

        override fun onSetGadgetLoggingEnabledFailed(gadget: Gadget,
                                                     service: GadgetDownloadService) {
            Log.d(TAG, "onSetGadgetLoggingEnabledFailed")
        }

        override fun onDownloadCompleted(gadget: Gadget, service: GadgetDownloadService) {
            Log.d(TAG, "onDownloadCompleted")
        }

        override fun onGadgetDisconnected(gadget: Gadget) {
            Log.d(TAG, "onGadgetDisconnected")

            onSensorDisconnected()

            tearDownGadgetConnection()
            setupGadgetConnection()
        }

        override fun onSetLoggerIntervalFailed(gadget: Gadget, service: GadgetDownloadService) {
            Log.d(TAG, "onSetLoggerIntervalFailed")
        }

        override fun onDownloadFailed(gadget: Gadget, service: GadgetDownloadService) {
            Log.d(TAG, "onDownloadFailed")
        }

        override fun onSetLoggerIntervalSuccess(gadget: Gadget) {
            Log.d(TAG, "onSetLoggerIntervalSuccess")
        }

        override fun onDownloadNoData(gadget: Gadget, service: GadgetDownloadService) {
            Log.d(TAG, "onDownloadNoData")
        }

        override fun onGadgetConnected(gadget: Gadget) {
            Log.d(TAG, "onGadgetConnected")
            val service = gadget.getServicesOfType(SHTC1TemperatureAndHumidityService::class.java)
                    .firstOrNull { it is SHTC1TemperatureAndHumidityService }
                    as SHTC1TemperatureAndHumidityService?

            service?.run {
                Log.d(TAG, "subscribing to temp/humidity service")
                subscribe()

                onSensorConnected()
            } ?: Log.d(TAG, "Unable to find temp/humidity service")
        }

        override fun onGadgetDownloadNewDataPoints(gadget: Gadget, service: GadgetDownloadService,
                                                   dataPoints: Array<out GadgetDataPoint>) {
            Log.d(TAG, "onGadgetDownloadNewDataPoints")
        }

        override fun onGadgetDownloadProgress(gadget: Gadget, service: GadgetDownloadService,
                                              progress: Int) {
            Log.d(TAG, "onGadgetDownloadProgress")
        }

        override fun onGadgetValuesReceived(gadget: Gadget, service: GadgetService,
                                            values: Array<out GadgetValue>) {
            Log.d(TAG, "gadget values received from service $service")

            for (value in values) {
                Log.d(TAG, "${value.value} ${value.unit} at ${value.timestamp}")
            }
        }
    }
}

private val TAG = MainActivity::class.java.simpleName

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

private val Float.cToF: Float get() = this * 9 / 5 + 32

private val GadgetDataPoint.fahrenheit: Float
    get() =
        if (temperatureUnit == UNIT_CELSIUS) {
            temperature.cToF
        } else {
            temperature
        }

private var Gpio?.on: Boolean
    get() = this?.value ?: false
    set(new) { this?.value = new }
