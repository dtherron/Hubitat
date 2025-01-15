/*
 *  Mitsubishi2Hubitat Thermostat Child App
 *  Project URL: https://github.com/dtherron/Hubitat/edit/main/Apps/mitsubishi2hubitatThermostat
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

#include dtherron.logger
import groovy.json.JsonSlurper

definition(
    name: 'Mitsubishi2Hubitat Thermostat Child',
    namespace: 'dtherron',
    author: 'Dan Herron',
    description: 'Connect via HTTP to a Mitsubishi heat pump that will appear as a thermostat.',
    category: 'Green Living',
    iconUrl: 'https://raw.githubusercontent.com/dtherron/Hubitat/main/Apps/mitsubishi2hubitatThermostat/mitsubishi2hubitatThermostat-logo-small.png',
    iconX2Url: 'https://raw.githubusercontent.com/dtherron/Hubitat/main/Apps/mitsubishi2hubitatThermostat/mitsubishi2hubitatThermostat-logo.png',
    importUrl: 'https://raw.githubusercontent.com/dtherron/Hubitat/main/Apps/mitsubishi2hubitatThermostat/mitsubishi2hubitatThermostat-Child.groovy',
    parent: 'dtherron:Mitsubishi2Hubitat Thermostat Manager'
)

preferences {
    page(name: 'pageConfig') // Doing it this way eliminates the default app name/mode options.
}

def pageConfig() {
    // Display all options for a new instance of the Mitsubishi2Hubitat Thermostat
    dynamicPage(name: '', title: '', install: true, uninstall: true, refreshInterval:0) {
        section('<b>HTTP configuration</b>') {
            input (name: 'heatPumpArduinoHostname', type: 'text', title: 'Hostname specified in your heat pump\'s WiFi configuration:', required: true, defaultValue: 'MyHeat')
        }

        section('<b>Configure schedule for heating (cooling is manual)</b>') {
            input(name: 'schedule', type: 'text', title: "Array of arrays of HH:MM, temp, e.g. [[\"6:00\",64],[\"8:30\",68],[\"20:30\",60]]")
            input(name: 'awayModeHeatTemp', type: 'number', title: 'Temp to heat to when away mode is set', required: true, defaultValue: 62)
            input(name: 'fanBoost', type: 'number', title: 'How much to boost the fan for large rooms (0-2)', required: true, defaultValue: 0)
            input(name: 'coldWeatherHeatBoost', type: 'bool', title: 'Bump the heat slightly when the expected high is below 45', required: true, defaultValue: false)
        }

        section('<b>Remote temperature sensor(s) (average value will be used)</b>') {
            input 'remoteTempSensors', 'capability.temperatureMeasurement', title: 'Remote temperature sensors', multiple: true, required: false
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

    logger('info', 'installed', "Installed Running Mitsubishi2Hubitat Thermostat: $app.label")

    // Generate a random DeviceID
    def deviceID = 'm2mt' + Math.abs(new Random().nextInt() % 9999) + '_' + (now() % 9999)

    //Create Mitsubishi2Hubitat Thermostat device
    def thermostat
    def label = app.getLabel()
    logger('info', 'installed', "Creating Mitsubishi2Hubitat Thermostat : ${label} with device id: $deviceID")
    try {
        //** Should we add isComponent in the properties of the child device to make sure we can't remove the Device, will this make it that we can't change settings in it?
        thermostat = addChildDevice('dtherron', 'Mitsubishi2Hubitat Thermostat Device', deviceID, [label: label, name: label, completedSetup: true])
        updateThermostatId(deviceID);
    } catch (e) {
        logger('error', 'installed', "Error adding Mitsubishi2Hubitat Thermostat child ${label}: ${e}")
    }

    updated()
}

def updateThermostatId(childId) {
    logger('info', 'updateThermostatId', "Updating child thermostat device ID to $childId")

    state.thermostatDeviceId = childId
}

def updated() {
    // Set log level to new value
    int loggingLevel
    if (settings.logLevel) {
        loggingLevel = settings.logLevel.toInteger()
    } else {
        loggingLevel = 3
    }

    logger('debug', 'updated', "Updated Running Mitsubishi2Hubitat Thermostat: $app.label.")
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

    logger('info', 'initialize', "Initialize Running Mitsubishi2Hubitat Thermostat: $app.label.")
    thermostatInstance.setLogLevel(loggingLevel)

    // Log level was set to a higher level than 3, drop level to 3 in x number of minutes
    unschedule(logsDropLevel)
    if (loggingLevel > 3) {
        logger('debug', 'initialize', "Revert log level to default in $settings.logDropLevelTime minutes")
        if (settings.logDropLevelTime > 0) {
            runIn(settings.logDropLevelTime.toInteger() * 60, logsDropLevel)
        }
    }

    logger('info', 'initialize', "App logging level set to $loggingLevel")
    logger('info', 'initialize', "Initialize LogDropLevelTime: $settings.logDropLevelTime")

    state.lastWeatherDataTime = null
    state.modeIsAway = location.currentMode.getName() == 'Away'

    subscribe(location, 'mode', locationModeChanged)

    // Set device settings if this is a new device
    thermostatInstance.updated()

    initializeRemoteTemperatureSensors(thermostatInstance);
}

def initializeRemoteTemperatureSensors(thermostatInstance) {
    thermostatInstance.clearRemoteTemperature() // Clear any lingering value
    state.lastRemoteTemperatureUpdate = null
    
    if (remoteTempSensors != null) {
        logger('trace', 'initializeRemoteTemperatureSensors', 'remote sensors found')
        thermostatInstance.clearRemoteTemperature() // Clear any lingering value

        // Remove any sensors chosen that are actually of this device type
        // TODO: figure out why the UI never updates to catch on to this
        if (remoteTempSensors?.removeAll { device -> device.getTypeName() == 'Mitsubishi2Hubitat Thermostat Device' }) {
            logger('warn', 'initializeRemoteTemperatureSensors', 'Some remote sensors were ignored because they are Mitsubishi2Hubitat child devices')
        }

        // Subscribe to the new sensor(s)
        if (remoteTempSensors != null && remoteTempSensors.size() > 0) {
            logger('info', 'initializeRemoteTemperatureSensors', "Initializing ${remoteTempSensors.size()} remote sensor(s)")

            // Get all events from the remote sensors. This way even if the temp is constant
            // we have a better signal that the sensors are still online. The device client
            // will eventually revert to using the device temp if it stops getting updates.
            subscribe(remoteTempSensors, remoteTemperatureHandler, ['filterEvents': false])

            // Update the temperature with these new sensors
            updateRemoteTemperature()
        }
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
    } else {
        def child = getChildDevices().grep { it.getDeviceNetworkId() == state.thermostatDeviceId }.first()

        if (!child) {
            logger('error', 'getThermostat', "No child with ID ${state.thermostatDeviceId}")
            return
        }
        
        return child
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

    def currentMode = getCurrentMode()

    if (currentMode == 'heat') {        
        runIn(1, 'handleHeatingUpdate')
    } else if (currentMode == 'cool') {        
        runIn(1, 'checkForFanUpdate', [data: currentMode])
    }
}

def getCurrentMode() {
    def thermostatInstance = getThermostat()
    def currentMode = 'heat'
    def currentMonth = new Date(now()).format('MM').toInteger()
    def wasModeOffSetByApp = thermostatInstance.wasModeOffSetByApp() == true
    def currentThermostatMode = thermostatInstance.currentValue('thermostatMode')

    // Manual in summer. If the user sets it to off, that will stick.
    if (currentMonth >= 4 && currentMonth <= 10) {
        currentMode = (currentThermostatMode == 'off' && !wasModeOffSetByApp) ? 'off' : getThermostat().getLastRunningMode()
    } else if (currentThermostatMode != currentMode) {
        logger('info', 'getCurrentMode', "allowing unit to be changed back to $currentMode because this is not a summer month")   
    }
    
    return currentMode
}

def handleHeatingUpdate() {
    handleHeatingTempUpdate();
    runIn(5, 'checkForFanUpdate', [data: 'heat'])
}

def handleHeatingTempUpdate(boolean forceResetToSchedule = false) {
    if (getCurrentMode() != "heat") {
        logger('debug', 'handleHeatingTempUpdate', "exiting; current mode is not heat")
        return;
    }
    
    def thermostatInstance = getThermostat()
    def currentSetpoint = thermostatInstance.currentValue('thermostatSetpoint')
    def currentRequestedTemp

    def userChangedTemp = currentSetpoint != state.lastRequestedTemp && state.lastRequestedTemp != null && thermostatInstance.wasLastTemperatureChangeByUser()
    def timeNow = new Date(now()).format('HH:mm')
    def timeNowAsMinutesAfterMidnight = timeStringToMinutesAfterMidnight(timeNow)
    def thermostatSchedule = state.thermostatSchedule

    logger('trace', 'handleHeatingTempUpdate', "right now it is $timeNow ($timeNowAsMinutesAfterMidnight). userChangedTemp = $userChangedTemp; forceResetToSchedule = $forceResetToSchedule. Checking ${thermostatSchedule.size()} schedule entries: $thermostatSchedule")

    // Find the currently scheduled temperature
    def previousEntries = thermostatSchedule.findAll { it[0] <= timeNowAsMinutesAfterMidnight }
    def currentScheduleEntry = previousEntries.last()
    currentRequestedTemp = currentScheduleEntry[2]

    // Look to see if we have rolled to a new scheduled entry. That will override even user changes
    if (forceResetToSchedule || previousEntries.size() != state.lastThermostatScheduleIndex) {
        state.lastThermostatScheduleIndex = previousEntries.size()
        userChangedTemp = false
        state.lastRequestedTemp = currentRequestedTemp
        logger('info', 'handleHeatingTempUpdate', "new current active schedule entry: ${currentScheduleEntry[1]} -> $currentRequestedTemp; userChangedTemp = $userChangedTemp; forceResetToSchedule = $forceResetToSchedule.")
    } else {
        logger('debug', 'handleHeatingTempUpdate', "current active schedule entry: ${currentScheduleEntry[1]} -> $currentRequestedTemp; userChangedTemp = $userChangedTemp; forceResetToSchedule = $forceResetToSchedule.")
    }
        
    if (userChangedTemp) {
        // If there is a manual change, don't adjust it in most ways
        logger('debug', 'handleHeatingTempUpdate', "Manual change to temp to $currentSetpoint (from ${state.lastRequestedTemp}) is active; forceResetToSchedule = $forceResetToSchedule.")
        currentRequestedTemp = currentSetpoint
    } else {
        if (state.lastWeatherExpectedHigh != null) {
            // If the forecast high for the day is hotter than we want the house, back off on heating
            maxTempInSchedule = thermostatSchedule.collect { it[2] }.max().toInteger()
            if (state.lastWeatherExpectedHigh > maxTempInSchedule) {
                currentRequestedTemp -= (state.lastWeatherExpectedHigh - maxTempInSchedule)
                logger('debug', 'handleHeatingTempUpdate', "expected high (${state.lastWeatherExpectedHigh}) is above the high heat point for the day ($maxTempInSchedule); lowering current request to $currentRequestedTemp")
            }

            // Boost the heating in cold weather
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
    }
    
    if (!userChangedTemp && location.currentMode.getName() == 'Away' && parent?.allowAwayMode()) {
        def coolDown = parent.getTimeSinceAway() + 1
        currentRequestedTemp = Math.max(awayModeHeatTemp, currentRequestedTemp - coolDown)
        logger('debug', 'handleHeatingTempUpdate', "house is set to Away. Setting temperature to $currentRequestedTemp")
    }
    
    // Make sure on really cold days to not let the house get too cold to warm back up
    if (!userChangedTemp && state.lastWeatherExpectedLow != null && state.lastWeatherExpectedLow <= 32 && state.lastWeatherExpectedHigh < 45) {
        def lowTempInSchedule = thermostatSchedule.collect { it[2] }.min().toInteger()
        def boost = 1

        if (state.lastWeatherExpectedLow <= 24 && state.lastWeatherOutsideTemp <= 28) {
            boost += 4
        } else if (state.lastWeatherExpectedLow <= 26 && state.lastWeatherOutsideTemp <= 30) {
            boost += 3
        } else if (state.lastWeatherExpectedLow <= 28 && state.lastWeatherOutsideTemp <= 32) {
            boost += 2
        } else if (state.lastWeatherExpectedLow <= 30 && state.lastWeatherOutsideTemp <= 34) {
            boost += 1
        }
        
        def allowedMinTemp = lowTempInSchedule + boost
        if (currentRequestedTemp < allowedMinTemp) {
            logger('debug', 'handleHeatingTempUpdate', "Expected low is really cold; increasing minimum temp by $boost to $allowedMinTemp (from $lowTempInSchedule)")
            currentRequestedTemp = allowedMinTemp
        }
    }

    // TODO: if the user updates the temp from set to X and back to set, the device still consider it a user override, so until the 
    // next schedule change we don't handle, e.g., away mode. Consider if we should fix that.
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

    def fanSpeed = (fanBoost.toInteger() + ((currentMode == 'cool') ? (currentIndoorTemp - currentSetpoint) : (currentSetpoint - currentIndoorTemp))).floatValue().round()

    logger('trace', 'checkForFanUpdate', "currentIndoorTemp is $currentIndoorTemp; currentSetpoint is $currentSetpoint; fanBoost is $fanBoost; initial fanSpeed is $fanSpeed")

    if (state.lastWeatherOutsideTemp != null) { // TODO: need to make this not boost fan speed when the room temp is ok
        def currentOutdoorTemp = state.lastWeatherOutsideTemp
        if (currentMode == 'heat') {
            if (currentOutdoorTemp < 25) { fanSpeed += 4 }
            else if (currentOutdoorTemp <= 32) { fanSpeed += 3 }
            else if (currentOutdoorTemp <= 35) { fanSpeed += 2 }
            else if (currentOutdoorTemp <= 38) { fanSpeed += 1 }
            else if (currentOutdoorTemp > 60) { fanSpeed -= 3 }
            else if (currentOutdoorTemp > 55) { fanSpeed -= 2 }
            else if (currentOutdoorTemp > 50) { fanSpeed -= 1 }
            logger('trace', 'checkForFanUpdate', "currentOutdoorTemp is $currentOutdoorTemp; heating mode fanSpeed is $fanSpeed")
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

    def thermostatFanModeOverride = thermostatInstance.currentValue('thermostatFanModeOverride')
    logger('trace', 'checkForFanUpdate', "thermostatFanModeOverride is $thermostatFanModeOverride")
    if (thermostatFanModeOverride != "no-override") {
        logger('debug', 'checkForFanUpdate', "Override of fan speed is set: locking to $thermostatFanModeOverride, currentMode is $currentMode, wasModeOffSetByApp is $wasModeOffSetByApp, and request off is $requestOff")
        normalizeFanSpeed = thermostatFanModeOverride
    } else {
        logger('debug', 'checkForFanUpdate', "Preferred fan speed is $fanSpeed, normalized to $normalizeFanSpeed, currentMode is $currentMode, wasModeOffSetByApp is $wasModeOffSetByApp, and request off is $requestOff")
    }        

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
    runInMillis(5000, "updateRemoteTemperature")
}

//************************************************************
// updateRemoteTemperature
//     Update device current temperature based on selected sensors
//
// Signature(s)
//     updateRemoteTemperature()
//
// Returns
//     None
//
//************************************************************
def updateRemoteTemperature() {
    def thermostatInstance = getThermostat()
    
    def total = 0
    def count = 0

    // Average across all sensors, but ignore any not reporting as present
    for (sensor in remoteTempSensors) {
        if (sensor.currentValue('presence') != 'not present' && sensor.currentValue('temperature') != null) {
	        total += sensor.currentValue('temperature') // TODO: figure out what to do for unit C vs F
    	    count++
        }
    }

    // Only send an update if we have data
    if (count > 0) {
        logger('trace', 'updateRemoteTemperature', "Setting remote temp to ${total / count}")
        thermostatInstance.setRemoteTemperature(total / count)
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
    unschedule(resetToScheduleWhenAwayAndNotActive)
    if (location.currentMode.getName() == 'Away') {
        logger('info', 'locationModeChanged', "Mode is away; in 30 minutes reset to scheduled temp")        
        runIn(30 * 60, resetToScheduleWhenAwayAndNotActive) // wait 30 minutes to see if house is quiet
    } else if (state.modeIsAway) {
        logger('info', 'locationModeChanged', "Mode is no longer away; reset to scheduled temp soon")        
        runIn(30, handleHeatingTempUpdate,  [data: true]) 
    }

    state.modeIsAway = location.currentMode.getName() == 'Away'
}

def resetToScheduleWhenAwayAndNotActive() {
    if (location.currentMode.getName() == 'Away' && parent?.allowAwayMode()) {
        // When we go Away force the house back to the current setpoint
        handleHeatingTempUpdate(true)
    }
}

def getInheritedSetting(setting) {
    return settings?."${setting}" == null ? parent.getInheritedSetting(setting) : settings."${setting}"
}
