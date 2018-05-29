package com.emerssso.hedgebase

import android.app.Activity
import android.os.Build
import android.util.Log
import com.google.android.things.contrib.driver.ht16k33.AlphanumericDisplay
import com.sensirion.libsmartgadget.*
import com.sensirion.libsmartgadget.smartgadget.*
import java.io.IOException


private val TAG = MainActivity::class.java.simpleName

private const val SCAN_DURATION_MS = 60000L
private val NAME_FILTER = arrayOf(
        "SHTC1 smart gadget",
        "SHTC1 smart gadget\u0002",
        "Smart Humigadget",
        "SensorTag"
)
private val UUID_FILTER = arrayOf(
        SHT3xTemperatureService.SERVICE_UUID,
        SHT3xHumidityService.SERVICE_UUID,
        SHTC1TemperatureAndHumidityService.SERVICE_UUID,
        SensorTagTemperatureAndHumidityService.SERVICE_UUID
)

class MainActivity : Activity() {
    private lateinit var gadgetManager: GadgetManager
    private val gadgetCallback = HedgebaseGadgetManagerCallback()
    private var gadget: Gadget? = null

    private var display: AlphanumericDisplay? = null

    override fun onStart() {
        super.onStart()

        setupGadgetConnection()
        setUpDisplay()
    }

    /**
     * Initializes [GadgetManagerFactory] (BLE temperature sensor)
     * with [gadgetCallback], so that when we're ready to search
     * for gadgets, we can start right away.
     */
    private fun setupGadgetConnection() {
        gadgetManager = GadgetManagerFactory.create(gadgetCallback)
        gadgetManager.initialize(this)
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

    override fun onStop() {
        super.onStop()

        tearDownGadgetConnection()
        tearDownDisplay()
    }

    /**
     * Destroy any existing connections to BLE devices
     */
    private fun tearDownGadgetConnection() {
        gadgetManager.stopGadgetDiscovery()
        gadgetManager.release(this)

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

    private fun updateDisplay(value: Float) = display?.run {
        try {
            display(value.toDouble())
        } catch (e: IOException) {
            Log.e(TAG, "Error setting display", e)
        }
    }

    private inner class HedgebaseGadgetManagerCallback : GadgetManagerCallback {
        override fun onGadgetManagerInitialized() {
            Log.d(TAG, "manager initialized, start scanning")
            if (!gadgetManager.startGadgetDiscovery(SCAN_DURATION_MS, NAME_FILTER, UUID_FILTER)) {
                // Failed with starting a scan
                Log.e(TAG, "Could not start discovery")
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

        override fun onGadgetNewDataPoint(gadget: Gadget, service: GadgetService,
                                          dataPoint: GadgetDataPoint?) {
            Log.d(TAG, "new data point: " +
                    "${dataPoint?.temperature}${dataPoint?.temperatureUnit}")
            dataPoint?.run { updateDisplay(fahrenheit) }
        }

        override fun onSetGadgetLoggingEnabledFailed(gadget: Gadget,
                                                     service: GadgetDownloadService) {
            Log.d(TAG, "onSetGadgetLoggingEnabled")
        }

        override fun onDownloadCompleted(gadget: Gadget, service: GadgetDownloadService) {
            Log.d(TAG, "onDownloadCompleted")
        }

        override fun onGadgetDisconnected(gadget: Gadget) {
            Log.d(TAG, "onGadgetDisconnected")
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

val i2cBus: String
    get() = when (Build.DEVICE) {
        DEVICE_RPI3 -> "I2C1"
        DEVICE_IMX6UL_PICO -> "I2C2"
        DEVICE_IMX7D_PICO -> "I2C1"
        else -> throw IllegalArgumentException("Unknown device: " + Build.DEVICE)
    }

private const val DEVICE_RPI3 = "rpi3"
private const val DEVICE_IMX6UL_PICO = "imx6ul_pico"
private const val DEVICE_IMX7D_PICO = "imx7d_pico"
private const val UNIT_CELSIUS = "°C"

private val Float.cToF: Float get() = this * 9 / 5 + 32

private val GadgetDataPoint.fahrenheit: Float get() {
    if(temperatureUnit == UNIT_CELSIUS) {
        return temperature.cToF
    } else {
        return temperature
    }
}

