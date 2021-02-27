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

definition(
	name: "Mitsubishi2Mqtt Thermostat Child",
	namespace: "dtherron",
	author: "Dan Herron",
	description: "Configure the MQTT properties to connect to a Mitsubishi heat pump that will appear as a thermostat.",
	category: "Green Living",
	iconUrl: "https://raw.githubusercontent.com/dtherron/Hubitat/main/Apps/mitsubishi2mqttThermostat/mitsubishi2mqttThermostat-logo-small.png",
	iconX2Url: "https://raw.githubusercontent.com/dtherron/Hubitat/main/Apps/mitsubishi2mqttThermostat/mitsubishi2mqttThermostat-logo.png",
	importUrl: "https://raw.githubusercontent.com/dtherron/Hubitat/main/Apps/mitsubishi2mqttThermostat/mitsubishi2mqttThermostat-Child.groovy",
	parent: "dtherron:Mitsubishi2Mqtt Thermostat Manager"
)

preferences {
	page(name: "pageConfig") // Doing it this way eliminates the default app name/mode options.
}

def pageConfig() {
	installed = false

	if (!state.deviceID) {
		installed = true
	}
    
    // Display all options for a new instance of the Mitsubishi2Mqtt Thermostat
	dynamicPage(name: "", title: "", install: true, uninstall: true, refreshInterval:0) {
        
        section("<b>Device configuration</b>") {
			label title: "Local name of new Mitsubishi2Mqtt Thermostat app/device:", required: true
			input (name: "mqttTopic", type: "text", title: "MQTT topic specified in the configuration on the remote arduino", required: true, defaultValue: "mitsubishi2mqtt")
			input (name: "heatPumpFriendlyName", type: "text", title: "Friendly Name of the device specified in the configuration on the remote arduino", required: true)
		}

		section("Remote temperature sensor(s) (average value will be used)"){
			input "remoteTempSensors", "capability.temperatureMeasurement", title: "Remote temperature sensors", multiple: true, required: false
		}

        section("<b>Log Settings</b>") {
			input (name: "logLevel", type: "enum", title: "Live Logging Level: Messages with this level and higher will be logged", options: [[0: 'Disabled'], [1: 'Error'], [2: 'Warning'], [3: 'Info'], [4: 'Debug'], [5: 'Trace']], defaultValue: 3)
			input "logDropLevelTime", "decimal", title: "Drop down to Info Level Minutes", required: true, defaultValue: 5
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
	
	logger("info", "installed", "Installed Running Mitsubishi2Mqtt Thermostat: $app.label")
	
	// Generate a random DeviceID
	state.deviceID = "m2mt" + Math.abs(new Random().nextInt() % 9999) + "_" + (now() % 9999)

    state.lastRemoteTempSensorsValue = "";
    
	//Create Mitsubishi2Mqtt Thermostat device
	def thermostat
	def label = app.getLabel()
	logger("info", "installed", "Creating Mitsubishi2Mqtt Thermostat : ${label} with device id: ${state.deviceID}")
	try {
		//** Should we add isComponent in the properties of the child device to make sure we can't remove the Device, will this make it that we can't change settings in it? 
		thermostat = addChildDevice("dtherron", "Mitsubishi2Mqtt Thermostat Device", state.deviceID, [label: label, name: label, completedSetup: true])
	} catch(e) {
		logger("error", "installed", "Error adding Mitsubishi2Mqtt Thermostat child ${label}: ${e}")	
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
	
    logger("trace", "updated", "Updated Running Mitsubishi2Mqtt Thermostat: $app.label.")
    
	initialize(getThermostat())
}


def uninstalled() {
	logger("info", "uninstalled", "Child Device " + state.deviceID + " removed") // This never shows in the logs, is it because of the way HE deals with the uninstalled method?
	deleteChildDevice(state.deviceID)
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

    logger("info", "initialize", "Initialize Running Mitsubishi2Mqtt Thermostat: $app.label.")

	// Log level was set to a higher level than 3, drop level to 3 in x number of minutes
	if (loggingLevel > 3) {
		logger("debug", "initialize", "Initialize runIn $settings.logDropLevelTime")
		runIn(settings.logDropLevelTime.toInteger() * 60, logsDropLevel)
	}

    logger("info", "initialize", "App logging level set to $loggingLevel")
	logger("info", "initialize", "Initialize LogDropLevelTime: $settings.logDropLevelTime")

    if (remoteTempSensors == null) {
        if (state.lastRemoteTempSensorsValue != "") {
            state.lastRemoteTempSensorsValue = "";
            logger("debug", "initialize", "clearing remote sensors to [${state.lastRemoteTempSensorsValue}]")
            unsubscribe()
            thermostatInstance.clearRemoteTemperature() // Clear any lingering value
        }
    } else if (remoteTempSensors.toString() != state.lastRemoteTempSensorsValue) {
        state.lastRemoteTempSensorsValue = remoteTempSensors.toString();
        logger("trace", "initialize", "remote sensor change found")
        unsubscribe()
        thermostatInstance.clearRemoteTemperature() // Clear any lingering value
    	
        // Remove any sensors chosen that are actually of this device type
        // TODO: figure out why the UI never updates to catch on to this
        if (remoteTempSensors?.removeAll { device -> device.getDeviceNetworkId().startsWith("m2mt") }) {
            logger("warn", "initialize", "Some remote sensors were ignored because they seem to be Mitsubishi2Mqtt child devices")
        }

     	// Subscribe to the new sensor(s) and device
        if (remoteTempSensors != null && remoteTempSensors.size() > 0) {
            logger("info", "initialize", "Initializing ${remoteTempSensors.size()} remote sensor(s)")

            // Get all events from the remote sensors. This way even if the temp is constant
            // we have a better signal that the sensors are still online. The device client 
            // will eventually revert to using the device temp if it stops getting updates.
        	subscribe(remoteTempSensors, remoteTemperatureHandler, ["filterEvents": false])

            // Update the temperature with these new sensors
	        updateRemoteTemperature(thermostatInstance)
        }
    }
        
	// Set device settings if this is a new device
	thermostatInstance.setLogLevel(loggingLevel)
    thermostatInstance.updated()
}


//************************************************************
// getThermostat
//     Gets current childDeviceWrapper from list of childs
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
	
	// Does this instance have a DeviceID
	if (!state.deviceID){
		//No DeviceID available what is going on, has the device been removed?
		logger("error", "getThermostat", "getThermostat cannot access deviceID!")
	} else {
		//We have a deviceID, continue and return ChildDeviceWrapper
        logger("trace", "getThermostat", "getThermostat for device ${state.deviceID}")
		def child = getChildDevices().find {
			d -> d.deviceNetworkId.startsWith(state.deviceID)
		}
		logger("trace", "getThermostat", "getThermostat child is ${child}")
		return child
	}
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
def remoteTemperatureHandler(evt)
{
	logger("trace", "remoteTemperatureHandler", "Got event: ${evt.type} , ${evt.name}, ${evt.value}")
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
	def total = 0;
	def count = 0;
	
	// Average across all sensors, but ignore any not reporting as present
    logger("trace", "updateRemoteTemperature", "Checking ${remoteTempSensors.size()} for presence to update remote temp")
	for(sensor in remoteTempSensors) {
        if (sensor.currentValue("presence") == "present") {
		    total += sensor.currentValue("temperature")
		    count++;
        }
	}
	
    // Only send an update if we have data
    logger("trace", "updateRemoteTemperature", "Found ${count} valid remote temperatures")
    if (count > 0) {
        logger("trace", "updateRemoteTemperature", "Setting remote temp to ${total / count}")
        thermostatInstance.setRemoteTemperature(total / count)
    }
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
	switch(level) {
		case "error":
			if (loggingLevel >= 1) log.error "[${source}] ${msg}"
			break

		case "warn":
			if (loggingLevel >= 2) log.warn "[${source}] ${msg}"
			break

		case "info":
			if (loggingLevel >= 3) log.info "[${source}] ${msg}"
			break

		case "debug":
			if (loggingLevel >= 4) log.debug "[${source}] ${msg}"
			break

		case "trace":
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
	def thermostat=getThermostat()
	
	app.updateSetting("logLevel",[type:"enum", value:"3"])
	thermostat.setLogLevel(3)
	
	loggingLevel = app.getSetting('logLevel').toInteger()
	logger("info", "logsDropLevel", "App logging level set to $loggingLevel")
}

def getInheritedSetting(setting) {
    return settings?."${setting}" == null ? parent.getInheritedSetting(setting) : settings."${setting}"
}
