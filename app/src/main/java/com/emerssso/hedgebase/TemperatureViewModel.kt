package com.emerssso.hedgebase

import android.app.Application
import android.graphics.Color
import android.util.Log
import androidx.annotation.ColorRes
import androidx.annotation.StringRes
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.google.android.things.pio.Gpio
import com.google.android.things.pio.PeripheralManager
import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreSettings
import com.sensirion.libsmartgadget.*
import com.sensirion.libsmartgadget.smartgadget.BatteryService
import com.sensirion.libsmartgadget.smartgadget.GadgetManagerFactory
import com.sensirion.libsmartgadget.smartgadget.SHTC1TemperatureAndHumidityService
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * Routes the temperature data stream from a BLE temperature sensor to various [LiveData]
 */
internal class TemperatureViewModel(application: Application) :
        AndroidViewModel(application), GadgetManagerCallback {

    private var relaySwitch: Gpio? = null
    private var alwaysOn: Gpio? = null

    private var gadget: Gadget? = null

    private val gadgetManager = GadgetManagerFactory.create(this).also {
        it.initialize(application)
    }

    private val firestore: FirebaseFirestore = FirebaseFirestore.getInstance().also {
        it.firestoreSettings = FirebaseFirestoreSettings.Builder()
                .setTimestampsInSnapshotsEnabled(true)
                .build()
    }

    //Mutable internal view data

    private val connectionData = MutableLiveData<Boolean>().also { it.postValue(false) }
    private val heaterData = MutableLiveData<Boolean>().also { it.postValue(false) }
    private val toggleEnabledData = MutableLiveData<Boolean>().also { it.postValue(true) }

    private val temperatureData = MutableLiveData<String>()
//            .also { it.postString(R.string.searching_for_sensor) }

    private val textColorData = MutableLiveData<Color>()
//            .also { it.postColor(android.R.color.white) }

    //External "immutable" view data

    /** Indicates if temperature sensor is connected */
    val sensorConnection: LiveData<Boolean> = connectionData

    /** Indicates if heater is on */
    val heaterStatus: LiveData<Boolean> = heaterData

    /** Indicates of heater on/off toggle should be enabled */
    val heaterToggleEnabled: LiveData<Boolean> = toggleEnabledData

    /** Temperature text to be displayed */
    val displayText: LiveData<String> = temperatureData

    /** Color of temperature text */
    val displayTextColor: LiveData<Color> = textColorData

    val app = getApplication<Application>()

    init {
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

    fun tearDownGadgetConnection() {
        gadgetManager.stopGadgetDiscovery()
        gadgetManager.release(getApplication())

        gadget?.run {
            disconnect()
        }
    }

    override fun onCleared() {
        tearDownGadgetConnection()

        alwaysOn?.close()
        relaySwitch?.close()

        super.onCleared()
    }

    override fun onGadgetManagerInitialized() {
        Log.d(TAG, "manager initialized, start scanning")
        if (!gadgetManager.startGadgetDiscovery(SCAN_DURATION_MS, NAME_FILTER, UUID_FILTER)) {
            // Failed with starting a scanapplication.getString
            Log.e(TAG, "Could not start discovery")
            temperatureData.postString(R.string.unable_to_discover)
        }
    }

    override fun onGadgetDiscoveryFinished() {
        Log.d(TAG, "discovery finished")
    }

    override fun onGadgetManagerInitializationFailed() {
        Log.e(TAG, "gadget manager init failed")
        relaySwitch.on = false

        temperatureData.postString(R.string.unable_to_discover)
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

                firestore.document(PATH_ALERT_DISCONNECTED).clearAlert()

                gadget = this
            } else {
                Log.d(TAG, "unable to connect to device $newGadget")
                firestore.document(PATH_ALERT_DISCONNECTED)
                        .setAlert("Unable to connect to temperature sensor!")
            }
        }
    }

    override fun onGadgetDiscoveryFailed() {
        Log.w(TAG, "gadget discovery failed")
        relaySwitch.on = false

        firestore.document(PATH_ALERT_DISCONNECTED)
                .setAlert("Unable to connect to temperature sensor!")
    }

    fun requestHeatLampOn(on: Boolean) {
        relaySwitch.on = !on
    }

    private inner class SHTC1GadgetListener : GadgetListener {

        private var lastSavedTemp: Instant = Instant.MIN

        override fun onGadgetNewDataPoint(gadget: Gadget, service: GadgetService,
                                          dataPoint: GadgetDataPoint?) {
            Log.d(TAG, "new data point: " +
                    "${dataPoint?.temperature}${dataPoint?.temperatureUnit}")
            dataPoint?.run {
                temperatureData.postValue(app.getString(R.string.format_temp, fahrenheit))

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
                temp < COMFORT_TEMP_LOW -> {
                    relaySwitch.on = false
                    textColorData.postValue(BLUE)
                    toggleEnabledData.postValue(false)

                    Log.d(TAG, "turn on heat")
                }
                temp > COMFORT_TEMP_HIGH -> {
                    relaySwitch.on = true
                    textColorData.postValue(RED)
                    toggleEnabledData.postValue(false)

                    Log.d(TAG, "turn off heat")
                }
                temp !in SAFE_TEMP_LOW..SAFE_TEMP_HIGH -> {

                    firestore.document(PATH_ALERTS_TEMP).setAlert(
                            if (temp <= SAFE_TEMP_LOW) {
                                "Temperature is dangerously low!"
                            } else {
                                "Temperature is dangerously high!"
                            })
                }
                temp in COMFORT_TEMP_LOW..COMFORT_TEMP_HIGH -> {
                    textColorData.postValue(Color.valueOf(app.getColor(android.R.color.white)))
                    toggleEnabledData.postValue(true)

                    firestore.document(PATH_ALERTS_TEMP).clearAlert()
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

                firestore.collection(PATH_TEMPS).add(data)

                firestore.document(PATH_TEMP_CURRENT).set(data)

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
            relaySwitch.on = false

            connectionData.postValue(false)
            temperatureData.postString(R.string.sensor_disconnected)

            tearDownGadgetConnection()
            gadgetManager.initialize(app)

            firestore.document(PATH_ALERT_DISCONNECTED)
                    .setAlert("Temperature sensor disconnected!")
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
                    as? SHTC1TemperatureAndHumidityService?

            service?.run {
                Log.d(TAG, "subscribing to temp/humidity service")
                subscribe()

                connectionData.postValue(true)
                temperatureData.postString(R.string.sensor_connected)
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
                if (value.unit == "%" && value.value.toFloat() < 25f) {
                    firestore.document(PATH_ALERTS_BATTERY)
                            .setAlert("Sensor battery low: ${value.value}%")
                } else {
                    firestore.document(PATH_ALERTS_BATTERY).clearAlert()
                }
            }
        }
    }

    /** Returns true if the [Gpio] is on, else false. */
    private var Gpio?.on: Boolean
        get() = this?.value ?: false
        set(new) {
            this?.value = new
            heaterData.postValue(!new)
        }

    private fun MutableLiveData<String>.postString(@StringRes resName: Int) =
            postValue(app.getString(resName))

    private fun MutableLiveData<Color>.postColor(@ColorRes resName: Int) =
            postValue(Color.valueOf(app.getColor(resName)))
}

private const val TAG = "GadgetManagerCallback"

private const val PATH_ALERTS = "alerts"
private const val PATH_ALERT_DISCONNECTED = "$PATH_ALERTS/disconnected"
private const val PATH_ALERTS_TEMP = "$PATH_ALERTS/temperature"
private const val PATH_ALERTS_BATTERY = "$PATH_ALERTS/battery"

private const val PATH_TEMPS = "temperatures"
private const val PATH_TEMP_CURRENT = "$PATH_TEMPS/current"

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

private const val KEY_ALERT_MESSAGE = "message"
private const val KEY_ALERT_ACTIVE = "active"
private const val KEY_ALERT_TIME_START = "start"
private const val KEY_ALERT_TIME_END = "end"

internal val RED = Color.valueOf(1f, 0f, 0f)
internal val BLUE = Color.valueOf(0f, 0f, 1f)

private fun DocumentReference.setAlert(message: String) {
    Log.d(TAG, "Setting alert: $message")

    set(mapOf(
            KEY_ALERT_MESSAGE to message,
            KEY_ALERT_ACTIVE to true,
            KEY_ALERT_TIME_START to Timestamp.now()
    ))
}

//Clears the alert if it is active, else does nothing
private fun DocumentReference.clearAlert() {
    get().addOnCompleteListener {

        if (it.isSuccessful && it.result[KEY_ALERT_ACTIVE] as? Boolean == true) {
            Log.d(TAG, "clearing alert")

            //Copy cleared alert out of active namespace for posterity; delete active instance
            it.result.data?.let { data ->
                data.putAll(mapOf(
                        KEY_ALERT_ACTIVE to false,
                        KEY_ALERT_TIME_END to Timestamp.now())
                )

                firestore.collection(PATH_ALERTS)
                        .add(data)
                        .addOnCompleteListener { delete() }
            }
        } else {
            Log.d(TAG, "alert not set, skipping clear")
        }
    }
}