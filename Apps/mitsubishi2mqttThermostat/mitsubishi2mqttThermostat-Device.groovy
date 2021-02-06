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

        command "setFanSpeed", ["string"]
        command "setHeatPumpVane", ["string"]
        
        // TODO make this enums?
        attribute "heatPumpVane", "string"
        attribute "heatPumpWideVane", "string"

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
    logger("trace", "command auto called")
    setThermostatMode("auto") 
}

def cool() {
    logger("trace", "command cool called")
    setThermostatMode("cool") 
}

def heat() {
    logger("trace", "command heat called")
    setThermostatMode("heat") 
}

def off() {
    logger("trace", "command off called")
    setThermostatMode("off") 
}

def setCoolingSetpoint(value) {
    logger("trace", "command setHeatingSetpoint called: ${value}")
    publishMqtt("temp/set", "${value}")
}

def setHeatingSetpoint(value) {
    logger("trace", "command setHeatingSetpoint called: ${value}")
    publishMqtt("temp/set", "${value}")
}

def setFanSpeed(value) {
    logger("trace", "command setFanSpeed called: ${value}")
    publishMqtt("fan/set", "${value}")
}

def setHeatPumpVane(value) {
    logger("trace", "command setHeatPumpVane called: ${value}")
    publishMqtt("vane/set", "${value}")
}

def fanCirculate() { logger("warn", "command fanCirculate not available on this device") }
def fanOn() { logger("warn", "command fanOn not available on this device") }
def fanAuto() { logger("warn", "command fanAuto not available on this device") }
def emergencyHeat() { logger("warn", "command emergencyHeat not available on this device") }
def setSchedule(schedule) { logger("warn", "setSchedule not available on this device") } 
def setThermostatFanMode(fanmode) { logger("warn", "setThermostatFanMode not available on this device") }
                                           
//************************************************************
//************************************************************
def installed() {
    logger("info", "Device installed. Initializing to defaults.")

    sendEvent(name: "supportedThermostatFanModes", value: ["AUTO", "QUIET", "1", "2", "3", "4"], isStateChange: true)
	sendEvent(name: "supportedThermostatModes", value: ["off", "auto", "heat", "cool"] , isStateChange: true)
	sendEvent(name: "thermostatMode", value: "off", isStateChange: true)
	sendEvent(name: "thermostatFanMode", value: "AUTO", isStateChange: true)
	sendEvent(name: "thermostatOperatingState", value: "idle", isStateChange: true)
	sendEvent(name: "thermostatSetpoint", value: 68, isStateChange: true)
	sendEvent(name: "heatingSetpoint", value: 68, isStateChange: true)
	sendEvent(name: "coolingSetpoint", value: 68, isStateChange: true)
    sendEvent(name: "temperature", value: 68, isStateChange: true)
    sendEvent(name: "heatPumpWideVane", value: "", isStateChange: true)
    sendEvent(name: "heatPumpVane", value: "", isStateChange: true)
    
	updateDataValue("lastRunningMode", "off")	

    getParentSettings()
	disconnect()
	connect()
}

//************************************************************
//************************************************************
def updated() {
    logger("info", "Device settings updated.")
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
        logger("debug", "resetting remote temp to 0")
        publishMqtt("remote_temp/set", "0")
        state.lastRemoteTemperatureTime = null
        state.lastRemoteTemperature = null
    }
}

def setRemoteTemperature(value) {
    if (value != state.lastRemoteTemperature) {
        state.lastRemoteTemperature = value
        state.lastRemoteTemperatureTime = now()
        logger("trace", "set remote_temp to ${value}")
        publishMqtt("remote_temp/set", "${value}")
    }
}

def checkRemoteTemperatureForStaleness() {
    if (state.lastRemoteTemperatureTime != null && (state.lastRemoteTemperatureTime < (now() - 3600000))) {
        logger("warn", "resetting remote temp to 0 due to no data from sensors in over an hour")
        clearRemoteTemperature()
    }
}

// ========================================================
// MQTT COMMANDS
// ========================================================

def subscribe(topic) {
    if (notMqttConnected()) {
        connect()
    }

    logger("debug","[subscribe] full topic: ${getTopicPrefix()}${topic}")
    interfaces.mqtt.subscribe("${getTopicPrefix()}${topic}")
}

def unsubscribe(topic) {
    if (notMqttConnected()) {
        connect()
    }
    
    logger("debug","[unsubscribe] full topic: ${getTopicPrefix()}${topic}")
    interfaces.mqtt.unsubscribe("${getTopicPrefix()}${topic}")
}

def connect() {
    logger("info", "Connecting to MQTT broker as client ${getClientId()}")
    
    try {   
        interfaces.mqtt.connect(getBrokerUri(),
                           getClientId(), 
                           settings?.brokerUser, 
                           settings?.brokerPassword)
       
        // delay for connection
        pauseExecution(1000)        
    } catch(Exception e) {
        logger("error", "Connecting MQTT failed: ${e}")
        return
    }

    subscribe("state")
    subscribe("settings")
	subscribe("debug")
}

def disconnect() {
    logger("info", "Disconnecting from MQTT broker")
    try {
        interfaces.mqtt.disconnect()
    } catch(e) {
        logger("warn", "Disconnection from broker failed: ${e.message}")
    }
}

// ========================================================
// MQTT METHODS
// ========================================================

// Parse incoming message from the MQTT broker
def parse(String event) {
    def message = interfaces.mqtt.parseMessage(event)  
    def (topic, friendlyName, messageType) = message.topic.tokenize( '/' )
    
    logger("trace", "[parse] Received MQTT message type ${messageType} on topic ${topic} from FN ${friendlyName} with value ${message.payload}")

    def slurper = new groovy.json.JsonSlurper()
    def result = slurper.parseText(message.payload)
    
    if (messageType == "settings") {
        parseSettings(result)
    } else if (messageType == "state") {
        parseState(result)
    }
}

def setIfNotNullAndChanged(newValue, attributeName) {
    if (newValue != null && device.currentValue(attributeName) != newValue) {
        logger("debug", "setting ${attributeName} to ${newValue}")
        sendEvent(name: attributeName, value: newValue) 
        return true
    }
    return false
}

def parseSettings(parsedSettings) {
    if (setIfNotNullAndChanged(parsedSettings?.mode, "thermostatMode")) {
        updateDataValue("lastRunningMode", parsedSettings.mode)	
        logger("info", "Mode changed to ${parsedSettings.mode}")
    }

    if (setIfNotNullAndChanged(parsedSettings?.temperature, "thermostatSetpoint")) {
        logger("info", "Set point changed to ${parsedSettings.temperature}")
        if (device.currentValue("thermostatMode") == "heat") {
            logger("trace", "setting heatingSetpoint to ${parsedSettings.temperature}")
            sendEvent(name: "heatingSetpoint", value: parsedSettings.temperature) 
        } else if (device.currentValue("thermostatMode") == "cool") {
            logger("trace", "setting coolingSetpoint to ${parsedSettings.temperature}")
            sendEvent(name: "coolingSetpoint", value: parsedSettings.temperature) 
        }
    }

    setIfNotNullAndChanged(parsedSettings?.fan, "thermostatFanMode")
    setIfNotNullAndChanged(parsedSettings?.vane, "heatPumpVane")
    setIfNotNullAndChanged(parsedSettings?.wideVane, "heatPumpWideVane")
}

def parseState(parsedState) {
    if (setIfNotNullAndChanged(parsedState?.mode, "thermostatMode")) {
        updateDataValue("lastRunningMode", parsedState.mode)	
    }

    if (setIfNotNullAndChanged(parsedState?.temperature, "thermostatSetpoint")) {
        if (device.currentValue("thermostatMode") == "heat") {
            logger("trace", "setting heatingSetpoint to ${parsedState.temperature}")
            sendEvent(name: "heatingSetpoint", value: parsedState.temperature) 
        } else if (device.currentValue("thermostatMode") == "cool") {
            logger("trace", "setting coolingSetpoint to ${parsedState.temperature}")
            sendEvent(name: "coolingSetpoint", value: parsedState.temperature) 
        }
    }

    setIfNotNullAndChanged(parsedState?.action, "thermostatOperatingState")
    setIfNotNullAndChanged(parsedState?.roomTemperature, "temperature")
    setIfNotNullAndChanged(parsedState?.fan, "thermostatFanMode")
    setIfNotNullAndChanged(parsedState?.vane, "heatPumpVane")
    setIfNotNullAndChanged(parsedState?.wideVane, "heatPumpWideVane")
    
    if (parsedState?.compressorFrequency != null && state.heatPumpCompressorFrequency != parsedState?.compressorFrequency) {
        logger("trace", "setting heatPumpCompressorFrequency to ${parsedState.compressorFrequency}")
        state.heatPumpCompressorFrequency = parsedState.compressorFrequency 
    }

    checkRemoteTemperatureForStaleness()
}

def mqttClientStatus(status) {
    logger("debug","[mqttClientStatus] status: ${status}")
}

def publishMqtt(topic, payload, qos = 0, retained = false) {
    if (notMqttConnected()) {
        connect()
    }
    
    def pubTopic = "${getTopicPrefix()}${topic}"

    try {
        interfaces.mqtt.publish("${pubTopic}", payload, qos, retained)
        logger("trace","[publishMqtt] topic: ${pubTopic} payload: ${payload}")
        
    } catch (Exception e) {
        logger("error","[publishMqtt] Unable to publish message: ${e}")
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
//     msg : Message to log
// Returns
//     None
//************************************************************
def logger(level, msg) {

	switch(level) {
		case "error":
			if (state.loggingLevel >= 1) log.error msg
			break

		case "warn":
			if (state.loggingLevel >= 2) log.warn msg
			break

		case "info":
			if (state.loggingLevel >= 3) log.info msg
			break

		case "debug":
			if (state.loggingLevel >= 4) log.debug msg
			break

		case "trace":
			if (state.loggingLevel >= 5) log.trace msg
			break

		default:
			log.debug msg
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
	logger("warn","Device logging level set to $state.loggingLevel")
}

def setThermostatMode(String value) {
	if (value != device.currentValue("thermostatMode")) {
        logger("debug", "setThermostatMode to ${value}")
        publishMqtt("mode/set", value)
	}
}
                             
def getParentSetting(setting, type) {
	def inheritedValue = parent?.getInheritedSetting(setting)
	if (inheritedValue != null) {
        def displayValue = type == "password" ? "*******" : inheritedValue;
        logger("trace", "Inheriting value ${displayValue} for ${setting} from parent")
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
