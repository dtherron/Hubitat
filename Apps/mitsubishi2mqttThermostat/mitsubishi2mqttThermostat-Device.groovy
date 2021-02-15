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

        command "setThermostatFanMode", [[name:"Fan mode (ignore above)", type: "ENUM", description:"Set the heat pump's fan speed setting", constraints: ["QUIET", "AUTO", "1", "2", "3", "4"]]]
        command "setHeatPumpVane", ["string"]
        
        attribute "temperatureUnit", "ENUM", ["C", "F"]
        attribute "heatPumpVane", "ENUM", ["AUTO", "SWING", "1", "2", "3", "4", "5"]
        attribute "heatPumpWideVane", "ENUM", ["SWING", "<<", "<", "|", ">", ">>", "<>"]

		preferences {
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
        }
	}
}

def auto() { 
    logger("info", "COMMAND", "command auto called")
    setThermostatMode("auto") 
}

def cool() {
    logger("info", "COMMAND", "command cool called")
    setThermostatMode("cool") 
}

def heat() {
    logger("info", "COMMAND", "command heat called")
    setThermostatMode("heat") 
}

def off() {
    logger("info", "COMMAND", "command off called")
    setThermostatMode("off") 
}

def setCoolingSetpoint(value) {
    logger("info", "COMMAND", "command setCoolingSetpoint called: ${value}")
    publishMqtt("temp/set", "${value}")
}

def setHeatingSetpoint(value) {
    logger("info", "COMMAND", "command setHeatingSetpoint called: ${value}")
    publishMqtt("temp/set", "${value}")
}

// Hack so that the UI command works if you specify the real arg in the second param
def setThermostatFanMode(ignore, value) { setThermostatFanMode(value) }

def setThermostatFanMode(value) {
    logger("info", "COMMAND", "command setThermostatFanMode called: ${value}")
    publishMqtt("fan/set", "${value}")
}

def setHeatPumpVane(value) {
    logger("info", "COMMAND", "command setHeatPumpVane called: ${value}")
    publishMqtt("vane/set", "${value}")
}

def setFanSpeed() { logger("warn", "COMMAND", "command fanCirculate not available on this device") }
def fanCirculate() { logger("warn", "COMMAND", "command fanCirculate not available on this device") }
def fanOn() { logger("warn", "COMMAND", "command fanOn not available on this device") }
def fanAuto() { logger("warn", "COMMAND", "command fanAuto not available on this device") }
def emergencyHeat() { logger("warn", "COMMAND", "command emergencyHeat not available on this device") }
def setSchedule(schedule) { logger("warn", "COMMAND", "setSchedule not available on this device") } 
                                           
//************************************************************
//************************************************************
def installed() {
    logger("info", "installed", "Device installed. Initializing to defaults.")

    sendEvent(name: "supportedThermostatFanModes", value: ["AUTO", "QUIET", "1", "2", "3", "4"], isStateChange: true)
	sendEvent(name: "supportedThermostatModes", value: ["off", "auto", "heat", "cool"] , isStateChange: true)
	sendEvent(name: "thermostatMode", value: "off", isStateChange: true)
	sendEvent(name: "thermostatFanMode", value: "AUTO", isStateChange: true)
	sendEvent(name: "thermostatOperatingState", value: "idle", isStateChange: true)
	sendEvent(name: "thermostatSetpoint", value: 68, isStateChange: true)
	sendEvent(name: "heatingSetpoint", value: 68, isStateChange: true)
	sendEvent(name: "coolingSetpoint", value: 68, isStateChange: true)
    sendEvent(name: "temperature", value: 68, isStateChange: true)
    sendEvent(name: "heatPumpWideVane", value: "SWING", isStateChange: true)
    sendEvent(name: "heatPumpVane", value: "AUTO", isStateChange: true)
    sendEvent(name: "temperatureUnit", value: location.temperatureScale, isStateChange: true)
    
	updateDataValue("lastRunningMode", "off")	

    getParentSettings()
	disconnect()
	connect()
}

//************************************************************
//************************************************************
def updated() {
    logger("info", "updated", "Device settings updated.")
    // TODO: make getParentSettings tell us if any settings changed
    getParentSettings()
	disconnect()
	connect()	
}

// ========================================================
// Remote temperature sensor methods
// ========================================================
def clearRemoteTemperature() {
    if (state.lastRemoteTemperature != null) {
        logger("debug", "clearRemoteTemperature", "resetting remote temp to 0")
        publishMqtt("remote_temp/set", "0")
        state.lastRemoteTemperatureTime = null
        state.lastRemoteTemperature = null
    }
}

def setRemoteTemperature(value) {
    // Don't update duplicate temperatures more than every five minutes.
    synchronized(this) {
        if (value != state.lastRemoteTemperature || (state.lastRemoteTemperatureTime < (now() - 300000))) {
            state.lastRemoteTemperatureTime = now()
            state.lastRemoteTemperature = value
            logger("trace", "setRemoteTemperature", "set remote_temp to ${value}")
            publishMqtt("remote_temp/set", "${value}")
        } else {
            logger("trace", "setRemoteTemperature", "remote remote_temp unchaged at ${value}")
        }
    }
}

def checkRemoteTemperatureForStaleness() {
    synchronized(this) {
        if (state.lastRemoteTemperatureTime != null && (state.lastRemoteTemperatureTime < (now() - 3600000))) {
            logger("warn", "checkRemoteTemperatureForStaleness", "resetting remote temp to 0 due to no data from sensors in over an hour")
            clearRemoteTemperature()
        }
    }
}

// ========================================================
// MQTT COMMANDS
// ========================================================

def subscribe(topic) {
    synchronized(this) {
        if (notMqttConnected()) {
            connect()
        }
    }
    
    logger("info", "subscribe", "full topic: ${getTopicPrefix()}${topic}")
    interfaces.mqtt.subscribe("${getTopicPrefix()}${topic}")
}

def unsubscribe(topic) {
    synchronized(this) {
        if (notMqttConnected()) {
            connect()
        }
    }
    
    logger("info", "unsubscribe", "full topic: ${getTopicPrefix()}${topic}")
    interfaces.mqtt.unsubscribe("${getTopicPrefix()}${topic}")
}

def connect() {
    logger("info", "connect", "Connecting to MQTT broker as client ${getClientId()}")
    
    try {   
        interfaces.mqtt.connect(getBrokerUri(),
                           getClientId(), 
                           settings?.brokerUser, 
                           settings?.brokerPassword)
       
        // delay for connection
        pauseExecution(1000)        
    } catch(Exception e) {
        logger("error", "connect", "Connecting MQTT failed: ${e}")
        return
    }

    subscribe("state")
    subscribe("settings")
	// subscribe("debug")
}

def disconnect() {
    logger("info", "disconnect", "Disconnecting from MQTT broker")
    try {
        interfaces.mqtt.disconnect()
    } catch(e) {
        logger("warn", "disconnect", "Disconnection from broker failed: ${e.message}")
    }
}

// ========================================================
// MQTT METHODS
// ========================================================

// Parse incoming message from the MQTT broker
def parse(String event) {
    def message = interfaces.mqtt.parseMessage(event)  
    def (topic, friendlyName, messageType) = message.topic.tokenize( '/' )
    
    logger("trace", "parse", "Received MQTT message type ${messageType} on topic ${topic} from FN ${friendlyName} with value ${message.payload}")

    def slurper = new groovy.json.JsonSlurper()
    def result = slurper.parseText(message.payload)
    
    if (messageType == "settings") {
        parseSettings(result)
    } else if (messageType == "state") {
        parseState(result)
    }
}

def setIfNotNullAndChanged(newValue, attributeName, sourceMethod) {
    if (newValue != null && device.currentValue(attributeName) != newValue) {
        logger("trace", sourceMethod, "setting ${attributeName} to ${newValue}")
        sendEvent(name: attributeName, value: newValue) 
        return true
    }
    return false
}

def parseSettings(parsedSettings) {
    if (setIfNotNullAndChanged(parsedSettings?.mode, "thermostatMode", "parseSettings")) {
        updateDataValue("lastRunningMode", parsedSettings.mode)	
        logger("info", "parseSettings", "Mode changed to ${parsedSettings.mode}")
    }

    if (setIfNotNullAndChanged(parsedSettings?.temperature, "thermostatSetpoint", "parseSettings")) {
        logger("info", "parseSettings", "Set point changed to ${parsedSettings.temperature}")
        if (device.currentValue("thermostatMode") == "heat") {
            logger("trace", "parseSettings", "setting heatingSetpoint to ${parsedSettings.temperature}")
            sendEvent(name: "heatingSetpoint", value: parsedSettings.temperature) 
        } else if (device.currentValue("thermostatMode") == "cool") {
            logger("trace", "parseSettings", "setting coolingSetpoint to ${parsedSettings.temperature}")
            sendEvent(name: "coolingSetpoint", value: parsedSettings.temperature) 
        }
    }

    setIfNotNullAndChanged(parsedSettings?.fan, "thermostatFanMode", "parseSettings")
    setIfNotNullAndChanged(parsedSettings?.vane, "heatPumpVane", "parseSettings")
    setIfNotNullAndChanged(parsedSettings?.wideVane, "heatPumpWideVane", "parseSettings")
    if (setIfNotNullAndChanged(parsedSettings?.temperatureUnit, "temperatureUnit", "parseSettings") &&
        location.temperatureScale != parsedSettings.temperatureUnit) {
        logger("warn", "parseSettings", "Your hub is set to degrees ${location.temperatureScale} but this device reports using degrees ${parsedSettings.temperatureUnit}")
    }

    if (parsedSettings?.temperatureSource != null) {
        if (parsedSettings.temperatureSource == "local" && state.lastRemoteTemperatureTime != null) {
            logger("warn", "parseSettings", "The heat pump has reverted to using local temperature readings")
            clearRemoteTemperature()
        } else if (parsedSettings.temperatureSource == "remote" && state.lastRemoteTemperatureTime == null) {
            logger("warn", "parseSettings", "The heat pump thinks it is using remote temperatures but this app did not")
            clearRemoteTemperature()
        }
    }
}

def parseState(parsedState) {
    if (setIfNotNullAndChanged(parsedState?.mode, "thermostatMode", "parseState")) {
        logger("info", "parseState", "lastRunningMode changed to ${parsedState.mode}")
        updateDataValue("lastRunningMode", parsedState.mode)	
    }

    if (setIfNotNullAndChanged(parsedState?.temperature, "thermostatSetpoint", "parseState")) {
        logger("info", "parseState", "Set point changed to ${parsedState.temperature}")
        if (device.currentValue("thermostatMode") == "heat") {
            logger("trace", "parseState", "setting heatingSetpoint to ${parsedState.temperature}")
            sendEvent(name: "heatingSetpoint", value: parsedState.temperature) 
        } else if (device.currentValue("thermostatMode") == "cool") {
            logger("trace", "parseState", "setting coolingSetpoint to ${parsedState.temperature}")
            sendEvent(name: "coolingSetpoint", value: parsedState.temperature) 
        }
    }

    if (setIfNotNullAndChanged(parsedState?.action, "thermostatOperatingState", "parseState")) {
        if (device.currentValue("thermostatMode") == "heat") {
            logger("trace", "parseState", "setting heatingSetpoint to ${parsedState.temperature}")
            sendEvent(name: "heatingSetpoint", value: parsedState.temperature) 
        } else if (device.currentValue("thermostatMode") == "cool") {
            logger("trace", "parseState", "setting coolingSetpoint to ${parsedState.temperature}")
            sendEvent(name: "coolingSetpoint", value: parsedState.temperature) 
        }
    }
    
    setIfNotNullAndChanged(parsedState?.roomTemperature, "temperature", "parseState")
    setIfNotNullAndChanged(parsedState?.fan, "thermostatFanMode", "parseState")
    setIfNotNullAndChanged(parsedState?.vane, "heatPumpVane", "parseState")
    setIfNotNullAndChanged(parsedState?.wideVane, "heatPumpWideVane", "parseState")
    
    if (parsedState?.compressorFrequency != null && state.heatPumpCompressorFrequency != parsedState.compressorFrequency) {
        logger("trace", "parseState", "setting heatPumpCompressorFrequency to ${parsedState.compressorFrequency}")
        state.heatPumpCompressorFrequency = parsedState.compressorFrequency 
    }

    checkRemoteTemperatureForStaleness()
}

def mqttClientStatus(status) {
    logger("debug", "mqttClientStatus", "status: ${status}")
}

def publishMqtt(topic, payload, qos = 0, retained = false) {
    if (notMqttConnected()) {
        connect()
    }
    
    def pubTopic = "${getTopicPrefix()}${topic}"

    try {
        interfaces.mqtt.publish("${pubTopic}", payload, qos, retained)
        logger("trace", "publishMqtt", "topic: ${pubTopic} payload: ${payload}")
        
    } catch (Exception e) {
        logger("error", "publishMqtt", "Unable to publish message: ${e}")
    }
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

def setThermostatMode(String value) {
    if (value == null) {
        logger("error", "setThermostatMode", "setThermostatMode called with null")
    } else if (value != device.currentValue("thermostatMode")) {
        logger("debug", "setThermostatMode", "setThermostatMode to ${value}")
        publishMqtt("mode/set", value)
	}
}
                             
def getParentSetting(setting, type) {
	def inheritedValue = parent?.getInheritedSetting(setting)
	if (inheritedValue != null) {
        def displayValue = type == "password" ? "*******" : inheritedValue;
        logger("trace", "getParentSetting", "Inheriting value ${displayValue} for ${setting} from parent")
        device.updateSetting(setting, [value: inheritedValue, type: type])
	}
}

def getParentSettings() {
	getParentSetting("brokerIp", "string")
	getParentSetting("brokerPort", "string")
	getParentSetting("brokerUser", "string")
	getParentSetting("brokerPassword", "password")
	getParentSetting("mqttTopic", "string")
	getParentSetting("heatPumpFriendlyName", "string")
}

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
