/*
 *  Mitsubishi2Mqtt Thermostat Parent App
 *  Project URL: https://github.com/dtherron/Hubitat/edit/main/Apps/mitsubishi2mqttThermostat
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

definition(
    name: 'Mitsubishi2Mqtt Thermostat Manager',
    namespace: 'dtherron',
    author: 'Dan Herron',
    description: 'Add Mitsubishi heat pumps communicating via MQTT as thermostats. See https://github.com/gysmo38/mitsubishi2MQTT for communicating with the heat pump.',
    category: 'Green Living',
    iconUrl: 'https://raw.githubusercontent.com/dtherron/Hubitat/main/Apps/mitsubishi2mqttThermostat/mitsubishi2mqttThermostat-logo-small.png',
    iconX2Url: 'https://raw.githubusercontent.com/dtherron/Hubitat/main/Apps/mitsubishi2mqttThermostat/mitsubishi2mqttThermostat-logo.png',
    importUrl: 'https://raw.githubusercontent.com/dtherron/Hubitat/main/Apps/mitsubishi2mqttThermostat/mitsubishi2mqttThermostat-Parent.groovy',
    singleInstance: true
)

preferences {
    page(name: 'Install', title: 'Mitsubishi2Mqtt Thermostat Manager', install: true, uninstall: true) {
        section('<b>Enter MQTT configuration</b>') {
            input(
                name: 'brokerIp',
                type: 'string',
                title: 'MQTT Broker IP Address',
                description: 'e.g. 192.168.1.200',
                required: true,
                displayDuringSetup: true
            )
            input(
                name: 'brokerPort',
                type: 'string',
                title: 'MQTT Broker Port',
                description: 'e.g. 1883',
                required: true,
                displayDuringSetup: true
            )

            input(
                name: 'brokerUser',
                type: 'string',
                title: 'MQTT Broker Username',
                description: 'e.g. mqtt_user',
                required: false,
                displayDuringSetup: true
            )
            input(
                name: 'brokerPassword',
                type: 'password',
                title: 'MQTT Broker Password',
                description: 'e.g. ^L85er1Z7g&%2En!',
                required: false,
                displayDuringSetup: true
            )
        }

        section('<b>Configure devices</b>') {
            app(name: 'thermostats', appName: 'Mitsubishi2Mqtt Thermostat Child', namespace: 'dtherron', title: 'Add Mitsubishi2Mqtt Thermostat', multiple: true)
        }

        section('<b>Extra devices to use</b>') {
            input 'openWeatherMap', 'device.OpenWeatherMap-AlertsWeatherDriver', title: '<i>OpenWeatherMap - Alerts Weather Driver</i> device used for outside temperature and weather conditions', multiple: false, required: false
            input 'outsideTempSensors', 'capability.temperatureMeasurement', title: 'Outside temperature sensors (will be averaged with OpenWeatherMap for outside temp)', multiple: true, required: false
        }

        section('<b>Home/away detection</b>') {
            input 'motionSensors', 'capability.motionSensor', title: 'Motion sensors (to detect somebody is home)', multiple: true, required: false
            input 'homeActivityLevel', 'device.VirtualOmniSensor', title: 'Where to log home activity level', multiple: false, required: false
            input(name: 'nighttimeStart', type: 'string', title: 'Time when night begins', description: 'e.g. when you go to sleep, and away mode stops taking effect', required: true, defaultValue: "22:00")
            input(name: 'nighttimeEnd', type: 'string', title: 'Time when night ends', description: 'e.g. when you wake up, and away mode starts taking effect', required: true, defaultValue: "06:00")
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

    // reset the motion sensor state data
    state.motionSensorState = [:]

    // Log level was set to a higher level than 3, drop level to 3 in x number of minutes
    loggingLevel = app.getSetting('logLevel').toInteger()
    if (loggingLevel > 3) {
        logger('debug', 'initialize', "Revert log level to default in $settings.logDropLevelTime minutes")
        runIn(settings.logDropLevelTime.toInteger() * 60, logsDropLevel)
    }

    logger('info', 'initialize', "App logging level set to $loggingLevel")
    logger('info', 'initialize', "Initialize LogDropLevelTime: $settings.logDropLevelTime")

    // Subscribe to the motion sensor(s)
    if (motionSensors != null && motionSensors.size() > 0) {
        logger('info', 'initialize', "Initializing ${motionSensors.size()} motion sensor(s)")
        subscribe(motionSensors, 'motion.active', motionActiveHandler)
        subscribe(motionSensors, 'motion.inactive', motionActiveHandler)
        runEvery5Minutes(getRollingActivityLevel)
    }

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
            if (outsideTempSensors?.removeAll { device -> device.getTypeName() == 'Mitsubishi2Mqtt Thermostat Device' }) {
                logger('warn', 'initialize', 'Some outside sensors were ignored because they are Mitsubishi2Mqtt child devices')
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
            subscribe(openWeatherMap, 'cloud', outsideConditionsHandler)
        }

        // Update the temperature with these new sensors
        updateOutsideConditions()
    }

    subscribe(location, 'mode', locationModeChanged)

    childApps.each { child ->
        logger('debug', 'initialize', "Updating child app: ${child.label}")
        child.updated()
    }
}

def outsideConditionsHandler(evt) {
    logger('trace', 'outsideConditionsHandler', "Got event: ${evt.name}, ${evt.value}")
    updateOutsideConditions()
}

def updateOutsideConditions() {
    def total = 0
    def count = 0
    def outsideTemp = null
    def cloudiness = null
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
            cloudiness = openWeatherMap.currentValue('cloud')
            forecastLow = openWeatherMap.currentValue('forecastLow')
            forecastHigh = openWeatherMap.currentValue('forecastHigh')
        }
    }

    // Only send an update if we have data
    logger('trace', 'updateOutsideConditions', "Found ${count} valid outside temperatures")
    if (count > 0) {
        logger('trace', 'updateOutsideConditions', "Setting outside temp to ${total / count}")
        outsideTemp = total / count
    }

    childApps.each { child ->
        logger('debug', 'updateOutsideConditions', "Updating child app: ${child.label}")
        child.updateWeatherData(outsideTemp, cloudiness, forecastLow, forecastHigh)
    }
}

def locationModeChanged(evt) {
    def newMode = evt.value
    updateAwayModeDisabledUntil(10)
    logger('trace', 'locationModeChanged', "Mode changed to away; prevent away for 15 minutes to avoid bouncing")
}

def motionActiveHandler(evt) {
    def deviceId = evt.getDeviceId().toString()
    def isActive = evt.value == 'active'
    if (evt.value != 'active' && evt.value != 'inactive') {
        logger('warn', 'motionActiveHandler', "Unexpected event ${evt.value} ignored")
        return
    }

    def timeNow = now()
    def sensorHistories = state.motionSensorState

    logger('trace', 'motionActiveHandler', "Sensor $deviceId is ${evt.value} at $timeNow")

    def sensorHistory = sensorHistories[deviceId]
    if (sensorHistory == null) {
        logger('debug', 'motionActiveHandler', "Initialize history to empty")
        sensorHistory = []
    } else {
        // Remove anything more than 30 minutes old
        sensorHistory.removeAll { entry -> entry[1] != null && entry[1] < (timeNow - 1800000) }
    }
    
    if (isActive) {
        if (sensorHistory.removeAll { entry -> entry[1] == null } ) {
            logger('warn', 'motionActiveHandler', "Sensor $deviceId had an active event without matching inactive, which was removed")
        }

        sensorHistory << [timeNow, null]
    } else if (sensorHistory.size() > 0) {
        if (sensorHistory[-1][1] != null) {
            logger('warn', 'motionActiveHandler', "Inactive time is already set for most recent activity on sensor $deviceId at $timeNow ($sensorHistory)")
        }
        sensorHistory[-1] = [sensorHistory[-1][0], timeNow]
    }

    sensorHistories[deviceId] = sensorHistory
    logger('trace', 'motionActiveHandler', "Update $deviceId for ${evt.value} at $timeNow ($sensorHistory)")
}

def getRollingActivityLevel() {
    def timeNow = now()
    def windowStartTime = timeNow - 1800000
    def timeActive = 0

    def sensorHistories = state.motionSensorState
    sensorHistories.each { sensorHistoryKV ->
        def sensorTimeActive = 0
        def sensorHistory = sensorHistoryKV.value

        sensorHistory.each { entry ->
            def startTime = Math.max(windowStartTime, entry[0])
            def endTime = entry[1] ? entry[1] : timeNow 
            if (endTime >= windowStartTime) {
                sensorTimeActive += (int) ((endTime - startTime) / 1000)
            }
        }
        
        logger('trace', 'getRollingActivityLevel', "Sensor ${sensorHistoryKV.key} level is $sensorTimeActive")
    
        timeActive += sensorTimeActive
    }
    
    def percentActive = Math.min(100, (int) (100 * timeActive / 3600))
    
    logger('trace', 'getRollingActivityLevel', "Activity level is $timeActive or $percentActive")
    homeActivityLevel.setVariable(percentActive)
    
    if (percentActive > 10) {
        updateAwayModeDisabledUntil(30)
        logger('trace', 'getRollingActivityLevel', "Moderate activity in house; prevent away for 30 minutes")
    } else if (percentActive > 30) {
        updateAwayModeDisabledUntil(60)
        logger('trace', 'getRollingActivityLevel', "High activity in house; prevent away for one hour")
    } else if (percentActive > 50) {
        updateAwayModeDisabledUntil(240)
        logger('trace', 'getRollingActivityLevel', "Very high activity in house; prevent away for four hours")
    }
}

def updateAwayModeDisabledUntil(minutes) {
    def newTimeRequested = now() + (60000 * minutes)
    state.disableAwayModeUntil = Math.max(state.disableAwayModeUntil, newTimeRequested)
}

def allowAwayMode() {
    def nowTime = Date.parse('HH:mm', new Date(now()).format('HH:mm'))
    def nightStart = Date.parse('HH:mm', settings.nighttimeStart)
    def nightEnd = Date.parse('HH:mm', settings.nighttimeEnd)
        
    if ((nightStart < nightEnd && timeOfDayIsBetween(nightStart, nightEnd, nowTime)) || !timeOfDayIsBetween(nightEnd, nightStart, nowTime)) {  
        logger('trace', 'allowAwayMode', "Nighttime; prevent away mode")
        return false;
    }

    // when there was recent activity
    if (state.disableAwayModeUntil > now()) {
        logger('trace', 'allowAwayMode', "Recent activity detected; prevent away mode")
        return false
    }
    
    logger('trace', 'allowAwayMode', "Not nighttime and no recent activity; allow away mode")
    return true
}

def getInheritedSetting(setting) {
    return settings."${setting}"
}

def logger(level, source) {
    logger(level, source, '')
}

def logger(level, source, msg) {
    def loggingLevel = settings.logLevel.toInteger()
    switch (level) {
        case 'error':
            if (loggingLevel >= 1) log.error "[${source}] ${msg}"
            break

        case 'warn':
            if (loggingLevel >= 2) log.warn "[${source}] ${msg}"
            break

        case 'info':
            if (loggingLevel >= 3) log.info "[${source}] ${msg}"
            break

        case 'debug':
            if (loggingLevel >= 4) log.debug "[${source}] ${msg}"
            break

        case 'trace':
            if (loggingLevel >= 5) log.trace "[${source}] ${msg}"
            break

        default:
            log.debug "[${source}] ${msg}"
            break
    }
}

//************************************************************
// logsDropLevel
//     Turn down logLevel to 3 in this app/device and log the change
//
// Signature(s)
//     logsDropLevel()
//
// Parameters
//     None
//
// Returns
//     None
//
//************************************************************
def logsDropLevel() {
    app.updateSetting('logLevel', [type:'enum', value:'3'])

    loggingLevel = app.getSetting('logLevel').toInteger()
    logger('info', 'logsDropLevel', "App logging level set to $loggingLevel")
}

