package com.emerssso.hedgebase

import com.sensirion.libsmartgadget.smartgadget.SHT3xHumidityService
import com.sensirion.libsmartgadget.smartgadget.SHT3xTemperatureService
import com.sensirion.libsmartgadget.smartgadget.SHTC1TemperatureAndHumidityService
import com.sensirion.libsmartgadget.smartgadget.SensorTagTemperatureAndHumidityService

/*
 * This file constains a variety of constants determined for my hardware setup and use-case.
 * The app can be reconfigured for other contexts by changing these values.
 */

/*
 * These temperature values define app behavior in different temperature ranges.
 * There are basically two ranges. "Comfort" range is what the app will try to keep the measured
 * temperature within by manipulating the heat lamp. If the temp goes outside of the "Safe" range,
 * It will try to notify the user (more notification mechanisms coming soon!)
 */

/** The low end of the safe range. The app will notify the user if temp is lower than this */
internal const val SAFE_TEMP_LOW = 73f

/** The high end of the safe range. If temp is higher, the app will notify the user*/
internal const val SAFE_TEMP_HIGH = 85f

/** The low end of the comfort range. The app will turn the heat lamp on if temp drops below this */
internal const val COMFORT_TEMP_LOW = 75f

/** The high end of the comfort range. The app will turn the heat lamp off if above this */
internal const val COMFORT_TEMP_HIGH = 78f

//Non-temperature constants below

/** GPIO that is always on to use for logic-level conversion */
internal const val GPIO_ALWAYS_ON = "GPIO6_IO14"

/** GPIO used to control relay switch that powers the heat lamp */
internal const val GPIO_RELAY_SWITCH = "GPIO6_IO12"

//Device type constants
internal const val DEVICE_RPI3 = "rpi3"
internal const val DEVICE_IMX6UL_PICO = "imx6ul_pico"
internal const val DEVICE_IMX7D_PICO = "imx7d_pico"

internal const val UNIT_CELSIUS = "°C"

/** How long to scan for a temp sensor on BLE */
internal const val SCAN_DURATION_MS = 60000L

/** List of BLE temp sensor device names */
internal val NAME_FILTER = arrayOf(
        "SHTC1 smart gadget",
        "SHTC1 smart gadget\u0002",
        "Smart Humigadget",
        "SensorTag"
)

/** List of BLE temp sensor device UUIDs */
internal val UUID_FILTER = arrayOf(
        SHT3xTemperatureService.SERVICE_UUID,
        SHT3xHumidityService.SERVICE_UUID,
        SHTC1TemperatureAndHumidityService.SERVICE_UUID,
        SensorTagTemperatureAndHumidityService.SERVICE_UUID
)