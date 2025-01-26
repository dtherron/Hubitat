/*
 *  Mitsubishi2Hubitat Thermostat Parent App
 *  Project URL: https://github.com/dtherron/Hubitat/edit/main/Apps/mitsubishi2hubitatThermostat
 *  Copyright 2021 Dan Herron
 *
 *  This app requires its child app and device driver to function; please go to the project page for more information.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 *  in compliance with the License. You may obtain a copy of the License at:
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software distributed under the License is distributed
 *  on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License
 *  for the specific language governing permissions and limitations under the License.
 *
 */

#include dtherron.logger

definition(
    name: 'Mitsubishi2Hubitat Thermostat Manager',
    namespace: 'dtherron',
    author: 'Dan Herron',
    description: 'Connect via HTTP to a Mitsubishi heat pump that will appear as a thermostat.',
    category: 'Green Living',
    iconUrl: '',
    iconX2Url: '',
    importUrl: 'https://raw.githubusercontent.com/dtherron/Hubitat/refs/heads/main/Apps/mitsubishi2hubitat_manager.groovy',
    singleInstance: true
)

preferences {
    page(name: 'Install', title: 'Mitsubishi2Hubitat Thermostat Manager', install: true, uninstall: true) {
        section('<b>Configure devices</b>') {
            app(name: 'thermostats', appName: 'Mitsubishi2Hubitat Thermostat Child', namespace: 'dtherron', title: 'Add Mitsubishi2Hubitat Thermostat', multiple: true)
        }

        section('<b>External temperature support</b>') {
            input 'openWeatherMap', 'device.OpenWeatherMap-AlertsWeatherDriver', title: '<i>OpenWeatherMap - Alerts Weather Driver</i> device used for outside temperature and weather conditions', multiple: false, required: false
            input 'outsideTempSensors', 'capability.temperatureMeasurement', title: 'Outside temperature sensors (will be averaged with OpenWeatherMap for outside temp)', multiple: true, required: false
        }

        section('<b>Home/away detection</b>') {
            app(name: 'houseActivityLevel', appName: 'Mitsubishi2Hubitat Thermostat House Activity Level', namespace: 'dtherron', title: 'Add Mitsubishi2Hubitat House Activity Level')
         }

        section('<b>Log Settings</b>') {
            input (name: 'logLevel', type: 'enum', title: 'Live Logging Level: Messages with this level and higher will be logged', options: [[0: 'Disabled'], [1: 'Error'], [2: 'Warning'], [3: 'Info'], [4: 'Debug'], [5: 'Trace']], defaultValue: 3)
            input 'logDropLevelTime', 'number', title: 'Drop down to Info Level Minutes', required: true, defaultValue: 5
        }
    }
}

def installed() {
    logger('info', 'installed')
    state.lastOutsideTempSensorsValue = ''
    initialize()
}

def updated() {
    logger('info', 'updated')
    unsubscribe()
    initialize()
}

def initialize() {
    logger('info', 'initialize', "Initializing; there are ${childApps.size()} child apps installed")

    // Log level was set to a higher level than 3, drop level to 3 in x number of minutes
    loggingLevel = app.getSetting('logLevel').toInteger()
    unschedule(logsDropLevel)
    if (loggingLevel > 3) {
        logger('debug', 'initialize', "Revert log level to default in $settings.logDropLevelTime minutes")
        if (settings.logDropLevelTime > 0) {
            runIn(settings.logDropLevelTime.toInteger() * 60, logsDropLevel)
        }
    }

    logger('info', 'initialize', "App logging level set to $loggingLevel")
    logger('info', 'initialize', "Initialize LogDropLevelTime: $settings.logDropLevelTime")

    // Subscribe to the outside temperature sensor(s)
    if (outsideTempSensors == null && openWeatherMap == null) {
        state.lastOutsideTempSensorsValue = ''
        logger('debug', 'initialize', 'clearing outside sensors')
        unsubscribe(outsideConditionsHandler)
        updateOutsideConditions() // Clear any lingering value
    } else {
        if (outsideTempSensors.toString() != state.lastOutsideTempSensorsValue) {
            state.lastOutsideTempSensorsValue = outsideTempSensors.toString()
            logger('trace', 'initialize', 'outside sensor change found')
            unsubscribe(outsideConditionsHandler)

            // Remove any sensors chosen that are actually of this device type
            // TODO: figure out why the UI never updates to catch on to this
            if (outsideTempSensors?.removeAll { device -> device.getTypeName() == 'Mitsubishi2Hubitat Thermostat Device' }) {
                logger('warn', 'initialize', 'Some outside sensors were ignored because they are Mitsubishi2Hubitat child devices')
            }

            // Subscribe to the new sensor(s)
            if (outsideTempSensors != null && outsideTempSensors.size() > 0) {
                logger('info', 'initialize', "Initializing ${outsideTempSensors.size()} outside sensor(s)")
                subscribe(outsideTempSensors, 'temperature', outsideConditionsHandler)
            }
        }

        if (openWeatherMap != null) {
            logger('trace', 'initialize', 'openWeatherMap device found')
            subscribe(openWeatherMap, 'temperature', outsideConditionsHandler)
        }

        // Update the temperature with these new sensors
        updateOutsideConditions()
    }

    childApps.each { child ->f
        logger('debug', 'initialize', "Updating child app: ${child.label}")
        child.updated()
    }
}

def outsideConditionsHandler(evt) {
    logger('trace', 'outsideConditionsHandler', "Got event: ${evt.name}, ${evt.value}")
    runInMillis(5000, "updateOutsideConditions")
}

def updateOutsideConditions() {
    def total = 0
    def count = 0
    def outsideTemp = null
    def forecastLow = null
    def forecastHigh = null

    // Average across all sensors, but ignore any not reporting as present
    if (outsideTempSensors != null) {
        logger('trace', 'updateOutsideConditions', "Checking ${outsideTempSensors?.size()} for presence to update outside temp")
        for (sensor in outsideTempSensors) {
            logger('trace', 'updateOutsideConditions', "Checking sensor of type ${sensor.getTypeName()}")
            if (sensor.currentValue('presence') == 'present') {
                total += sensor.currentValue('temperature') // TODO: figure out what to do for unit C vs F
                count++
            }
        }
    }

    if (openWeatherMap != null) {
        logger('trace', 'updateOutsideConditions', 'Checking openWeatherMap update outside temp')
        def updateDateFreshness = now() - Date.parse(openWeatherMap.currentValue('last_poll_Forecast'))
        if (updateDateFreshness > 7200000) {
            logger('trace', 'updateOutsideConditions', 'openWeatherMap data is more than two hours stale and being ignored')
        } else {
            total += openWeatherMap.currentValue('temperature') // TODO: figure out what to do for unit C vs F
            count++
            forecastLow = new Date().getHours() >= 12 ? openWeatherMap.currentValue('forecastLow1') : openWeatherMap.currentValue('forecastLow')
            forecastHigh = openWeatherMap.currentValue('forecastHigh')
        }
    }

    // Only send an update if we have data
    logger('trace', 'updateOutsideConditions', "Found ${count} valid outside temperatures")
    if (count > 0) {
        logger('trace', 'updateOutsideConditions', "Setting outside temp to ${total / count}")
        outsideTemp = total / count
    }

    getChildApps.findAll { it -> it["name"] == "Mitsubishi2Hubitat Thermostat Child"} .each { child ->
        logger('debug', 'updateOutsideConditions', "Updating child app's weather data: ${child.label} with temp:$outsideTemp, low:$forecastLow, high:$forecastHigh")
        child.updateWeatherData(outsideTemp, forecastLow, forecastHigh)
    }
}

def getInheritedSetting(setting) {
    return settings."${setting}"
}

def allowAwayMode() {
    def houseActivityLevelChild = getChildApps().find { it["name"] == "Mitsubishi2Hubitat Thermostat House Activity Level" }
    return houseActivityLevelChild == null ? true : houseActivityLevelChild.allowAwayMode()
}
