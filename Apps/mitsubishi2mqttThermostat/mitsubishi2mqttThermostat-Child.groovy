/*
 *  Mitsubishi2Mqtt Thermostat Child App
 *  Project URL: https://github.com/dtherron/Hubitat/edit/main/Apps/mitsubishi2mqttThermostat
 *  Copyright 2021 Dan Herron
 *
 *  This app requires its parent app and device driver to function; please go to the project page for more information.
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

import groovy.json.JsonSlurper

definition(
    name: 'Mitsubishi2Mqtt Thermostat Child',
    namespace: 'dtherron',
    author: 'Dan Herron',
    description: 'Configure the MQTT properties to connect to a Mitsubishi heat pump that will appear as a thermostat.',
    category: 'Green Living',
    iconUrl: 'https://raw.githubusercontent.com/dtherron/Hubitat/main/Apps/mitsubishi2mqttThermostat/mitsubishi2mqttThermostat-logo-small.png',
    iconX2Url: 'https://raw.githubusercontent.com/dtherron/Hubitat/main/Apps/mitsubishi2mqttThermostat/mitsubishi2mqttThermostat-logo.png',
    importUrl: 'https://raw.githubusercontent.com/dtherron/Hubitat/main/Apps/mitsubishi2mqttThermostat/mitsubishi2mqttThermostat-Child.groovy',
    parent: 'dtherron:Mitsubishi2Mqtt Thermostat Manager'
)

preferences {
    page(name: 'pageConfig') // Doing it this way eliminates the default app name/mode options.
}

def pageConfig() {
    // Display all options for a new instance of the Mitsubishi2Mqtt Thermostat
    dynamicPage(name: '', title: '', install: true, uninstall: true, refreshInterval:0) {
        section('<b>Device configuration</b>') {
            input (name: 'useMqtt', type: 'bool', title: 'True to use MQTT; false to use direct HTTP', required: false, defaultValue: true)
        }
        
        section('<b>MQTT configuration</b>') {
            label title: 'Local name of new Mitsubishi2Mqtt Thermostat app/device:', required: true
            input (name: 'mqttTopic', type: 'text', title: 'MQTT topic specified in the configuration on the remote arduino', required: false, defaultValue: 'mitsubishi2mqtt')
            input (name: 'heatPumpFriendlyName', type: 'text', title: 'Friendly Name of the device specified in the configuration on the remote arduino', required: true)

        }
        section('<b>HTTP configuration</b>') {
            input (name: 'arduinoAddress', type: 'text', title: 'Local IP address or name of the heat pump\'s arduino:', required: false, defaultValue: 'MyHeat')
        }

        section('<b>Configure schedule for heating (cooling is manual)</b>') {
            input(name: 'schedule', type: 'text', title: "Array of arrays of HH:MM, temp, e.g. [[\"6:00\",64],[\"8:30\",68],[\"20:30\",60]]")
            input(name: 'awayModeHeatTemp', type: 'number', title: 'Temp to heat to when away mode is set', required: true, defaultValue: 62)
            input(name: 'fanBoost', type: 'number', title: 'How much to boost the fan for large rooms (0-2)', required: true, defaultValue: 0)
            input(name: 'coldWeatherHeatBoost', type: 'bool', title: 'Bump the heat slightly when the expected high is below 45', required: true, defaultValue: false)
            input(name: 'heatingSunBoost', type: 'number', title: 'How much to reduce the heat temperature on sunny days', required: true, defaultValue: 0)
        }

        section('<b>Remote temperature sensor(s) (average value will be used)</b>') {
            input 'remoteTempSensors', 'capability.temperatureMeasurement', title: 'Remote temperature sensors', multiple: true, required: false
        }

        section('<b>Remote illuminance sensor(s) (largest value will be used)</b>') {
            input 'remoteIlluminanceSensors', 'capability.illuminanceMeasurement', title: 'Remote illuminance sensors', multiple: true, required: false
        }

        section('<b>Log Settings</b>') {
            input (name: 'logLevel', type: 'enum', title: 'Logging level: Messages with this level and higher will be logged', options: [[0: 'Disabled'], [1: 'Error'], [2: 'Warning'], [3: 'Info'], [4: 'Debug'], [5: 'Trace']], defaultValue: 3)
            input 'logDropLevelTime', 'number', title: 'Drop down to Info level after (minutes)', required: true, defaultValue: 5
        }
    }
}

def installed() {
    // Set log level as soon as it's installed to start logging what we do ASAP
    int loggingLevel
    if (settings.logLevel) {
        loggingLevel = settings.logLevel.toInteger()
    } else {
        loggingLevel = 3
    }

    logger('info', 'installed', "Installed Running Mitsubishi2Mqtt Thermostat: $app.label")

    // Generate a random DeviceID
    def deviceID = 'm2mt' + Math.abs(new Random().nextInt() % 9999) + '_' + (now() % 9999)

    //Create Mitsubishi2Mqtt Thermostat device
    def thermostat
    def label = app.getLabel()
    logger('info', 'installed', "Creating Mitsubishi2Mqtt Thermostat : ${label} with device id: $deviceID")
    try {
        //** Should we add isComponent in the properties of the child device to make sure we can't remove the Device, will this make it that we can't change settings in it?
        thermostat = addChildDevice('dtherron', 'Mitsubishi2Mqtt Thermostat Device', deviceID, [label: label, name: label, completedSetup: true])
    } catch (e) {
        logger('error', 'installed', "Error adding Mitsubishi2Mqtt Thermostat child ${label}: ${e}")
    }

    updated()
}

def updated() {
    // Set log level to new value
    int loggingLevel
    if (settings.logLevel) {
        loggingLevel = settings.logLevel.toInteger()
    } else {
        loggingLevel = 3
    }

    logger('debug', 'updated', "Updated Running Mitsubishi2Mqtt Thermostat: $app.label.")
    unsubscribe()

    state.lastThermostatScheduleIndex = null
    if (schedule != null && !schedule.trim().isEmpty()) {
        def thermostatSchedule = new JsonSlurper().parseText(schedule).collect { [ timeStringToMinutesAfterMidnight(it[0]), it[0], it[1] ] }.sort { a, b -> a[0] <=> b[0] }

        if (thermostatSchedule.size() > 0) {
            thermostatSchedule.add(0, [timeStringToMinutesAfterMidnight('00:00'), '00:00', thermostatSchedule.last()[2]])
            state.thermostatSchedule = thermostatSchedule.sort { a, b -> a[0] <=> b[0] }

            scheduledUpdateCheck()
            runEvery5Minutes(scheduledUpdateCheck)
        }
    }

    initialize(getThermostat())
}

def uninstalled() {
    def deviceToRemove = getThermostat().getDeviceNetworkId();
    logger('info', 'uninstalled', "Child Device $deviceToRemove removed")
    deleteChildDevice(deviceToRemove)
}

//************************************************************
// initialize
//     Set preferences in the associated device and subscribe to the selected sensors and thermostat device
//     Also set logging preferences
//
// Signature(s)
//     initialize(thermostatInstance)
//
// Parameters
//     thermostatInstance : deviceWrapper
//
// Returns
//     None
//
//************************************************************
def initialize(thermostatInstance) {
    int loggingLevel
    if (settings.logLevel) {
        loggingLevel = settings.logLevel.toInteger()
    } else {
        loggingLevel = 3
    }

    logger('info', 'initialize', "Initialize Running Mitsubishi2Mqtt Thermostat: $app.label.")
    thermostatInstance.setLogLevel(loggingLevel)

    // Log level was set to a higher level than 3, drop level to 3 in x number of minutes
    if (loggingLevel > 3) {
        logger('debug', 'initialize', "Revert log level to default in $settings.logDropLevelTime minutes")
        runIn(settings.logDropLevelTime.toInteger() * 60, logsDropLevel)
    }

    logger('info', 'initialize', "App logging level set to $loggingLevel")
    logger('info', 'initialize', "Initialize LogDropLevelTime: $settings.logDropLevelTime")

    state.lastWeatherDataTime = null

    subscribe(location, 'mode', locationModeChanged)

    // Set device settings if this is a new device
    thermostatInstance.updated()

    initializeRemoteTemperatureSensors(thermostatInstance);
    initializeRemoteIlluminanceSensors();
}

def initializeRemoteTemperatureSensors(thermostatInstance) {
    thermostatInstance.clearRemoteTemperature() // Clear any lingering value
    
    if (remoteTempSensors != null) {
        logger('trace', 'initializeRemoteTemperatureSensors', 'remote sensors found')
        thermostatInstance.clearRemoteTemperature() // Clear any lingering value

        // Remove any sensors chosen that are actually of this device type
        // TODO: figure out why the UI never updates to catch on to this
        if (remoteTempSensors?.removeAll { device -> device.getTypeName() == 'Mitsubishi2Mqtt Thermostat Device' }) {
            logger('warn', 'initializeRemoteTemperatureSensors', 'Some remote sensors were ignored because they are Mitsubishi2Mqtt child devices')
        }

        // Subscribe to the new sensor(s)
        if (remoteTempSensors != null && remoteTempSensors.size() > 0) {
            logger('info', 'initializeRemoteTemperatureSensors', "Initializing ${remoteTempSensors.size()} remote sensor(s)")

            // Get all events from the remote sensors. This way even if the temp is constant
            // we have a better signal that the sensors are still online. The device client
            // will eventually revert to using the device temp if it stops getting updates.
            subscribe(remoteTempSensors, remoteTemperatureHandler, ['filterEvents': false])

            // Update the temperature with these new sensors
            updateRemoteTemperature(thermostatInstance)
        }
    }
}

def initializeRemoteIlluminanceSensors() {
    state.lastHighIlluminanceTime = null

    // Subscribe to the new sensor(s)
    if (location.currentMode.getName() == 'Day' && remoteIlluminanceSensors != null && remoteIlluminanceSensors.size() > 0) {
        logger('info', 'initializeRemoteIlluminanceSensors', "Initializing ${remoteIlluminanceSensors.size()} remote sensor(s)")
        runEvery5Minutes(remoteIlluminanceHandler)
    }
}

//************************************************************
// getThermostat
//     Gets current childDeviceWrapper from list of child devices
//
// Signature(s)
//     getThermostat()
//
// Parameters
//     None
//
// Returns
//     ChildDeviceWrapper
//
//************************************************************
def getThermostat() {
    if (getChildDevices().size() == 0) {
        logger('error', 'getThermostat', 'no child device found')
    } else if (getChildDevices().size() == 1) {
        return getChildDevices().first()
    } else {
        // There should only be one child
        logger('error', 'getThermostat', "There should only be one child device")
    }
}

//************************************************************
// scheduledUpdateCheck
//     Checks if we need to update the temperature or other
//     settings on the thermostat
//
// Signature(s)
//     scheduledUpdateCheck(evt)
//
// Parameters
//
//
// Returns
//     None
//
//************************************************************
def scheduledUpdateCheck() {
    // weather data must be no more than six hours old
    if (state.lastWeatherDataTime != null && now() - 21600000 > state.lastWeatherDataTime) {
        updateWeatherData()
    }

    def thermostatInstance = getThermostat()
    def currentMode = 'heat'
    def currentMonth = new Date(now()).format('MM').toInteger()
    def wasModeOffSetByApp = thermostatInstance.wasModeOffSetByApp() == true
    def currentThermostateMode = thermostatInstance.currentValue('trueThermostatMode')

    // Manual in summer. If the user sets it to off, that will stick.
    if (currentMonth >= 5 && currentMonth <= 9) {
        currentMode = (currentThermostateMode == 'off' && !wasModeOffSetByApp) ? 'off' : getThermostat().getLastRunningMode()
    }
        
    logger('debug', 'scheduledUpdateCheck', "currentMode is $currentMode because currentThermostateMode is $currentThermostateMode and wasModeOffSetByApp is $wasModeOffSetByApp")

    if (currentMode == 'heat') {        
        runIn(1, 'handleHeatingUpdate')
    } else if (currentMode == 'cool') {        
        runIn(1, 'checkForFanUpdate', [data: currentMode])
    }
}

def handleHeatingUpdate() {
    handleHeatingTempUpdate();
    runIn(5, 'checkForFanUpdate', [data: 'heat'])
}

def handleHeatingTempUpdate() {
    def thermostatInstance = getThermostat()
    def currentSetpoint = thermostatInstance.currentValue('thermostatSetpoint')
    def currentRequestedTemp

    // There is some weird bug or issue that is causing wasLastTemperatureChangeByApp to not return false
    def userChangedTemp = currentSetpoint != state.lastRequestedTemp && state.lastRequestedTemp != null && (thermostatInstance.wasLastTemperatureChangeByApp() != true)
    def timeNow = new Date(now()).format('HH:mm')
    def timeNowAsMinutesAfterMidnight = timeStringToMinutesAfterMidnight(timeNow)
    def thermostatSchedule = state.thermostatSchedule

    logger('trace', 'handleHeatingTempUpdate', "right now it is $timeNow ($timeNowAsMinutesAfterMidnight). Checking ${thermostatSchedule.size()} schedule entries: $thermostatSchedule")

    // Find the currently scheduled temperature
    def previousEntries = thermostatSchedule.findAll { it[0] <= timeNowAsMinutesAfterMidnight }
    def currentScheduleEntry = previousEntries.last()
    currentRequestedTemp = currentScheduleEntry[2]

    // Look to see if we have rolled to a new scheduled entry. That will override even user changes
    if (previousEntries.size() != state.lastThermostatScheduleIndex) {
        state.lastThermostatScheduleIndex = previousEntries.size()
        userChangedTemp = false
        logger('info', 'handleHeatingTempUpdate', "new current active schedule entry: ${currentScheduleEntry[1]} -> $currentRequestedTemp")
    } else {
        logger('trace', 'handleHeatingTempUpdate', "current active schedule entry: ${currentScheduleEntry[1]} -> $currentRequestedTemp")
    }
        
    if (userChangedTemp) {
        // If there is a manual change, don't adjust it in most ways
        logger('debug', 'handleHeatingTempUpdate', "Manual change to temp to $currentSetpoint (from ${state.lastRequestedTemp}) is active")
        currentRequestedTemp = currentSetpoint
    } else {
        // If the forecast high for the day is hotter than we want the house, back off on heating
        if (state.lastWeatherExpectedHigh != null) {
            maxTempInSchedule = thermostatSchedule.collect { it[2] }.max().toInteger()
            if (state.lastWeatherExpectedHigh > maxTempInSchedule) {
                currentRequestedTemp -= (state.lastWeatherExpectedHigh - maxTempInSchedule)
                logger('trace', 'handleHeatingTempUpdate', "expected high (${state.lastWeatherExpectedHigh}) is above the high heat point for the day ($maxTempInSchedule); lowering current request to $currentRequestedTemp")
            }

            if (coldWeatherHeatBoost) {
                if (state.lastWeatherExpectedHigh < 40 && state.lastWeatherExpectedLow < 32) {
                    logger('debug', 'handleHeatingTempUpdate', 'bumping temp two degrees because the high is below 40 and low below 32')
                    currentRequestedTemp += 2
                } else if (state.lastWeatherExpectedHigh < 45 && state.lastWeatherExpectedLow < 34) {
                    logger('debug', 'handleHeatingTempUpdate', 'bumping temp one degree because the high is below 45 and low below 34')
                    currentRequestedTemp++
                }
            }
        }

        // If it's very sunny, reduce the heat somewhat
        def brightSeconds = state.lastHighIlluminanceTime
        if (brightSeconds != null) {
            brightSeconds = (now() - brightSeconds) / 1000; // seconds of bright sun
        }
        
        if (heatingSunBoost > 0 &&                 // if this device has a sunboost
            brightSeconds != null &&               // and we have at least 10 minutes
            brightSeconds >= 600 &&                // of bright sunshine
            state.lastWeatherExpectedHigh > 45)
        {
            def sunReduction = (heatingSunBoost.toDouble() / 3).round()
            if (brightSeconds >= 1200) {
                sunReduction = heatingSunBoost
            } else if(brightSeconds >= 900) {
                sunReduction = (2 * heatingSunBoost.toDouble() / 3).round()
            }

            logger('debug', 'handleHeatingTempUpdate', "sunny day -- reduce heat setting by $sunReduction")
            currentRequestedTemp -= sunReduction
        }
    }
    
    if (!userChangedTemp && location.currentMode.getName() == 'Away' && parent?.allowAwayMode()) {
        def coolDown = parent.getHoursSinceAway() + 1
        currentRequestedTemp = Math.max(awayModeHeatTemp, currentRequestedTemp - coolDown)
        logger('debug', 'handleHeatingTempUpdate', "house is set to Away. Setting temperature to $currentRequestedTemp")
    }
    
    // Make sure on really cold days to not let the house get too cold to warm back up
    if (state.lastWeatherExpectedLow != null && state.lastWeatherExpectedLow <= 32 && state.lastWeatherExpectedHigh < 45) {
        def allowedMinTemp = 1 + thermostatSchedule.collect { it[2] }.min().toInteger()

        if (state.lastWeatherExpectedLow <= 26 && state.lastWeatherOutsideTemp <= 30) {
            allowedMinTemp += 3
        } else if (state.lastWeatherExpectedLow <= 28 && state.lastWeatherOutsideTemp <= 32) {
            allowedMinTemp += 2
        } else if (state.lastWeatherExpectedLow <= 30 && state.lastWeatherOutsideTemp <= 34) {
            allowedMinTemp += 1
        }
        
        if (currentRequestedTemp < allowedMinTemp) {
            logger('debug', 'handleHeatingTempUpdate', "Expected low is really cold; increasing minimum temp to $allowedMinTemp")
            currentRequestedTemp = allowedMinTemp
        }
    }

    if (currentRequestedTemp != currentSetpoint) {
        logger('info', 'handleHeatingTempUpdate', "requesting to change temp from $currentSetpoint to $currentRequestedTemp (userChangedTemp=$userChangedTemp)")
        thermostatInstance.handleAppTemperatureChange(currentRequestedTemp)
        state.lastRequestedTemp = currentRequestedTemp
    }
}

def checkForFanUpdate(currentMode) {
    def thermostatInstance = getThermostat()

    def currentSetpoint = thermostatInstance.currentValue('thermostatSetpoint')
    def currentIndoorTemp = thermostatInstance.currentValue('temperature')

    def fanSpeed = fanBoost.toInteger() + ((currentMode == 'cool') ? (currentIndoorTemp - currentSetpoint) : (currentSetpoint - currentIndoorTemp))
    logger('debug', 'checkForFanUpdate', "currentIndoorTemp is $currentIndoorTemp; currentSetpoint is $currentSetpoint; fanBoost is $fanBoost; initial fanSpeed is $fanSpeed")

    if (state.lastWeatherOutsideTemp != null) {
        def currentOutdoorTemp = state.lastWeatherOutsideTemp
        if (currentMode == 'heat') {
            if (currentOutdoorTemp < 25) { fanSpeed += 4 }
            else if (currentOutdoorTemp <= 32) { fanSpeed += 3 }
            else if (currentOutdoorTemp <= 35) { fanSpeed += 2 }
            else if (currentOutdoorTemp <= 38) { fanSpeed += 1 }
            else if (currentOutdoorTemp > 60) { fanSpeed -= 3 }
            else if (currentOutdoorTemp > 55) { fanSpeed -= 2 }
            else if (currentOutdoorTemp > 50) { fanSpeed -= 1 }
            logger('debug', 'checkForFanUpdate', "currentOutdoorTemp is $currentOutdoorTemp; heating mode fanSpeed is $fanSpeed")
        }
    }

    def normalizeFanSpeed = "$fanSpeed"
    def requestOff = false
    def wasModeOffSetByApp = thermostatInstance.wasModeOffSetByApp() == true

    // turn fan off at -2 but only back on at 0 to avoid swinging too much
    if (fanSpeed < -1 || (wasModeOffSetByApp && fanSpeed == -1)) {
        requestOff = true
        normalizeFanSpeed = 'quiet'
    }
    else if (fanSpeed <= 0 ) {
        normalizeFanSpeed = 'quiet'
    } else if (fanSpeed > 4) {
        normalizeFanSpeed = 4
    }

    logger('debug', 'checkForFanUpdate', "Prefered fan speed is $fanSpeed, normalized to $normalizeFanSpeed, currentMode is $currentMode, wasModeOffSetByApp is $wasModeOffSetByApp, and request off is $requestOff")

    thermostatInstance.handleAppThermostatFanMode(normalizeFanSpeed, requestOff)
}

def timeStringToMinutesAfterMidnight(timeString) {
    return (60 * Date.parse('HH:mm', timeString).format('H').toInteger()) + (Date.parse('HH:mm', timeString).format('m').toInteger())
}

//************************************************************
// remoteTemperatureHandler
//     Handles a sensor temperature change event
//     Do not call this directly, only used to handle events
//
// Signature(s)
//     remoteTemperatureHandler(evt)
//
// Parameters
//     evt : passed by the event subsciption
//
// Returns
//     None
//
//************************************************************
def remoteTemperatureHandler(evt) {
    logger('trace', 'remoteTemperatureHandler', "Got event: ${evt.name}, ${evt.value}")
    updateRemoteTemperature(getThermostat())
}

//************************************************************
// updateRemoteTemperature
//     Update device current temperature based on selected sensors
//
// Signature(s)
//     updateRemoteTemperature()
//
// Parameters
//     thermostatInstance : deviceWrapper
//
// Returns
//     None
//
//************************************************************
def updateRemoteTemperature(thermostatInstance) {
    def total = 0
    def count = 0

    // Average across all sensors, but ignore any not reporting as present
    logger('trace', 'updateRemoteTemperature', "Checking ${remoteTempSensors.size()} for presence to update remote temp")
    for (sensor in remoteTempSensors) {
        if (sensor.currentValue('presence') == 'present') {
            total += sensor.currentValue('temperature') // TODO: figure out what to do for unit C vs F
            count++
        }
    }

    // Only send an update if we have data
    logger('trace', 'updateRemoteTemperature', "Found ${count} valid remote temperatures")
    if (count > 0) {
        logger('trace', 'updateRemoteTemperature', "Setting remote temp to ${total / count}")
        thermostatInstance.setRemoteTemperature(total / count)
    }
}

//************************************************************
// remoteIlluminanceHandler
//     Handles a sensor illuminance change event
//     Do not call this directly, only used to handle events
//
// Returns
//     None
//
//************************************************************
def remoteIlluminanceHandler() {
    logger('trace', 'remoteIlluminanceHandler', "Invoked")

    def sunnyThreshold = 10000
    def foundSunnySensor = false
    
    // Just look for any sensor that is in really bright sunlight.
    // Consider replacing this with some area-under-the-curve intelligence
    logger('trace', 'remoteIlluminanceHandler', "Checking ${remoteIlluminanceSensors.size()} sensors to see if any are in bright sunshine")
    for (sensor in remoteIlluminanceSensors) {
        if (sensor.currentValue('presence') == 'present' && sensor.currentValue('illuminance') >= sunnyThreshold) {
            foundSunnySensor = true;
            break;
        }
    }

    if (!foundSunnySensor) {
        state.lastHighIlluminanceTime = null;
    } else if (state.lastHighIlluminanceTime == null) {
        logger('debug', 'remoteIlluminanceHandler', "Marking onset of a very sunny period")
        state.lastHighIlluminanceTime = now();
    }
}

//************************************************************
// updateWeatherData
//     Update current outdoor temperature based on selected sensors
//
// Signature(s)
//     updateWeatherData()
//
// Parameters
//     temperature : number
//     expectedLow : number
//     expectedHigh : number
//
// Returns
//     None
//
//************************************************************
def updateWeatherData(temperature = null, expectedLow = null, expectedHigh = null) {
    if (temperature == null || expectedLow == null || expectedHigh == null) {
        logger('trace', 'updateWeatherData', 'Clearing weather data')
        state.lastWeatherDataTime = null
        state.lastWeatherOutsideTemp = null
        state.lastWeatherExpectedLow = null
        state.lastWeatherExpectedHigh = null
    } else {
        logger('trace', 'updateWeatherData', 'Updating weather data')
        state.lastWeatherDataTime = now()
        state.lastWeatherOutsideTemp = temperature
        state.lastWeatherExpectedLow = (expectedLow as float).round()
        state.lastWeatherExpectedHigh = (expectedHigh as float).round()
    }
}

def locationModeChanged(evt) {
    if (location.currentMode.getName() == 'Day') {
        if (remoteIlluminanceSensors != null && remoteIlluminanceSensors.size() > 0) {
            logger('trace', 'locationModeChanged', "Mode changed to Day; subscribe to illuminance sensors")
            runEvery5Minutes(remoteIlluminanceHandler)
        }
    } else {
        logger('trace', 'locationModeChanged', "Mode no longer set to Day; unsubscribe from illuminance sensors")
        unschedule(remoteIlluminanceHandler)
        state.lastHighIlluminanceTime = null
    }
}

def compressorFrequencyChanged(frequency) {
    parent?.compressorFrequencyChanged(frequency)
}

def logger(level, source) {
    logger(level, source, '')
}

//************************************************************
// logger
//     Wrapper function for all logging with level control via preferences
// Signature(s)
//     logger(String level, String msg)
// Parameters
//     level : Error level string
//     source : Calling method
//     msg : Message to log
// Returns
//     None
//************************************************************
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
    def thermostat = getThermostat()

    app.updateSetting('logLevel', [type:'enum', value:'3'])
    thermostat.setLogLevel(3)

    loggingLevel = app.getSetting('logLevel').toInteger()
    logger('info', 'logsDropLevel', "App logging level set to $loggingLevel")
}

def getInheritedSetting(setting) {
    return settings?."${setting}" == null ? parent.getInheritedSetting(setting) : settings."${setting}"
}
