package com.emerssso.hedgebase

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.SensorManager.DynamicSensorCallback
import android.os.Bundle
import android.util.Log
import com.sensirion.libsmartgadget.*
import com.sensirion.libsmartgadget.smartgadget.*


private val TAG = MainActivity::class.java.simpleName
private const val REQUEST_CODE_LOCATION_PERMISSION = 1

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
    private lateinit var sensorManager: SensorManager

    private val dynamicSensorCallback = object : DynamicSensorCallback() {
        override fun onDynamicSensorConnected(sensor: Sensor) {
            if (sensor.type == Sensor.TYPE_AMBIENT_TEMPERATURE) {
                Log.i(TAG, "Temperature sensor connected")
                sensorEventListener = TemperaturePressureEventListener()
                sensorManager.registerListener(sensorEventListener, sensor, SensorManager.SENSOR_DELAY_NORMAL)
            }
        }
    }

    private lateinit var sensorEventListener: TemperaturePressureEventListener

    private lateinit var gadgetManager: GadgetManager
    private val gadgetCallback = HedgebaseGadgetManagerCallback()
    private var gadget: Gadget? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        //startTemperaturePressureRequest()
    }

    override fun onStart() {
        super.onStart()
        gadgetManager = GadgetManagerFactory.create(gadgetCallback)
        gadgetManager.initialize(this)
    }

    override fun onStop() {
        super.onStop()
        //stopTemperaturePressureRequest()
        gadgetManager.stopGadgetDiscovery()
        gadgetManager.release(this)

        gadget?.run {
            disconnect()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        when (requestCode) {
            REQUEST_CODE_LOCATION_PERMISSION -> if (grantResults.isNotEmpty() &&
                    grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                // Permission granted, start scanning
                //onScanButtonClick(null)
                Log.d(TAG, "can scan")
                startScanning()
            }
        }// Otherwise: Permission denied, do nothing
    }

    private fun startScanning() {
        // ...

        Log.d(TAG, "Start scanning")
        if (!gadgetManager.startGadgetDiscovery(SCAN_DURATION_MS, NAME_FILTER, UUID_FILTER)) {
            // Failed with starting a scan
            Log.e(TAG, "Could not start discovery")
        }

        // Successfully started scanning
        // Discovered Gadgets are reported back through the GadgetManagerCallback implementation
    }

    private fun startTemperaturePressureRequest() {
        this.startService(Intent(this, TemperaturePressureService::class.java))
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        sensorManager.registerDynamicSensorCallback(dynamicSensorCallback)
    }

    private fun stopTemperaturePressureRequest() {
        this.stopService(Intent(this, TemperaturePressureService::class.java))
        sensorManager.unregisterDynamicSensorCallback(dynamicSensorCallback)
        sensorManager.unregisterListener(sensorEventListener)
    }

    private inner class TemperaturePressureEventListener : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            Log.i(TAG, "sensor changed: " + event.values[0])
        }

        override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {
            Log.i(TAG, "sensor accuracy changed: $accuracy")
        }
    }

    inner class HedgebaseGadgetManagerCallback : GadgetManagerCallback {
        override fun onGadgetManagerInitialized() {
            Log.d(TAG, "manager initialized")
            startScanning()
        }

        override fun onGadgetDiscoveryFinished() {
            Log.d(TAG, "discovery finished")
        }

        override fun onGadgetManagerInitializationFailed() {
            Log.e(TAG, "gadget manager init failed")
        }

        override fun onGadgetDiscovered(gadget: Gadget?, rssi: Int) {
            Log.d(TAG, "gadget $gadget discovered")

            gadget?.run {
                if(connect()) {
                    Log.d(TAG, "connected to device $gadget")
                    addListener(SHTC1GadgetListener())
                    subscribeAll()

                    getServicesOfType(BatteryService::class.java)
                            .firstOrNull()
                            ?.lastValues
                            ?.forEach {
                                Log.d(TAG, "${it.value} ${it.unit} at ${it.timestamp}")
                            }
                } else {
                    Log.d(TAG, "unable to connect to device $gadget")
                    Log.d(TAG, "unable to connect to device $gadget")
                }
            }
        }

        override fun onGadgetDiscoveryFailed() {
            Log.w(TAG, "gadget discovery failed")
        }
    }

    private inner class SHTC1GadgetListener : GadgetListener {

        override fun onGadgetNewDataPoint(gadget: Gadget, service: GadgetService, dataPoint: GadgetDataPoint?) {
            Log.d(TAG, "new data point: ${dataPoint?.temperature}")
        }

        override fun onSetGadgetLoggingEnabledFailed(gadget: Gadget, service: GadgetDownloadService) {
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

        override fun onGadgetDownloadNewDataPoints(gadget: Gadget, service: GadgetDownloadService, dataPoints: Array<out GadgetDataPoint>) {
            Log.d(TAG, "onGadgetDownloadNewDataPoints")
        }

        override fun onGadgetDownloadProgress(gadget: Gadget, service: GadgetDownloadService, progress: Int) {
            Log.d(TAG, "onGadgetDownloadProgress")
        }

        override fun onGadgetValuesReceived(gadget: Gadget, service: GadgetService, values: Array<out GadgetValue>) {
            Log.d(TAG, "gadget values received from service $service")

            for (value in values) {
                Log.d(TAG, "${value.value} ${value.unit} at ${value.timestamp}")
            }
        }
    }
}

