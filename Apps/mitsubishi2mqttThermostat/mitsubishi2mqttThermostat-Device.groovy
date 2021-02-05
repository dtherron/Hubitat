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

def auto() { setThermostatMode("auto") }
def cool() { setThermostatMode("cool") }
def heat() { setThermostatMode("heat") }
def off() { setThermostatMode("off") }
def setThermostatMode(String value) {
	if (value != device.currentValue("thermostatMode")) {
        logger("debug", "setThermostatMode to ${value}")
        publishMqtt("mode/set", value)
	} else {
		logger("trace", "setThermostatMode($value) - already set")
	}
}
                             
def setCoolingSetpoint(value) {
    logger("trace", "setHeatingSetpoint")
    publishMqtt("temp/set", "${value}")
}
def setHeatingSetpoint(value) {
    logger("trace", "setHeatingSetpoint")
    publishMqtt("temp/set", "${value}")
}
                                           
def fanCirculate() { logger("trace", "fanCirculate not available on this device") }
def fanOn() { logger("trace", "fanOn not available on this device") }
def fanAuto() { logger("trace", "fanAuto not available on this device") }
def emergencyHeat() { logger("trace", "emergencyHeat not available on this device") }
def setSchedule(schedule) { logger("trace", "setSchedule not available on this device") } 
def setThermostatFanMode(fanmode) { logger("trace", "setThermostatFanMode not available on this device") }
                                           
//************************************************************
//************************************************************
def installed() {
    logger("info", "Device installed. Initializing to defaults.")

    sendEvent(name: "supportedThermostatFanModes", value: ["auto"], isStateChange: true)
	sendEvent(name: "supportedThermostatModes", value: ["off", "auto", "heat", "cool"] , isStateChange: true)
	sendEvent(name: "thermostatMode", value: "off", isStateChange: true)
	sendEvent(name: "thermostatFanMode", value: "auto", isStateChange: true)
	sendEvent(name: "thermostatOperatingState", value: "idle", isStateChange: true)
	sendEvent(name: "thermostatSetpoint", value: 68, isStateChange: true)
	sendEvent(name: "heatingSetpoint", value: 68, isStateChange: true)
	sendEvent(name: "coolingSetpoint", value: 68, isStateChange: true)
    sendEvent(name: "temperature", value: 68, isStateChange: true)
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

def setRemoteTemperature(value) {
    logger("debug", "set remote_temp to ${value}")

    publishMqtt("remote_temp/set", "${value}")
}

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
    
    logger("debug", "[parse] Received MQTT message type ${messageType} on topic ${topic} from FN ${friendlyName} with value ${message.payload}")

    def slurper = new groovy.json.JsonSlurper()
    def result = slurper.parseText(message.payload)
    
    if (messageType == "settings") {
        parseSettings(result)
    } else if (messageType == "state") {
        parseState(result)
    }
}

def parseSettings(parsedSettings) {
    if (parsedSettings?.mode != null) {
        logger("trace", "[parse] setting thermostatMode to ${parsedSettings.mode}")
        sendEvent(name: "thermostatMode", value: parsedSettings.mode) 
        updateDataValue("lastRunningMode", parsedSettings.mode)	
    }

    if (parsedSettings?.temperature != null) {
        logger("trace", "[parse] setting thermostatSetpoint to ${parsedSettings.temperature}")
        sendEvent(name: "thermostatSetpoint", value: parsedSettings.temperature) 
        if (device.currentValue("thermostatMode") == "heat") {
            logger("trace", "[parse] setting heatingSetpoint to ${parsedSettings.temperature}")
            sendEvent(name: "heatingSetpoint", value: parsedSettings.temperature) 
        } else if (device.currentValue("thermostatMode") == "cool") {
            logger("trace", "[parse] setting coolingSetpoint to ${parsedSettings.temperature}")
            sendEvent(name: "coolingSetpoint", value: parsedSettings.temperature) 
        }
    }

    if (parsedSettings?.fan != null) {
        logger("trace", "[parse] setting fan to ${parsedSettings.fan}")
        sendEvent(name: "thermostatFanMode", value: parsedSettings.fan) 
    }

    if (parsedSettings?.vane != null) {
        logger("trace", "[parse] setting heatPumpVane to ${parsedSettings.vane}")
        sendEvent(name: "heatPumpVane", value: parsedSettings.vane) 
    }

    if (parsedSettings?.wideVane != null) {
        logger("trace", "[parse] setting heatPumpWideVane to ${parsedSettings.wideVane}")
        sendEvent(name: "heatPumpWideVane", value: parsedSettings.wideVane) 
    }
}

def parseState(parsedState) {
    if (parsedState?.mode != null) {
        logger("trace", "[parse] setting thermostatMode to ${parsedState.mode}")
        sendEvent(name: "thermostatMode", value: parsedState.mode) 
        updateDataValue("lastRunningMode", parsedState.mode)	
    }

    if (parsedState?.action != null) {
        logger("trace", "[parse] setting thermostatOperatingMode to ${parsedState.action}")
        sendEvent(name: "thermostatOperatingMode", value: parsedState.action) 
    }

    if (parsedState?.fan != null) {
        logger("trace", "[parse] setting thermostatFanMode to ${parsedState.fan}")
        sendEvent(name: "thermostatFanMode", value: parsedState.fan) 
    }

    if (parsedState?.vane != null) {
        logger("trace", "[parse] setting heatPumpVane to ${parsedState.vane}")
        sendEvent(name: "heatPumpVane", value: parsedState.vane) 
    }

    if (parsedSettings?.wideVane != null) {
        logger("trace", "[parse] setting heatPumpWideVane to ${parsedSettings.wideVane}")
        sendEvent(name: "heatPumpWideVane", value: parsedSettings.wideVane) 
    }
    
    if (parsedState?.compressorFrequency != null) {
        logger("trace", "[parse] setting heatPumpCompressorFrequency to ${parsedState.compressorFrequency}")
        sendEvent(name: "heatPumpCompressorFrequency", value: parsedState.compressorFrequency) 
    }

    if (parsedState?.temperature != null) {
        logger("trace", "[parse] setting thermostatSetpoint to ${parsedState.temperature}")
        sendEvent(name: "thermostatSetpoint", value: parsedState.temperature) 
        if (device.currentValue("thermostatMode") == "heat") {
            logger("trace", "[parse] setting heatingSetpoint to ${parsedState.temperature}")
            sendEvent(name: "heatingSetpoint", value: parsedState.temperature) 
        } else if (device.currentValue("thermostatMode") == "cool") {
            logger("trace", "[parse] setting coolingSetpoint to ${parsedState.temperature}")
            sendEvent(name: "coolingSetpoint", value: parsedState.temperature) 
        }
    }

    if (parsedState?.roomTemperature != null) {
        logger("trace", "[parse] setting temperature to ${parsedState.roomTemperature}")
        sendEvent(name: "temperature", value: parsedState.roomTemperature) 
    }
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

def getParentSetting(setting, type) {
	def inheritedValue = parent?.getInheritedSetting(setting)
	if (inheritedValue != null) {
        logger("trace", "Inheriting value ${inheritedValue} for ${setting} from parent")
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
