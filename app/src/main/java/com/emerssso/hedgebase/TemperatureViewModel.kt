package com.emerssso.hedgebase

import android.app.Application
import android.graphics.Color
import android.util.Log
import androidx.lifecycle.*
import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.FirebaseFirestore
import com.sensirion.libsmartgadget.*
import com.sensirion.libsmartgadget.smartgadget.BatteryService
import com.sensirion.libsmartgadget.smartgadget.GadgetManagerFactory
import com.sensirion.libsmartgadget.smartgadget.SHTC1TemperatureAndHumidityService
import org.threeten.bp.DateTimeUtils
import org.threeten.bp.Instant
import org.threeten.bp.temporal.ChronoUnit
import java.util.*

/**
 * Routes the temperature data stream from a BLE temperature sensor to various [LiveData]
 */
class TemperatureViewModel(application: Application) :
        AndroidViewModel(application), GadgetManagerCallback {

    private val heater: Heater = Heater()

    private var gadget: Gadget? = null

    private val gadgetManager = GadgetManagerFactory.create(this).apply { initialize(application) }

    private val firestore: FirebaseFirestore = FirebaseFirestore.getInstance()
            .apply { setListeners(this) }

    private var lastSavedTemp: Instant = Instant.MIN

    //Mutable internal view data

    private val connectionData = MutableLiveData<Boolean>().apply {
        postValue(false)

        observeForever {
            if (it != true) heater.on()
        }
    }
    private val temperatureData = MutableLiveData<Float>().apply {
        observeForever {
            checkTempAlerts(it)
            logTemp(it)
        }
    }

    private val thermalSafetyData: LiveData<ThermalSafety> =
            Transformations.map(temperatureData) { it.toSafety() }
                    .apply {
                        observeForever {
                            when (it) {
                                ThermalSafety.BELOW_SAFE -> heater.on()
                                ThermalSafety.BELOW_COMFORT -> heater.on()
                                ThermalSafety.COMFORT -> {
                                } //if comfortable, no change to heater
                                ThermalSafety.ABOVE_COMFORT -> heater.off()
                                ThermalSafety.ABOVE_SAFE -> heater.off()
                                else -> heater.on() //if no data, turn on heater to be safe
                            }
                        }
                    }

    //External "immutable" view data

    /** Indicates if temperature sensor is connected */
    val sensorConnection: LiveData<Boolean> = connectionData

    /** Indicates if heater is on */
    val heaterStatus: LiveData<Boolean> = heater.status.apply {
        observeForever {
            Log.d(TAG, "heater status changed.")
            temperatureData.value?.let { temp ->
                forceLogTemp(temp)
            } ?: Log.w(TAG, "Unable to log temp when heat lamp changed")
        }
    }

    /** Indicates of heater on/off toggle should be enabled */
    val heaterToggleEnabled: LiveData<Boolean> =
            Transformations.map(thermalSafetyData) { it == ThermalSafety.COMFORT }

    /** Temperature text to be displayed */
    val displayText: LiveData<String> = MediatorLiveData<String>().apply {
        addSource(Transformations.map(connectionData) {
            when (it) {
                true -> application.getString(R.string.sensor_connected)
                false -> application.getString(R.string.sensor_disconnected)
                null -> application.getString(R.string.unable_to_discover)
            }
        }) { postValue(it) }

        addSource(Transformations.map<Float, String>(temperatureData) {
            application.getString(R.string.format_temp, it)
        }) { postValue(it) }
    }

    /** Color of temperature text */
    val displayTextColor: LiveData<Int> = Transformations.map(thermalSafetyData) {
        when (it) {
            ThermalSafety.BELOW_SAFE -> BLUE
            ThermalSafety.BELOW_COMFORT -> BLUE
            ThermalSafety.COMFORT -> WHITE
            ThermalSafety.ABOVE_COMFORT -> RED
            ThermalSafety.ABOVE_SAFE -> RED
            else -> WHITE
        }
    }

    val app = getApplication<Application>()

    fun tearDownGadgetConnection() {
        gadgetManager.stopGadgetDiscovery()
        gadgetManager.release(getApplication())

        gadget?.run {
            disconnect()
        }
    }

    override fun onCleared() {
        tearDownGadgetConnection()

        heater.disconnect()

        super.onCleared()
    }

    override fun onGadgetManagerInitialized() {
        Log.d(TAG, "manager initialized, start scanning")
        if (!gadgetManager.startGadgetDiscovery(SCAN_DURATION_MS, NAME_FILTER, UUID_FILTER)) {
            // Failed with starting a scanapplication.getString
            Log.e(TAG, "Could not start discovery")
            connectionData.postValue(false)
        }
    }

    override fun onGadgetDiscoveryFinished() {
        Log.d(TAG, "discovery finished")
    }

    override fun onGadgetManagerInitializationFailed() {
        Log.e(TAG, "gadget manager init failed")

        connectionData.postValue(false)
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
        connectionData.postValue(false)

        firestore.document(PATH_ALERT_DISCONNECTED)
                .setAlert("Unable to connect to temperature sensor!")
    }

    /**
     * Check the [temp] against our thresholds:
     * if below [COMFORT_TEMP_LOW] when [heater] is off, color temp blue and turn on switch
     * if above [COMFORT_TEMP_HIGH] when [heater] is on, color temp red and turn off switch
     * if within comfort temp range, color text default and disable audible alarm
     * if outside [SAFE_TEMP_LOW] to [SAFE_TEMP_HIGH] play an audible alarm
     */
    private fun checkTempAlerts(temp: Float) {
        when (temp) {
            !in SAFE_TEMP_LOW..SAFE_TEMP_HIGH -> {

                firestore.document(PATH_ALERTS_TEMP).setAlert(
                        if (temp <= SAFE_TEMP_LOW) {
                            "Temperature is dangerously low!"
                        } else {
                            "Temperature is dangerously high!"
                        })
            }
            in COMFORT_TEMP_LOW..COMFORT_TEMP_HIGH -> {
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

        firestore.document("temperatures/current").get().addOnCompleteListener {
            if (it.isSuccessful) {
                val lastCurrent = it.result?.getDate("time")?.let { d -> DateTimeUtils.toInstant(d) }
                if (lastCurrent == null || lastCurrent.isStale()) {
                    Log.d(TAG, "firestore current >15 old")
                    forceLogTemp(temp, now)
                }
            } else if (it.isCanceled && lastSavedTemp.isStale()) {
                Log.w(TAG, "Couldn't get last temp from firestore, falling back to local value")
                forceLogTemp(temp, now)
            }
        }
    }

    private fun forceLogTemp(temp: Float, time: Instant = Instant.now()) {
        val data = mapOf(
                "temp" to temp,
                "time" to Timestamp(time.epochSecond, 0),
                "lamp" to heater.status.value
        )
        Log.d(TAG, "logging temp $temp at $time")

        firestore.collection(PATH_TEMPS).add(data)

        firestore.document(PATH_TEMP_CURRENT).set(data)

        lastSavedTemp = time
    }

    fun requestHeatLampOn(on: Boolean) = heater.set(on)

    private inner class SHTC1GadgetListener : GadgetListener {

        override fun onGadgetNewDataPoint(gadget: Gadget, service: GadgetService,
                                          dataPoint: GadgetDataPoint?) {
            Log.d(TAG, "new data point: " +
                    "${dataPoint?.temperature}${dataPoint?.temperatureUnit}")

            dataPoint?.run { temperatureData.postValue(fahrenheit) }
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

            connectionData.postValue(false)

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

    private fun setListeners(firestore: FirebaseFirestore) {
        val commands = firestore.collection("commands")

        commands.document("lamp").addSnapshotListener { snap, e ->
            if (snap != null) {
                if (snap.getBoolean("active") == true) {
                    snap.getBoolean("target")?.let { requestHeatLampOn(it) }
                    snap.reference.set(mapOf("active" to false))
                }
            } else if (e != null) {
                Log.w(TAG, "can't listen to lamp command: ", e)
            } else {
                Log.w(TAG, "lamp: neither snapshot nor error were non-null")
            }
        }

        commands.document("sendTemp").addSnapshotListener { snap, e ->
            if (snap != null) {
                if (snap.getBoolean("active") == true) {
                    lastSavedTemp = Instant.MIN
                    snap.reference.set(mapOf("active" to false))
                }
            } else if (e != null) {
                Log.w(TAG, "can't listen to sendTemp command: ", e)
            } else {
                Log.w(TAG, "sendTemp: neither snapshot nor error were non-null")
            }
        }
    }
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

internal const val RED = 0x00FF0000
internal const val BLUE = 0x000000FF
internal const val WHITE = 0x00FFFFFF

/** Sets an alert with [message] if it doesn't already exist **/
private fun DocumentReference.setAlert(message: String) {
    get().addOnCompleteListener {
        if (it.isSuccessful && it.result?.exists() != true) {
            Log.d(TAG, "alert not set")
            forceSetAlert(message)
        }
    }
}

/** Always sets alert [message] **/
private fun DocumentReference.forceSetAlert(message: String) {
    Log.d(TAG, "setting alert: $message")
    set(mapOf(
            KEY_ALERT_MESSAGE to message,
            KEY_ALERT_ACTIVE to true,
            KEY_ALERT_TIME_START to Timestamp.now()
    ))
}

//Clears the alert if it is active, else does nothing
private fun DocumentReference.clearAlert() {
    get().addOnCompleteListener {

        if (it.isSuccessful && it.result?.get(KEY_ALERT_ACTIVE) as? Boolean == true) {
            Log.d(TAG, "clearing alert")

            //Copy cleared alert out of active namespace for posterity; delete active instance
            it.result?.data?.let { data ->
                data.putAll(mapOf(
                        KEY_ALERT_ACTIVE to false,
                        KEY_ALERT_TIME_END to Timestamp.now())
                )

                firestore.collection(PATH_ALERTS)
                        .add(data)
                        .addOnCompleteListener { delete() }
            }
        }
    }
}

enum class ThermalSafety {
    BELOW_SAFE,
    BELOW_COMFORT,
    COMFORT,
    ABOVE_COMFORT,
    ABOVE_SAFE;
}

fun Float.toSafety(): ThermalSafety {
    return when {
        this < SAFE_TEMP_LOW -> ThermalSafety.BELOW_SAFE
        this < COMFORT_TEMP_LOW -> ThermalSafety.BELOW_COMFORT
        this > COMFORT_TEMP_HIGH -> ThermalSafety.ABOVE_COMFORT
        this > SAFE_TEMP_HIGH -> ThermalSafety.ABOVE_SAFE
        else -> ThermalSafety.COMFORT
    }
}

/** Model class that controls the cage heater, and it's on/off state */
class Heater {

    private val mutableStatus = MutableLiveData<Boolean>().apply { postValue(true) }
    val status: LiveData<Boolean> = mutableStatus

    fun on() = set(true)

    fun off() = set(false)

    fun set(status: Boolean) {
        Log.d(TAG, "heater status changed to $status")
        mutableStatus.postValue(status)
    }

    fun disconnect() {
    }
}

fun Instant.isStale(): Boolean = this.isBefore(Instant.now().minus(15L, ChronoUnit.MINUTES))
