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
	name: "Mitsubishi2Mqtt Thermostat Manager",
	namespace: "dtherron",
	author: "Dan Herron",
	description: "Add Mitsubishi heat pumps communicating via MQTT as thermostats. See https://github.com/gysmo38/mitsubishi2MQTT for communicating with the heat pump.",
	category: "Green Living",
	iconUrl: "https://raw.githubusercontent.com/dtherron/Hubitat/main/Apps/mitsubishi2mqttThermostat/mitsubishi2mqttThermostat-logo-small.png",
	iconX2Url: "https://raw.githubusercontent.com/dtherron/Hubitat/main/Apps/mitsubishi2mqttThermostat/mitsubishi2mqttThermostat-logo.png",
	importUrl: "https://raw.githubusercontent.com/dtherron/Hubitat/main/Apps/mitsubishi2mqttThermostat/mitsubishi2mqttThermostat-Parent.groovy",
	singleInstance: true
)

preferences {
	page(name: "Install", title: "Mitsubishi2Mqtt Thermostat Manager", install: true, uninstall: true) {
		section("Devices") {
		}
		section {
			app(name: "thermostats", appName: "Mitsubishi2Mqtt Thermostat Child", namespace: "dtherron", title: "Add Mitsubishi2Mqtt Thermostat", multiple: true)
		}
	}
}

def installed() {
	log.debug "Installed"
	initialize()
}

def updated() {
	log.debug "Updated"
	unsubscribe()
	initialize()
}

def initialize() {
	log.debug "Initializing; there are ${childApps.size()} child apps installed"
	childApps.each {child -> 
		log.debug "  child app: ${child.label}"
	}
}
