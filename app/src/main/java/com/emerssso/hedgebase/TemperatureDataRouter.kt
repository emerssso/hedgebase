package com.emerssso.hedgebase

import android.content.Context
import android.util.Log
import com.google.android.things.pio.Gpio
import com.google.firebase.Timestamp
import com.google.firebase.firestore.FirebaseFirestore
import com.sensirion.libsmartgadget.*
import com.sensirion.libsmartgadget.smartgadget.BatteryService
import com.sensirion.libsmartgadget.smartgadget.GadgetManagerFactory
import com.sensirion.libsmartgadget.smartgadget.SHTC1TemperatureAndHumidityService
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * Routes the temperature data stream from a BLE temperature sensor to
 * [firestore] for logging,
 * a [temperatureDisplay] for rendering,
 * and a [relaySwitch] to help regulate the temperature
 */
internal class TemperatureDataRouter(
        private val firestore: FirebaseFirestore,
        private val relaySwitch: Gpio?,
        private val temperatureDisplay: TemperatureDisplay
) : GadgetManagerCallback {

    private var gadget: Gadget? = null

    private val gadgetManager = GadgetManagerFactory.create(this)

    private var context: Context? = null

    fun setupGadgetConnection(applicationContext: Context) {
        gadgetManager.initialize(applicationContext)
        context = applicationContext
    }

    fun tearDownGadgetConnection() {
        gadgetManager.stopGadgetDiscovery()

        context?.let { gadgetManager.release(it) }
                ?: Log.w(TAG, "Unable to release context, reference null")

        context = null

        gadget?.run {
            disconnect()
        }
    }

    override fun onGadgetManagerInitialized() {
        Log.d(TAG, "manager initialized, start scanning")
        if (!gadgetManager.startGadgetDiscovery(SCAN_DURATION_MS, NAME_FILTER, UUID_FILTER)) {
            // Failed with starting a scan
            Log.e(TAG, "Could not start discovery")
            temperatureDisplay.noDevicesAvailable()
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

    private inner class SHTC1GadgetListener : GadgetListener {

        private var lastSavedTemp: Instant = Instant.MIN

        override fun onGadgetNewDataPoint(gadget: Gadget, service: GadgetService,
                                          dataPoint: GadgetDataPoint?) {
            Log.d(TAG, "new data point: " +
                    "${dataPoint?.temperature}${dataPoint?.temperatureUnit}")
            dataPoint?.run {
                temperatureDisplay.updateDisplay(fahrenheit)

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
                    temperatureDisplay.onBelowComfort()
                    Log.d(TAG, "turn on heat")
                }
                temp > COMFORT_TEMP_HIGH && !relaySwitch.on -> {
                    relaySwitch.on = true
                    temperatureDisplay.onAboveComfort()
                    Log.d(TAG, "turn off heat")
                }
                temp !in SAFE_TEMP_LOW..SAFE_TEMP_HIGH -> temperatureDisplay.onNotSafe()
                temp in COMFORT_TEMP_LOW..COMFORT_TEMP_HIGH -> temperatureDisplay.onComfortable()
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

            temperatureDisplay.onSensorDisconnected()

            context?.let {
                tearDownGadgetConnection()
                setupGadgetConnection(it)
            } ?: Log.w(TAG, "Context null, unable to reset gadget connection")
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

                temperatureDisplay.onSensorConnected()
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

private const val TAG = "GadgetManagerCallback"

/** helper function that converts Celsius to Fahrenheit */
private val Float.cToF: Float get() = this * 9 / 5 + 32

/** [GadgetDataPoint] temperature as Fahrenheit */
private val GadgetDataPoint.fahrenheit: Float
    get() =
        if (temperatureUnit == UNIT_CELSIUS) {
            temperature.cToF
        } else {
            temperature
        }

/** Returns true if the [Gpio] is on, else false. */
private var Gpio?.on: Boolean
    get() = this?.value ?: false
    set(new) { this?.value = new }

/** Defines the actions that can be displayed.*/
interface TemperatureDisplay {

    fun onSensorConnected()

    fun onSensorDisconnected()

    fun updateDisplay(temp: Float)

    fun noDevicesAvailable()

    fun onBelowComfort()

    fun onAboveComfort()

    fun onNotSafe()

    fun onComfortable()
}