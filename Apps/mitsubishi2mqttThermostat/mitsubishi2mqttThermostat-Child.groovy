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
	// Let's just set a few things before starting
	def displayUnits = getDisplayUnits()
	def hubScale = getTemperatureScale()
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

        /*
		// If this is the first time we install this driver, show initial settings
		if (!state.deviceID) {
			section("Initial Thermostat Settings... (invalid values will be set to the closest valid value)"){
				input "heatingSetPoint", "decimal", title: "Heating Setpoint in $displayUnits, this should be at least $setpointDistance $displayUnits lower than cooling", required: true, defaultValue: heatingSetPoint
			}
		}
        */
	
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
	
	logger("trace", "Installed Running Mitsubishi2Mqtt Thermostat: $app.label")
	
	// Generate a random DeviceID
	state.deviceID = "m2mt" + Math.abs(new Random().nextInt() % 9999) + now

	//Create Mitsubishi2Mqtt Thermostat device
	def thermostat
	def label = app.getLabel()
	logger("info", "Creating Mitsubishi2Mqtt Thermostat : ${label} with device id: ${state.deviceID}")
	try {
		//** Should we add isComponent in the properties of the child device to make sure we can't remove the Device, will this make it that we can't change settings in it? 
		thermostat = addChildDevice("dtherron", "Mitsubishi2Mqtt Thermostat Device", state.deviceID, [label: label, name: label, completedSetup: true]) //** This will only work with ver 2.1.9 and up
	} catch(e) {
		logger("error", "Error adding Mitsubishi2Mqtt Thermostat child ${label}: ${e}") //*** Not 100% sure about this one, test message outside loop to be sure ***
		//*** Original code: log.error("Could not create Mitsubishi2Mqtt Thermostat; caught exception", e)
	}
	initialize(thermostat)
}


def updated() {
	// Set log level to new value
	int loggingLevel
	if (settings.logLevel) {
		loggingLevel = settings.logLevel.toInteger()
	} else {
		loggingLevel = 3
	}
	
	logger("trace", "Updated Running Mitsubishi2Mqtt Thermostat: $app.label")

	initialize(getThermostat())
}


def uninstalled() {
	logger("info", "Child Device " + state.deviceID + " removed") // This never shows in the logs, is it because of the way HE deals with the uninstalled method?
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
	logger("trace", "Initialize Running Mitsubishi2Mqtt Thermostat: $app.label")

	// Recheck Log level in case it was changed in the child app
	if (settings.logLevel) {
		loggingLevel = settings.logLevel.toInteger()
	} else {
		loggingLevel = 3
	}
	
	// Log level was set to a higher level than 3, drop level to 3 in x number of minutes
	if (loggingLevel > 3) {
		logger("trace", "Initialize runIn $settings.logDropLevelTime")
		runIn(settings.logDropLevelTime.toInteger() * 60, logsDropLevel)
	}

	logger("warn", "App logging level set to $loggingLevel")
	logger("trace", "Initialize LogDropLevelTime: $settings.logDropLevelTime")
	
	// Set device settings if this is a new device
	thermostatInstance.setLogLevel(loggingLevel)
	//thermostatInstance.setThermostatMode(thermostatMode)
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
		logger("error", "getThermostat cannot access deviceID!")
	} else {
		
		//We have a deviceID, continue and return ChildDeviceWrapper
		logger("trace", "getThermostat for device " + state.deviceID)
		def child = getChildDevices().find {
			d -> d.deviceNetworkId.startsWith(state.deviceID)
		}
		logger("trace","getThermostat child is ${child}")
		return child
	}
}


//************************************************************
// logger
//     Wrapper function for all logging with level control via preferences
//
// Signature(s)
//     logger(String level, String msg)
//
// Parameters
//     level : Error level string
//     msg : Message to log
//
// Returns
//     None
//
//************************************************************
def logger(level, msg) {

	switch(level) {
		case "error":
			if (loggingLevel >= 1) log.error msg
			break

		case "warn":
			if (loggingLevel >= 2) log.warn msg
			break

		case "info":
			if (loggingLevel >= 3) log.info msg
			break

		case "debug":
			if (loggingLevel >= 4) log.debug msg
			break

		case "trace":
			if (loggingLevel >= 5) log.trace msg
			break

		default:
			log.debug msg
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
	logger("warn","App logging level set to $loggingLevel")
}


//************************************************************
// getTemperatureScale
//     Get the hubs temperature scale setting and return the result
// Signature(s)
//     getTemperatureScale()
// Parameters
//     None
// Returns
//     Temperature scale
//************************************************************
def getTemperatureScale() {
	return "${location.temperatureScale}"
}


//************************************************************
// getDisplayUnits
//     Get the diplay units
// Signature(s)
//     getDisplayUnits()
// Parameters
//     None
// Returns
//     Formated Units String
//************************************************************
def getDisplayUnits() {
	if (getTemperatureScale() == "C") {
		return "°C"
	} else {
		return "°F"
	}
}

def getInheritedSetting(setting) {
    return settings?."${setting}" == null ? parent.getInheritedSetting(setting) : settings."${setting}"
}
