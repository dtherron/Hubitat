/*
 *  Mitsubishi2Mqtt Thermostat Device Driver
 *  Project URL: https://github.com/dtherron/Hubitat/edit/main/Apps/mitsubishi2mqttThermostat
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
 * 
 */

import groovy.json.JsonSlurper
import groovy.json.JsonOutput

public static String version() { return "v1.0.0" }
public static String rootTopic() { return "hubitat" }

metadata {
    definition (name: "Mitsubishi2Mqtt Thermostat Device", 
        namespace: "dtherron", 
        author: "Dan Herron",
        importUrl: "https://raw.githubusercontent.com/dtherron/Hubitat/main/Apps/mitsubishi2mqttThermostat/mitsubishi2mqttThermostat-Device.groovy") {
        
        capability "Thermostat"
        capability "Sensor"
        capability "Actuator"
        capability "Temperature Measurement"

        command "setThermostatFanMode", [[name:"Fan mode (ignore above)", type: "ENUM", description:"Set the heat pump's fan speed setting", constraints: ["quiet", "auto", "1", "2", "3", "4"]]]
        command "setHeatPumpVane", ["string"]
        command "dry"
        
        attribute "trueThermostatMode", "ENUM", ["heat", "cool", "fan_only", "dry", "auto", "off"]
        attribute "temperatureUnit", "ENUM", ["C", "F"]
        attribute "heatPumpVane", "ENUM", ["auto", "swing", "1", "2", "3", "4", "5"]
        attribute "heatPumpWideVane", "ENUM", ["swing", "<<", "<", "|", ">", ">>", "<>"]

        preferences {
            input (
                name: 'useMqtt',
                type: 'bool',
                title: 'True to use MQTT; false to use direct HTTP',
                required: false,
                defaultValue: true,
                displayDuringSetup: true
            )
            input(
                name: "brokerIp", 
                type: "string",
                title: "MQTT Broker IP Address",
                description: "e.g. 192.168.1.200",
                required: true,
                displayDuringSetup: true
            )
            input(
                name: "brokerPort", 
                type: "string",
                title: "MQTT Broker Port",
                description: "e.g. 1883",
                required: true,
                displayDuringSetup: true
            )
            input(
                name: "brokerUser", 
                type: "string",
                title: "MQTT Broker Username",
                description: "e.g. mqtt_user",
                required: false,
                displayDuringSetup: true
            )
            input(
                name: "brokerPassword", 
                type: "password",
                title: "MQTT Broker Password",
                description: "e.g. ^L85er1Z7g&%2En!",
                required: false,
                displayDuringSetup: true
            )
            input(
                name: "mqttTopic", 
                type: "string",
                title: "MQTT topic specified heat pump's configuration",
                description: "e.g. mitsubishi2mqtt",
                defaultValiue: "mitsubishi2mqtt",
                required: true,
                displayDuringSetup: true
            )
            input(
                name: "heatPumpFriendlyName", 
                type: "string",
                title: "Friendly Name specified in your heat pump's configuration",
                description: "e.g. UpstairsHeat",
                required: true,
                displayDuringSetup: true
            )
            input (
                name: 'arduinoAddress',
                type: 'string',
                title: 'Local IP address or name of the heat pump\'s arduino:',
                required: false,
                defaultValue: 'UpstairsHeat',
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
    state.modeOffSetByApp = false
    setThermostatDeviceMode("cool") 
    publishCommand("temp/set", device.currentValue("coolingSetpoint"))
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
    state.modeOffSetByApp = false
    if (parent) {
        // let the parent app take over settings again
        state.lastTemperatureSetByApp = true
        parent.scheduledUpdateCheck()
    }
    setThermostatDeviceMode("heat") 
    publishCommand("temp/set", device.currentValue("heatingSetpoint"))
}

def off() {
    logger("info", "COMMAND", "command off called")
    state.modeOffSetByApp = false
    setThermostatDeviceMode("off") 
}

def setCoolingSetpoint(value) {
    value = value.toDouble().round()
    logger("info", "COMMAND", "command setCoolingSetpoint called: ${value}")
    state.lastTemperatureSetByApp = false
    
    if (device.currentValue("thermostatMode") == "cool") {
        publishCommand("temp/set", "${value}")
        if (parent) {
            parent.scheduledUpdateCheck()
        }
    } else {
        sendEvent(name: "coolingSetpoint", value: value) 
    }
}

def setHeatingSetpoint(value) {
    value = value.toDouble().round()
    logger("info", "COMMAND", "command setHeatingSetpoint called: ${value}")
    state.lastTemperatureSetByApp = false

    if (device.currentValue("thermostatMode") == "heat") {
        publishCommand("temp/set", "${value}")
        if (parent) {
            parent.scheduledUpdateCheck()
        }
    } else {
        sendEvent(name: "heatingSetpoint", value: value) 
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
    sendEvent(name: "trueThermostatMode", value: "off", isStateChange: true)
    sendEvent(name: "thermostatFanMode", value: "auto", isStateChange: true)
    sendEvent(name: "thermostatOperatingState", value: "idle", isStateChange: true)
    sendEvent(name: "thermostatSetpoint", value: 68, isStateChange: true)
    sendEvent(name: "heatingSetpoint", value: 68, isStateChange: true)
    sendEvent(name: "coolingSetpoint", value: 68, isStateChange: true)
    sendEvent(name: "temperature", value: 68, isStateChange: true)
    sendEvent(name: "heatPumpWideVane", value: "swing", isStateChange: true)
    sendEvent(name: "heatPumpVane", value: "auto", isStateChange: true)
    sendEvent(name: "temperatureUnit", value: location.temperatureScale, isStateChange: true)
    
    updateDataValue("lastRunningMode", "heat")    
    state.lastTemperatureSetByApp = false
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
    if (settings.useMqtt) {
        logger("trace", "refreshAfterUpdated", "Setting up for MQTT")
        unschedule("httpCheckConnection")
        disconnectMqtt()
        disconnectHttp()
        runIn(1, "connectMqtt")
    } else {
        logger("trace", "refreshAfterUpdated", "Setting up for HTTP")
        disconnectMqtt();
        connectHttp()
        runEvery5Minutes("httpCheckConnection")
    }
}

// ========================================================
// App-driven smart mode
// ========================================================
def wasLastTemperatureChangeByApp() {
    return state.lastTemperatureSetByApp == true
}

def wasModeOffSetByApp() {
    return state.modeOffSetByApp == true
}

def getLastRunningMode() {
    return getDataValue("lastRunningMode")
}

def handleAppTemperatureChange(value) {
    if (device.currentValue("thermostatSetpoint") != value) {
        logger("info", "handleAppTemperatureChange", "set temperature setpoint to ${value}")
        state.lastTemperatureSetByApp = true
        publishCommand("temp/set", "${value}")
    }
}

def handleAppThermostatFanMode(fanMode, requestOff) {
    def currentMode = device.currentValue("trueThermostatMode")
    def currentFanMode = device.currentValue("thermostatFanMode")
    logger("trace", "handleAppThermostatFanMode", "set fan $fanMode with off $requestOff. Currently unit is in $currentMode and fan is at $currentFanMode")
    
    // If we are currently using the local temperature readings from the device, don't actually power it down,
    // because that makes the temperature readings go crazy.
    if (requestOff && usingRemoteTemperature()) {
        if (currentMode != "off") {
            logger("info", "handleAppThermostatFanMode", "turning unit off")
            state.modeOffSetByApp = true
            setThermostatDeviceMode("off")
        }
    }
    else {
        state.modeOffSetByApp = false
        if (currentMode == "off") {
            def lastRunningMode = getDataValue("lastRunningMode")
            logger("info", "handleAppThermostatFanMode", "turning unit back on to $lastRunningMode")
            setThermostatDeviceMode(lastRunningMode)
        } else if (requestOff) {
            logger("info", "handleAppThermostatFanMode", "not turning unit off because remote temperature is not available")
        }
    }
    
    if (currentFanMode != fanMode) {
        logger("debug", "handleAppThermostatFanMode", "set fan to $fanMode")
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
// MQTT COMMANDS
// ========================================================

def subscribe(topic) {
    if (notMqttConnected()) {
        connectMqtt()
    }
    
    logger("info", "subscribe", "full topic: ${getTopicPrefix()}${topic}")
    interfaces.mqtt.subscribe("${getTopicPrefix()}${topic}")
}

def connectMqtt() {
    if (!settings.useMqtt) {
        logger("error", "connectMqtt", "This was called when useMqtt was FALSE")
        return;
    }
    
    logger("info", "connectMqtt", "Connecting to MQTT broker as client ${getClientId()}")
    
    try {   
        interfaces.mqtt.connect(getBrokerUri(),
                           getClientId(), 
                           settings?.brokerUser, 
                           settings?.brokerPassword)
       
        // delay for connection
        pauseExecution(1000)        
    } catch(Exception e) {
        logger("error", "connectMqtt", "Connecting MQTT failed: ${e}")
        return
    }

    subscribe("state")
    subscribe("settings")
    // subscribe("debug")
}

def disconnectMqtt() {
    logger("info", "disconnectMqtt", "Disconnecting from MQTT broker")
    try {
        interfaces.mqtt.disconnect()
    } catch(e) {
        logger("warn", "disconnectMqtt", "Disconnection from broker failed: ${e.message}")
    }
}

// ========================================================
// MQTT METHODS
// ========================================================

def normalize(name) {
    return name.replaceAll("[^a-zA-Z0-9]+","-").toLowerCase()
}

def getBrokerUri() {        
    return "tcp://${settings.brokerIp}:${settings.brokerPort}"
}

def getClientId() {
    def hub = location.hubs[0]
    def hubNameNormalized = normalize(hub.name)
    return "hubitat_${hubNameNormalized}-${hub.hardwareID}-${device.getId()}".toLowerCase()
}

def getTopicPrefix() {
    return "${settings.mqttTopic}/${settings.heatPumpFriendlyName}/"
}

def mqttConnected() {
    return interfaces.mqtt.isConnected()
}

def notMqttConnected() {
    return !mqttConnected()
}

def mqttClientStatus(status) {
    logger("debug", "mqttClientStatus", "status: ${status}")
}

def publishMqtt(topic, payload) {
    if (notMqttConnected()) {
        connectMqtt()
    }
    
    def pubTopic = "${getTopicPrefix()}${topic}"

    try {
        interfaces.mqtt.publish("${pubTopic}", "${payload}", 0, false)
        logger("trace", "publishMqtt", "topic: ${pubTopic} payload: ${payload}")
        
    } catch (Exception e) {
        logger("error", "publishMqtt", "Unable to publish message: ${e}")
    }
}

// Parse incoming message from the MQTT broker
def parseMqtt(String event) {
    def message = interfaces.mqtt.parseMessage(event)  
    def (topic, friendlyName, messageType) = message.topic.tokenize( '/' )
    
    logger("trace", "parseMqtt", "Received MQTT message type ${messageType} on topic ${topic} from FN ${friendlyName} with value ${message.payload}")
    parseIncomingMessage(messageType, message.payload)
}

// ========================================================
// HTTP METHODS
// ========================================================

def httpCheckConnection() {
    logger("trace", "httpCheckConnection", "Checking if HTTP connection is up")
    if (!settings.useMqtt && !http_connected()) {
        logger("warn", "httpCheckConnection", "HTTP connection is down; resetting")
        connectHttp()
    }
}

def connectHttp(boolean skipUpCheck = false) {
    if (settings.useMqtt) {
        logger("error", "connectHttp", "This was called when useMqtt was TRUE")
        return;
    }

    if (skipUpCheck || !http_connected()) {
        logger("info", "connectHttp", "Connecting to arduino via HTTP")
    
        def hubIpAddress = location.hubs[0].getDataValue("localIP")
        logger("trace", "connectHttp", "Hubitat's IP address is $hubIpAddress")
        
        Map headers = http_getHeaders()
        def uri = "http://${settings.arduinoAddress}/hubitat_cmd?command=http_connect&hubitat_ip=$hubIpAddress"
        logger("debug", "connectHttp", "Sending GET to $uri")

        httpGet([uri: uri, headers: headers]) { resp ->
            if (resp.success && resp.containsHeader("Connected") && resp.containsHeader("Arduino-MAC")) {
                def macAddr = resp.getHeaders("Arduino-MAC")[0].getValue();
                logger("debug", "connectHttp", "Success: ${resp.getStatus()}. Got MAC address $macAddr (device currently set to ${device.deviceNetworkId})")
                if (device.deviceNetworkId != macAddr) {
                    logger("warn", "connectHttp", "Updating deviceNetworkId to $macAddr")
                    device.deviceNetworkId = macAddr
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
    def uri = "http://${settings.arduinoAddress}/hubitat_cmd?command=http_disconnect"
    logger("debug", "disconnectHttp", "Sending GET to $uri")

    httpGet([uri: uri, headers: headers]) { resp ->
        if (resp.success && resp.containsHeader("Disconnected")) {
            logger("trace", "disconnectHttp", "Success: ${resp.getStatus()}")
        } else {
            logger("error", "disconnectHttp", "Disconnecting HTTP failed")
        }
    }
}

private Map http_getHeaders() {
    Map headers = [:]
    headers.put("Host", settings.arduinoAddress)
    headers.put("Content-Type", "application/x-www-form-urlencoded")
    return headers
}

// This method is called when Hubitat wants to control the heat pump (in HTTP mode). It
// sends the same payload as MQTT and passes the MQTT topic in a query param.
private void http_sendCommand(String topic, String body = "") { 
    if (!http_connected()) {
        connectHttp(true);
    }
    
    def pubTopic = "${getTopicPrefix()}${topic}"
    Map headers = http_getHeaders()
    def uri = "http://${settings.arduinoAddress}/hubitat_cmd?topic=$pubTopic&payload=$body"
    logger("debug", "http_sendCommand", "Posting to $uri")

    try {
        asynchttpGet("http_parseResponseToWebRequest", [uri: uri, headers: headers])
    } catch (e) {
        logger("error", "http_sendCommand", "Error for $uri: $e")
    }
}

// Confirm that we get back a 200 response after sending commands from Hubitat.
void http_parseResponseToWebRequest(hubitat.scheduling.AsyncResponse asyncResponse, data) {
    if(asyncResponse != null && asyncResponse.getStatus() == 200) {
        logger("debug", "http_parseResponseToWebRequest", "received 200")
        state.lastHttpConnectionTime = now()
    } else {
        logger("warn", "http_parseResponseToWebRequest", "Request failed (${asyncResponse.getStatus()}). Disconnecting HTTP to reset.")
        disconnectHttp()
    }
}

// This method receives status and settings messages from the heat pump (over HTTP). The same MQTT topic and payload
// are encoded in the body and headers, and sent to the common method of parsing.
def parseHttp(String message) {
    def msg = parseLanMessage(message)
    topic =  msg.headers.Topic
    def (topic, friendlyName, messageType) = msg.headers.Topic.tokenize( '/' )
    
    logger("debug", "parseHttp", "Received HTTP message type ${messageType} on topic ${topic} from FN ${friendlyName} with value ${msg.body}")
    state.lastHttpConnectionTime = now()
    if (topic != "debug") {
        parseIncomingMessage(messageType, msg.body)
    }
}

// ========================================================
// COMMON MESSAGE HANDLING
// ========================================================

def publishCommand(topic, payload) {
    if (settings.useMqtt) {
        publishMqtt(topic, payload)
    } else {
        http_sendCommand(topic, payload)
    }
}

def parse(String message) {
    logger("trace", "parse", "Received (with MQTT=${settings.useMqtt}): $message")
    if (settings.useMqtt) {
        parseMqtt(message)
    } else {
        parseHttp(message)
    }
}

def parseIncomingMessage(messageType, payload) {
    def slurper = new groovy.json.JsonSlurper()
    def result = slurper.parseText(payload)
    
    if (messageType == "settings") {
        parseSettings(result)
    } else if (messageType == "state") {
        parseState(result)
    }
}

def setIfNotNullAndChanged(newValue, attributeName, sourceMethod, boolean forceLowerCase = true) {
    if (attributeName == "trueThermostatMode" && newValue == "fan_only") {
        forceLowerCase = false
        newValue = "fanOnly"
    }
    
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

def parseSettings(parsedSettings) {
    def currentMode = device.currentValue("trueThermostatMode")
    if (setIfNotNullAndChanged(parsedSettings?.mode, "trueThermostatMode", "parseSettings")) {
        logger("info", "parseSettings", "Mode changed to ${parsedSettings.mode}")
        currentMode = parsedSettings.mode.toLowerCase()
        if (!state.modeOffSetByApp) {
            sendEvent(name: "thermostatMode", value: currentMode) 
        }
        if (currentMode != "off") {
            updateDataValue("lastRunningMode", parsedSettings.mode.toLowerCase())    
            logger("debug", "parseState", "lastRunningMode changed to $currentMode")
        }
    }

    if (setIfNotNullAndChanged(parsedSettings?.temperature, "thermostatSetpoint", "parseSettings")) {
        logger("info", "parseSettings", "Set point changed to ${parsedSettings.temperature}")
        if (currentMode == "heat") {
            logger("trace", "parseSettings", "setting heatingSetpoint to ${parsedSettings.temperature}")
            sendEvent(name: "heatingSetpoint", value: parsedSettings.temperature) 
        } else if (currentMode == "cool") {
            logger("trace", "parseSettings", "setting coolingSetpoint to ${parsedSettings.temperature}")
            sendEvent(name: "coolingSetpoint", value: parsedSettings.temperature) 
        }
    }

    setIfNotNullAndChanged(parsedSettings?.fan, "thermostatFanMode", "parseSettings")
    setIfNotNullAndChanged(parsedSettings?.vane, "heatPumpVane", "parseSettings")
    setIfNotNullAndChanged(parsedSettings?.wideVane, "heatPumpWideVane", "parseSettings")
    if (setIfNotNullAndChanged(parsedSettings?.temperatureUnit, "temperatureUnit", "parseSettings", false) &&
        location.temperatureScale != parsedSettings.temperatureUnit) {
        logger("warn", "parseSettings", "Your hub is set to degrees ${location.temperatureScale} but this device reports using degrees ${parsedSettings.temperatureUnit}")
    }
}

def parseState(parsedState) {
    def currentMode = device.currentValue("trueThermostatMode")
    if (setIfNotNullAndChanged(parsedState?.mode, "trueThermostatMode", "parseState")) {
        logger("info", "parseState", "Mode changed to ${parsedState.mode}")
        currentMode = parsedState.mode.toLowerCase()
        if (!state.modeOffSetByApp) {
            sendEvent(name: "thermostatMode", value: currentMode) 
        }
        if (currentMode != "off") {
            updateDataValue("lastRunningMode", parsedState.mode.toLowerCase())    
            logger("debug", "parseState", "lastRunningMode changed to $currentMode")
        }
    }

    if (setIfNotNullAndChanged(parsedState?.temperature, "thermostatSetpoint", "parseState")) {
        logger("info", "parseState", "Set point changed to ${parsedState.temperature}")
        if (currentMode == "heat") {
            logger("trace", "parseState", "setting heatingSetpoint to ${parsedState.temperature}")
            sendEvent(name: "heatingSetpoint", value: parsedState.temperature) 
        } else if (currentMode == "cool") {
            logger("trace", "parseState", "setting coolingSetpoint to ${parsedState.temperature}")
            sendEvent(name: "coolingSetpoint", value: parsedState.temperature) 
        }
    }

    setIfNotNullAndChanged(parsedState?.action, "thermostatOperatingState", "parseState")    
    setIfNotNullAndChanged(parsedState?.roomTemperature, "temperature", "parseState")
    setIfNotNullAndChanged(parsedState?.fan, "thermostatFanMode", "parseState")
    setIfNotNullAndChanged(parsedState?.vane, "heatPumpVane", "parseState")
    setIfNotNullAndChanged(parsedState?.wideVane, "heatPumpWideVane", "parseState")
    
    if (parsedState?.compressorFrequency != null && state.heatPumpCompressorFrequency != parsedState.compressorFrequency) {
        logger("trace", "parseState", "setting heatPumpCompressorFrequency to ${parsedState.compressorFrequency}")
        state.heatPumpCompressorFrequency = parsedState.compressorFrequency 
        parent?.compressorFrequencyChanged(state.heatPumpCompressorFrequency)
    }    

    if (parsedState?.temperatureSource != null) {
        if (parsedState.temperatureSource == "local" && state.lastRemoteTemperatureTime != null) {
            logger("warn", "parseState", "The heat pump has reverted to using local temperature readings")
            clearRemoteTemperature()
        } else if (parsedState.temperatureSource == "remote" && state.lastRemoteTemperatureTime == null) {
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
    if (value == null) {
        logger("error", "setThermostatDeviceMode", "setThermostatDeviceMode called with null")
    } else if (value != device.currentValue("trueThermostatMode")) {
        logger("debug", "setThermostatDeviceMode", "setThermostatDeviceMode to ${value}")
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

// Returns true if any setting has changed that requires the MQTT connection to be reset
def boolean getParentSettings() {
    return getParentSetting("useMqtt", "bool") ||
        getParentSetting("brokerIp", "string") ||
        getParentSetting("brokerPort", "string") ||
        getParentSetting("brokerUser", "string") ||
        getParentSetting("brokerPassword", "password") ||
        getParentSetting("mqttTopic", "string") ||
        getParentSetting("heatPumpFriendlyName", "string") ||
        getParentSetting("arduinoAddress", "string")
}
