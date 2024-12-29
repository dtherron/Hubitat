/*
 *  Mitsubishi2Hubitat Thermostat Device Driver
 *  Project URL: https://github.com/dtherron/Hubitat/edit/main/Apps/mitsubishi2hubitatThermostat
 *  Copyright 2021 Dan Herron
 *
 *  This driver is not meant to be used by itself, please go to the project page for more information.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 *  in compliance with the License. You may obtain a copy of the License at:
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software distributed under the License is distributed
 *  on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License
 *  for the specific language governing permissions and limitations under the License.
 */

import groovy.json.JsonSlurper
import groovy.json.JsonOutput

public static String version() { return "v1.0.0" }
public static String rootTopic() { return "hubitat" }

metadata {
    definition (name: "Mitsubishi2Hubitat Thermostat Device", 
        namespace: "dtherron", 
        author: "Dan Herron",
        importUrl: "https://raw.githubusercontent.com/dtherron/Hubitat/main/Apps/mitsubishi2hubitatThermostat/mitsubishi2hubitatThermostat-Device.groovy") {
        
        capability "Thermostat"
        capability "Sensor"
        capability "Actuator"
        capability "Temperature Measurement"

        command "setThermostatFanMode", [[name:"Fan mode (ignore above)", type: "ENUM", description:"Set the heat pump's fan speed setting", constraints: ["quiet", "auto", "1", "2", "3", "4"]]]
        command "setHeatPumpVane", ["string"]
        command "dry"
        command "overrideThermostatFanMode", [[name:"Temporary override of app fan mode", type: "ENUM", description:"Set the heat pump's fan speed setting", constraints: ["quiet", "auto", "1", "2", "3", "4", "no-override"]]]

        attribute "temperatureUnit", "ENUM", ["C", "F"]
        attribute "heatPumpVane", "ENUM", ["auto", "swing", "1", "2", "3", "4", "5"]
        attribute "heatPumpWideVane", "ENUM", ["swing", "<<", "<", "|", ">", ">>", "<>"]
        attribute "thermostatFanModeOverride", "ENUM", ["quiet", "auto", "1", "2", "3", "4", "no-override"]

        preferences {
            input(
                name: "heatPumpArduinoHostname", 
                type: "string",
                title: "Hostname specified in your heat pump's WiFi configuration",
                description: "e.g. UpstairsHeat",
                required: true,
                displayDuringSetup: true
            )
        }
    }
}

def auto() { 
    logger("info", "COMMAND", "command auto called")
    state.modeOffSetByApp = false
    setThermostatDeviceMode("auto") 
}

def cool() {
    logger("info", "COMMAND", "command cool called")
    if (device.currentValue("thermostatMode") == "cool") { return }
    state.modeOffSetByApp = false
    setThermostatDeviceMode("cool") 
    runIn(1, "delayedTempSet", [data: device.currentValue("coolingSetpoint")])
}

def dry() {
    logger("info", "COMMAND", "command dry called")
    state.modeOffSetByApp = false
    setThermostatDeviceMode("dry") 
}

def fanOn() {
    logger("info", "COMMAND", "command fanOn called")
    state.modeOffSetByApp = false
    setThermostatDeviceMode("fan_only") 
}

def fanCirculate() {
    logger("info", "COMMAND", "command fanCirculate called")
    state.modeOffSetByApp = false
    setThermostatDeviceMode("fan_only") 
}

def heat() {
    logger("info", "COMMAND", "command heat called")
    if (device.currentValue("thermostatMode") == "heat") { return }
    state.modeOffSetByApp = false
    if (parent) {
        // let the parent app take over settings again
        state.lastTemperatureSetByApp = device.currentValue("heatingSetpoint")
    }
    setThermostatDeviceMode("heat") 
    runIn(1, "delayedTempSet", [data: device.currentValue("heatingSetpoint")])
}

def off() {
    logger("info", "COMMAND", "command off called")
    state.modeOffSetByApp = false
    setThermostatDeviceMode("off") 
    sendEvent(name: "thermostatMode", value: "off", isStateChange: true)
}

def setCoolingSetpoint(value) {
    value = value.toDouble().round()
    logger("info", "COMMAND", "command setCoolingSetpoint called: ${value}")
    state.lastTemperatureSetByApp = null
    
    if (device.currentValue("thermostatMode") == "cool") {
        sendEvent(name: "coolingSetpoint", value: value) 
        sendEvent(name: "heatingSetpoint", value: value) 
        runIn(2, "delayedTempSet", [data: value])
        
        unschedule(forceScheduledUpdateCheck)
        runIn(10, "forceScheduledUpdateCheck")
    } else {
        sendEvent(name: "coolingSetpoint", value: value) 
    }
}

def setHeatingSetpoint(value) {
    value = value.toDouble().round()
    logger("info", "COMMAND", "command setHeatingSetpoint called: ${value}")
    state.lastTemperatureSetByApp = null

    if (device.currentValue("thermostatMode") == "heat") {
        sendEvent(name: "thermostatSetpoint", value: value) 
        sendEvent(name: "heatingSetpoint", value: value) 
        runIn(2, "delayedTempSet", [data: value])
         
        unschedule(forceScheduledUpdateCheck)
        runIn(10, "forceScheduledUpdateCheck")
    } else {
        sendEvent(name: "heatingSetpoint", value: value) 
    }
}

def delayedTempSet(value) {
    logger("trace", "delayedTempSet", "calling temp/set with ${value}")
    publishCommand("temp/set", "${value}")
}

def forceScheduledUpdateCheck() {
    if (parent) {
        parent.scheduledUpdateCheck()
    }
}

// Allow us to tell the unit to override the fan mode set by the app.
// Set this back to "no-override" to let the app return to doing its thing.
def overrideThermostatFanMode(value) { 
    logger("info", "COMMAND", "command overrideThermostatFanMode called: ${value}")
    sendEvent(name: "thermostatFanModeOverride", value: value)
    if (value != "no-override") {
        setThermostatFanMode(value)
    }
}

// Hack so that the UI command works if you specify the real arg in the second param
def setThermostatFanMode(ignore, value) { setThermostatFanMode(value) }

def setThermostatMode(value) {
    logger("info", "COMMAND", "command setThermostatMode called: ${value}")
    state.modeOffSetByApp = false

    switch(value.toLowerCase()) {
        case "heat":
        heat()
        break;
        
        case "cool":
        cool()
        break;
        
        case "fan":
        fanOn()
        break;
        
        case "dry":
        dry()
        break;
        
        case "auto":
        auto()
        break;
        
        case "off":
        off()
        break;
    }
}

def setThermostatFanMode(value) {
    logger("info", "COMMAND", "command setThermostatFanMode called: ${value}")
    state.modeOffSetByApp = false
    publishCommand("fan/set", "${value}")
}

def setHeatPumpVane(value) {
    logger("info", "COMMAND", "command setHeatPumpVane called: ${value}")
    publishCommand("vane/set", "${value}")
}

def setFanSpeed() { logger("warn", "COMMAND", "command setFanSpeed not available on this device") }
def fanAuto() { logger("warn", "COMMAND", "command fanAuto not available on this device") }
def emergencyHeat() { logger("warn", "COMMAND", "command emergencyHeat not available on this device") }
def setSchedule(schedule) { logger("warn", "COMMAND", "setSchedule not available on this device") } 
                                    
//************************************************************
//************************************************************
def installed() {
    logger("info", "installed", "Device installed. Initializing to defaults.")

    sendEvent(name: "supportedThermostatFanModes", value: ["auto", "quiet", "1", "2", "3", "4"], isStateChange: true)
    sendEvent(name: "supportedThermostatModes", value: ["off", "auto", "heat", "cool", "fanOnly", "dry"] , isStateChange: true)
    sendEvent(name: "thermostatMode", value: "off", isStateChange: true)
    sendEvent(name: "thermostatFanMode", value: "auto", isStateChange: true)
    sendEvent(name: "thermostatOperatingState", value: "idle", isStateChange: true)
    sendEvent(name: "thermostatSetpoint", value: 68, isStateChange: true)
    sendEvent(name: "heatingSetpoint", value: 68, isStateChange: true)
    sendEvent(name: "coolingSetpoint", value: 68, isStateChange: true)
    sendEvent(name: "temperature", value: 68, isStateChange: true)
    sendEvent(name: "heatPumpWideVane", value: "swing", isStateChange: true)
    sendEvent(name: "heatPumpVane", value: "auto", isStateChange: true)
    sendEvent(name: "temperatureUnit", value: location.temperatureScale, isStateChange: true)
    sendEvent(name: "thermostatFanModeOverride", value: "no-override")
    
    updateDataValue("lastRunningMode", "heat")    
    state.lastTemperatureSetByApp = null
    state.modeOffSetByApp = false

    updated()
}

//************************************************************
//************************************************************
def updated() {
    state.lastHttpConnectionTime = 0

    if (getParentSettings()) {
        logger("info", "updated", "Device settings updated.")
        runIn(1, "refreshAfterUpdated")        
    } else {
        logger("trace", "updated", "Device settings not actually updated.")
    }
}

def refreshAfterUpdated() {
    logger("trace", "refreshAfterUpdated", "Setting up for HTTP")
    connectHttp()
    runEvery5Minutes("httpCheckConnection")
}

// ========================================================
// App-driven smart mode
// ========================================================
def wasLastTemperatureChangeByUser() {
    return state.lastTemperatureSetByApp == null
}

def wasModeOffSetByApp() {
    return state.modeOffSetByApp == true
}

def getLastRunningMode() {
    return getDataValue("lastRunningMode")
}

def handleAppTemperatureChange(value) {
    state.lastTemperatureSetByApp = value
    if (device.currentValue("thermostatSetpoint") != value) {
        logger("info", "handleAppTemperatureChange", "set temperature setpoint to ${value}")
        publishCommand("temp/set", "${value}")
    }
}

def handleAppThermostatFanMode(fanMode, requestOff) {
    fanMode = "${fanMode}"
    def thermostatMode = device.currentValue("thermostatMode")
    def thermostatIsOff = device.currentValue("thermostatOperatingState") == "off" || device.currentValue("thermostatOperatingState") == "idle"
    
    def currentFanMode = device.currentValue("thermostatFanMode")
    logger("trace", "handleAppThermostatFanMode", "set fan $fanMode with off $requestOff. Currently unit is in $thermostatMode and fan is at $currentFanMode")
    
    // If we are currently using the local temperature readings from the device, don't actually power it down,
    // because that makes the temperature readings go crazy.
    if (requestOff && usingRemoteTemperature()) {
        if (!thermostatIsOff) {
            logger("info", "handleAppThermostatFanMode", "turning unit off")
            state.modeOffSetByApp = true
            setThermostatDeviceMode("off")
        }
    }
    else {
        state.modeOffSetByApp = false
        if (thermostatIsOff) {
            def lastRunningMode = getDataValue("lastRunningMode")
            logger("info", "handleAppThermostatFanMode", "turning unit back on to $lastRunningMode")
            setThermostatDeviceMode(lastRunningMode)
        } else if (requestOff) {
            logger("info", "handleAppThermostatFanMode", "not turning unit off because remote temperature is not available")
        }
    }
    
    if (currentFanMode != fanMode) {
        logger("info", "handleAppThermostatFanMode", "set fan to $fanMode")
        publishCommand("fan/set", fanMode)
    }
}

// ========================================================
// Remote temperature sensor methods
// ========================================================
def usingRemoteTemperature()
{
    return state.lastRemoteTemperature != null
}

def clearRemoteTemperature() {
    if (state.lastRemoteTemperature != null) {
        state.lastRemoteTemperatureTime = null
        state.lastRemoteTemperature = null
        logger("debug", "clearRemoteTemperature", "resetting remote temp to 0")
        publishCommand("remote_temp/set", "0")
    }
}

def setRemoteTemperature(value) {
    // Don't update duplicate temperatures more than every minute.
    if (value != state.lastRemoteTemperature || (state.lastRemoteTemperatureTime < (now() - 60000))) {
        state.lastRemoteTemperatureTime = now()
        state.lastRemoteTemperature = value
        logger("trace", "setRemoteTemperature", "set remote_temp to ${value}")
        publishCommand("remote_temp/set", "${value}")
    } else {
        logger("trace", "setRemoteTemperature", "remote remote_temp unchaged at ${value}")
    }
}

def checkRemoteTemperatureForStaleness() {
    if (state.lastRemoteTemperatureTime != null && (state.lastRemoteTemperatureTime < (now() - 3600000))) {
        logger("warn", "checkRemoteTemperatureForStaleness", "resetting remote temp to 0 due to no data from sensors in over an hour")
        clearRemoteTemperature()
    }
}

// ========================================================
// HTTP METHODS
// ========================================================

def httpCheckConnection() {
    logger("trace", "httpCheckConnection", "Checking if HTTP connection is up")
    if (!http_connected()) {
        logger("warn", "httpCheckConnection", "HTTP connection is down; resetting")
        connectHttp()
    }
}

def connectHttp(boolean skipUpCheck = false) {
    if (skipUpCheck || !http_connected()) {
        def hubIpAddress = location.hubs[0].getDataValue("localIP")
        logger("info", "connectHttp", "Connecting to arduino via HTTP. Hubitat's IP address is $hubIpAddress")

        if (hubIpAddress == "127.0.0.1" || hubIpAddress == "" || hubIpAddress == null) { // This is a hubitat bug
            logger("error", "connectHttp", "Hubitat's IP address is wrong or missing! (This is a known hubitat bug.)")
            return;
        }
        
        Map headers = http_getHeaders()
        def uri = "http://${settings.heatPumpArduinoHostname}/hubitat_cmd?command=http_connect&hubitat_ip=$hubIpAddress"
        logger("debug", "connectHttp", "Sending GET to $uri")

        httpGet([uri: uri, headers: headers, timeout: 30]) { resp ->
            if (resp.success && resp.containsHeader("Connected")) {
                def macAddr = resp.getHeaders("Arduino-MAC").value.first() 

                logger("info", "connectHttp", "Success: ${resp.getStatus()}.")
                if (macAddr == null) {
                    logger("error", "connectHttp", "Device returned null mac address for \"${settings.heatPumpArduinoHostname}\"")
                    return
                }
                else if (device.deviceNetworkId != macAddr) {
                    logger("warn", "connectHttp", "Updating deviceNetworkId from ${device.deviceNetworkId} to $macAddr")
                    device.deviceNetworkId = macAddr
                    parent?.updateThermostatId(macAddr)
                }
                state.lastHttpConnectionTime = now()
            } else {
                logger("error", "connectHttp", "Connecting HTTP failed")
            }
        }
    }
}

boolean http_connected() {
    def isConnected = state.lastHttpConnectionTime > 0 && (now() - state.lastHttpConnectionTime < 300000)
    logger("trace", "http_connected", "Compare ${state.lastHttpConnectionTime} to ${now()} for isConnected=$isConnected")
    return isConnected;
}

def disconnectHttp() {
    logger("info", "disconnectHttp", "Disconnecting from arduino via HTTP")
    state.lastHttpConnectionTime = 0

    Map headers = http_getHeaders()
    def uri = "http://${settings.heatPumpArduinoHostname}/hubitat_cmd?command=http_disconnect"
    logger("debug", "disconnectHttp", "Sending GET to $uri")

    httpGet([uri: uri, headers: headers, timeout: 30]) { resp ->
        if (resp.success && resp.containsHeader("Disconnected")) {
            logger("trace", "disconnectHttp", "Success: ${resp.getStatus()}")
        } else {
            logger("error", "disconnectHttp", "Disconnecting HTTP failed")
        }
    }
}

private Map http_getHeaders() {
    Map headers = [:]
    headers.put("Host", settings.heatPumpArduinoHostname)
    headers.put("Content-Type", "application/x-www-form-urlencoded")
    return headers
}

// This method is called when Hubitat wants to control the heat pump (in HTTP mode).
private void http_sendCommand(messageType, body = "") { 
    if (!http_connected()) {
        connectHttp(true);
    }
    
    Map headers = http_getHeaders()
    def uri = "http://${settings.heatPumpArduinoHostname}/hubitat_cmd?messageType=$messageType&payload=$body"
    def timeSent = now()
    logger("trace", "http_sendCommand", "Posting to $uri [request: $timeSent]")

    try {
        asynchttpGet("http_parseResponseToWebRequest", [uri: uri, headers: headers, timeout: 30], [uri: uri, timeSent: timeSent])
    } catch (e) {
        logger("error", "http_sendCommand", "Error for $uri: $e")
    }
}

// Confirm that we get back a 200 response after sending commands from Hubitat.
void http_parseResponseToWebRequest(hubitat.scheduling.AsyncResponse asyncResponse, data) {
    if(asyncResponse != null && asyncResponse.getStatus() == 200) {
        logger("trace", "http_parseResponseToWebRequest", "received 200 after ${(now()-data.timeSent)/1000} seconds (request:${data.timeSent}; uri: ${data.uri})")
        state.lastHttpConnectionTime = now()
    } else {
        logger("warn", "http_parseResponseToWebRequest", "Request failed (${asyncResponse.getStatus()}). Disconnecting HTTP to reset (request:${data.timeSent}; uri: ${data.uri})")
        disconnectHttp()
        runIn(1, "connectHttp")
    }
}

// This method receives status and settings messages from the heat pump (over HTTP).
def parseHttp(String message) {
    def msg = parseLanMessage(message)
    messageType = msg.headers.MessageType
    
    state.lastHttpConnectionTime = now()
    if (messageType != "debug") {
        parseIncomingMessage(messageType, msg.body)
    }
}

// ========================================================
// COMMON MESSAGE HANDLING
// ========================================================

def publishCommand(messageType, payload) {
    logger("trace", "publishCommand", "Send $messageType: $payload (state.lastTemperatureSetByApp = ${state.lastTemperatureSetByApp})")
    runInMillis(1000, "publishCommandHandler", [data: [messageType, payload], overwrite: false])
}

def publishCommandHandler(messageType, payload) { // DTH: Collapse
    logger("debug", "publishCommandHandler", "Send $messageType: $payload (state.lastTemperatureSetByApp = ${state.lastTemperatureSetByApp})")
    http_sendCommand(messageType, payload)
}

def parse(String message) {
    parseHttp(message) // DTH: Collapse
}

def parseIncomingMessage(messageType, payload) {
    if (messageType == "alert") {
        return
    }
    logger("trace", "parseIncomingMessage", "Parsing messageType $messageType with payload $payload")
    
    def slurper = new groovy.json.JsonSlurper()
    def result = slurper.parseText(payload)
    
    if (messageType == "settings") {
        logger("debug", "parseIncomingMessage", "Parsing settings: $payload")
        parseSettings(result)
    } else if (messageType == "state") {
        logger("debug", "parseIncomingMessage", "Parsing state: $payload")
        parseState(result)
    } 
}

def setIfNotNullAndChanged(newValue, attributeName, sourceMethod, boolean forceLowerCase = true) {
    if (newValue != null && device.currentValue(attributeName).toString().toLowerCase() != newValue.toString().toLowerCase()) {
        logger("trace", sourceMethod, "setting ${attributeName} to ${newValue}")
        if (forceLowerCase) {
            sendEvent(name: attributeName, value: newValue.toString().toLowerCase()) 
        } else {
            sendEvent(name: attributeName, value: newValue.toString()) 
        }
        return true
    }
    return false
}

def updateMode(currentMode, newMode) 
{
   if (newMode == "fan_only") {
        newMode = "fanOnly"
    }
    
    if (newMode == currentMode) {
        return currentMode;
    }
    
     if (newMode != "off") {
        updateDataValue("lastRunningMode", newMode)    
        logger("info", "updateMode", "lastRunningMode changed to $newMode")
    }

    // Keep thermostatMode as the logical mode; if the app turns the unit off, e.g., leave this set to 'heat'
    if (!state.modeOffSetByApp || newMode != "off") {
        logger("info", "updateMode", "Mode changed from $currentMode to $newMode")
        sendEvent(name: "thermostatMode", value: newMode) 
        return newMode
    }
    
    return currentMode    
}

def parseSettings(parsedData) {
    def currentMode = device.currentValue("thermostatMode") // note: this gets the logical mode. We do not set this to 'off' when the app turns it off
    
    if(parsedData?.mode != null) {
        currentMode = updateMode(currentMode, parsedData.mode)
    }

    if (setIfNotNullAndChanged(parsedData?.temperature, "thermostatSetpoint", "parseSettings")) {
        def newTemp = parsedData.temperature
        logger("info", "parseSettings", "Set point changed to $newTemp (current mode is $currentMode)")
        if (currentMode == "heat") {
            logger("trace", "parseSettings", "setting heatingSetpoint to $newTemp")
            if (newTemp != state.lastTemperatureSetByApp) {
                logger("debug", "parseSettings", "external change to heatingSetpoint (${state.lastTemperatureSetByApp} vs $newTemp); resetting lastTemperatureSetByApp to null")
                state.lastTemperatureSetByApp = null
            }
            sendEvent(name: "heatingSetpoint", value: newTemp)
        } else if (currentMode == "cool") {
            logger("trace", "parseSettings", "setting coolingSetpoint to $newTemp")
            sendEvent(name: "coolingSetpoint", value: newTemp) 
        }
    }

    setIfNotNullAndChanged(parsedData?.fan, "thermostatFanMode", "parseSettings")
    setIfNotNullAndChanged(parsedData?.vane, "heatPumpVane", "parseSettings")
    setIfNotNullAndChanged(parsedData?.wideVane, "heatPumpWideVane", "parseSettings")
    if (setIfNotNullAndChanged(parsedData?.temperatureUnit, "temperatureUnit", "parseSettings", false) &&
        location.temperatureScale != parsedData.temperatureUnit) {
        logger("warn", "parseSettings", "Your hub is set to degrees ${location.temperatureScale} but this device reports using degrees ${parsedData.temperatureUnit}")
    }
}

def parseState(parsedData) {
    def currentMode = device.currentValue("thermostatMode") // gets the logical mode

    if(parsedData?.mode != null) {
        currentMode = updateMode(currentMode, parsedData.mode)
    }

    if (setIfNotNullAndChanged(parsedData?.temperature, "thermostatSetpoint", "parsedData")) {
        def newTemp = parsedData.temperature
        logger("info", "parseState", "Set point changed to $newTemp (current mode is $currentMode)")
        if (currentMode == "heat") {
            logger("trace", "parseState", "setting heatingSetpoint to $newTemp")
            if (newTemp != state.lastTemperatureSetByApp) {
                logger("debug", "parseState", "external change to heatingSetpoint (${state.lastTemperatureSetByApp} vs $newTemp); resetting lastTemperatureSetByApp to null")
                state.lastTemperatureSetByApp = null
            }
            sendEvent(name: "heatingSetpoint", value: newTemp) 
        } else if (currentMode == "cool") {
            logger("trace", "parseState", "setting coolingSetpoint to $newTemp")
            sendEvent(name: "coolingSetpoint", value: newTemp) 
        }
    }

    setIfNotNullAndChanged(parsedData?.action, "thermostatOperatingState", "parseState")    
    setIfNotNullAndChanged(parsedData?.roomTemperature, "temperature", "parseState")
    setIfNotNullAndChanged(parsedData?.fan, "thermostatFanMode", "parseState")
    setIfNotNullAndChanged(parsedData?.vane, "heatPumpVane", "parseState")
    setIfNotNullAndChanged(parsedData?.wideVane, "heatPumpWideVane", "parseState")
    
    if (parsedData?.compressorFrequency != null && state.heatPumpCompressorFrequency != parsedData.compressorFrequency) {
        logger("trace", "parseState", "setting heatPumpCompressorFrequency to ${parsedData.compressorFrequency}")
        state.heatPumpCompressorFrequency = parsedData.compressorFrequency 
        parent?.compressorFrequencyChanged(state.heatPumpCompressorFrequency)
    }    

    if (parsedData?.temperatureSource != null) {
        if (parsedData.temperatureSource == "local" && state.lastRemoteTemperatureTime != null) {
            logger("warn", "parseState", "The heat pump has reverted to using local temperature readings")
            clearRemoteTemperature()
        } else if (parsedData.temperatureSource == "remote" && state.lastRemoteTemperatureTime == null) {
            logger("warn", "parseState", "The heat pump thinks it is using remote temperatures but this app did not")
            clearRemoteTemperature()
        }
    }

    checkRemoteTemperatureForStaleness()
}

// ========================================================
// HELPERS
// ========================================================

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
    switch(level) {
        case "error":
            if (state.loggingLevel >= 1) log.error "[${source}] ${msg}"
            break

        case "warn":
            if (state.loggingLevel >= 2) log.warn "[${source}] ${msg}"
            break

        case "info":
            if (state.loggingLevel >= 3) log.info "[${source}] ${msg}"
            break

        case "debug":
            if (state.loggingLevel >= 4) log.debug "[${source}] ${msg}"
            break

        case "trace":
            if (state.loggingLevel >= 5) log.trace "[${source}] ${msg}"
            break

        default:
            log.debug "[${source}] ${msg}"
            break
    }
}


//************************************************************
// setLogLevel
//     Set log level via the child app
// Signature(s)
//     setLogLevel(level)
// Parameters
//     level :
// Returns
//     None
//************************************************************
def setLogLevel(level) {
    state.loggingLevel = level.toInteger()
    logger("warn", "setLogLevel", "Device logging level set to $state.loggingLevel")
}

def setThermostatDeviceMode(String value) {
    def thermostatMode = device.currentValue("thermostatMode")
    def thermostatIsOff = device.currentValue("thermostatOperatingState") == "off" || device.currentValue("thermostatOperatingState") == "idle"
    logger("debug", "setThermostatDeviceMode", "request setThermostatDeviceMode to $value compared to $thermostatMode (state.modeOffSetByApp = ${state.modeOffSetByApp}; thermostatIsOff = $thermostatIsOff)")

    if (value == null) {
        logger("error", "setThermostatDeviceMode", "setThermostatDeviceMode called with null")
    } else if (value != thermostatMode || value == "off" || thermostatIsOff) {
        logger("info", "setThermostatDeviceMode", "setThermostatDeviceMode to $value")
        publishCommand("mode/set", value)
    }
}
                             
def boolean getParentSetting(setting, type) {
    boolean valueChanged = false;

    def inheritedValue = parent?.getInheritedSetting(setting)
    if (inheritedValue != settings[setting]) {
        valueChanged = true;
        def displayValue = type == "password" ? "*******" : inheritedValue;
        logger("debug", "getParentSetting", "Inheriting value ${displayValue} for ${setting} from parent")
        device.updateSetting(setting, [value: inheritedValue, type: type])
    } else {
        logger("trace", "getParentSetting", "Unchanged value for ${setting} from parent")
    }
    
    return valueChanged;
}

// Returns true if any setting has changed that requires the connection to be reset
def boolean getParentSettings() {
    def anyChanged = getParentSetting("heatPumpArduinoHostname", "bool")
    return anyChanged
}
