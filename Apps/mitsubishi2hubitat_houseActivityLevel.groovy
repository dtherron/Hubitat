/*
 *  Mitsubishi2Hubitat Thermostat House Activity Level Child App
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
    name: 'Mitsubishi2Hubitat Thermostat House Activity Level',
    namespace: 'dtherron',
    author: 'Dan Herron',
    description: 'Use presence sensors to determine whether to keep a house warm.',
    category: '',
    iconUrl: '',
    iconX2Url: '',
    importUrl: 'https://raw.githubusercontent.com/dtherron/Hubitat/refs/heads/main/Apps/mitsubishi2hubitat_houseActivityLevel.groovy',
    parent: 'dtherron:Mitsubishi2Hubitat Thermostat Manager'
)

preferences {
    page(name: 'Install', title: 'Mitsubishi2Hubitat House Activity Level', install: true, uninstall: true) {
        section('<b>Home/away detection</b>') {
            input 'motionSensors', 'capability.motionSensor', title: 'Motion sensors (to detect somebody is home)', multiple: true, required: true
         }

        section('<b>Log Settings</b>') {
            input (name: 'logLevel', type: 'enum', title: 'Live Logging Level: Messages with this level and higher will be logged', options: [[0: 'Disabled'], [1: 'Error'], [2: 'Warning'], [3: 'Info'], [4: 'Debug'], [5: 'Trace']], defaultValue: 3)
            input 'logDropLevelTime', 'number', title: 'Drop down to Info Level Minutes', required: true, defaultValue: 5
        }
    }
}

def installed() {
    logger('info', 'installed', "Installed: $app.label")

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

    logger('debug', 'updated', "Updated: $app.label.")
    initialize()
}

def uninstalled() {
    logger('info', 'uninstalled', "done")
}

def setHomeActivityLevel(level) {
    sendEvent(name: "homeActivityLevel", value: level)
}

def initialize() {
    logger('info', 'initialize', "Initializing")

    state.awayStartTime = location.currentMode.getName() == 'Away' ? now() : null
    
    // reset the motion sensor state data
    state.motionSensorState = [:]

	unsubscribe()

    subscribe(location, 'mode', locationModeChanged)

    // Subscribe to the motion sensor(s) if away
    if (location.currentMode.getName() == 'Away') {
        if (motionSensors != null && motionSensors.size() > 0) {
            logger('trace', 'subscribeToMotionSensors', "Initializing ${motionSensors.size()} motion sensor(s) and scheduling presence detection")
            subscribe(motionSensors, 'motion.active', motionActiveHandler)
            subscribe(motionSensors, 'motion.inactive', motionActiveHandler)
            runEvery1Minute(getRollingActivityLevel)
        }
    } else {
        logger('trace', 'subscribeToMotionSensors', "Clearing subscriptions and schedules for presence detection")
        unsubscribe(motionActiveHandler)
        unschedule(getRollingActivityLevel)
        setHomeActivityLevel(0)
    }
}

def locationModeChanged(evt) {
    subscribeToMotionSensors()

    if (location.currentMode.getName() == 'Away') {
        state.awayStartTime = now()
        def delayMinutes = 15
        logger('trace', 'locationModeChanged', "Mode changed to away; prevent away for $delayMinutes minutes to avoid bouncing")
        updateAwayModeDisabledUntil(delayMinutes)
    } else {
        logger('trace', 'locationModeChanged', "Mode no longer set to away")
        state.remove("awayStartTime")
        state.disableAwayModeUntil = now();
        state.motionSensorState = [:]
        childApps.each { child ->
            logger('debug', 'locationModeChanged', "Calling scheduledUpdateCheck for child app: ${child.label}")
            child.scheduledUpdateCheck()
        }
    }
}

def subscribeToMotionSensors() {
    // Subscribe to the motion sensor(s) if away
    if (location.currentMode.getName() == 'Away') {
        if (motionSensors != null && motionSensors.size() > 0) {
            logger('trace', 'subscribeToMotionSensors', "Initializing ${motionSensors.size()} motion sensor(s) and scheduling presence detection")
            subscribe(motionSensors, 'motion.active', motionActiveHandler)
            subscribe(motionSensors, 'motion.inactive', motionActiveHandler)
            runEvery1Minute(getRollingActivityLevel)
        }
    } else {
        logger('trace', 'subscribeToMotionSensors', "Clearing subscriptions and schedules for presence detection")
        unsubscribe(motionActiveHandler)
        unschedule(getRollingActivityLevel)
        setHomeActivityLevel(0)
    }
}

def motionActiveHandler(evt) {
    if (location.currentMode.getName() != 'Away') {
        logger('trace', 'motionActiveHandler', "House is not set to Away, so ignoring")
        return
    }
    
    def devicePresent = evt.getDevice().currentValue('presence') == 'present'
    def deviceId = evt.getDeviceId().toString()
    def isActive = evt.value == 'active' && devicePresent
    if (evt.value != 'active' && evt.value != 'inactive') {
        logger('warn', 'motionActiveHandler', "Unexpected event ${evt.value} ignored")
        return
    }

    def timeNow = now()
    def sensorHistories = state.motionSensorState

    logger('trace', 'motionActiveHandler', "Sensor $deviceId is ${evt.value} at $timeNow")

    def sensorHistory = sensorHistories[deviceId]
    if (sensorHistory == null) {
        logger('debug', 'motionActiveHandler', "Initialize history to empty")
        sensorHistory = []
    } else {
        // Remove anything more than 30 minutes old
        sensorHistory.removeAll { entry -> entry[1] != null && entry[1] < (timeNow - 1800000) }
    }
    
    if (isActive) {
        if (sensorHistory.removeAll { entry -> entry[1] == null } ) {
            logger('warn', 'motionActiveHandler', "Sensor $deviceId had an active event without matching inactive, which was removed")
        }

        sensorHistory << [timeNow, null]
    } else if (sensorHistory.size() > 0) {
        if (sensorHistory[-1][1] != null) {
            logger('warn', 'motionActiveHandler', "Inactive time is already set for most recent activity on sensor $deviceId at $timeNow ($sensorHistory)")
        }
        sensorHistory[-1] = [sensorHistory[-1][0], timeNow]
    }

    sensorHistories[deviceId] = sensorHistory
    logger('trace', 'motionActiveHandler', "Update $deviceId for ${evt.value} at $timeNow ($sensorHistory)")
}

def getRollingActivityLevel() {
    def timeNow = now()
    def windowSizeInMinutes = 30
    def windowStartTime = timeNow - (1000 * windowSizeInMinutes * 60)
    def timeActive = 0

    def sensorHistories = state.motionSensorState
    sensorHistories.each { sensorHistoryKV ->
        def sensorTimeActive = 0
        def sensorHistory = sensorHistoryKV.value

        sensorHistory.each { entry ->
            def startTime = Math.max(windowStartTime, entry[0])
            def endTime = entry[1] ? entry[1] : timeNow 
            if (endTime >= windowStartTime) {
                sensorTimeActive += (int) ((endTime - startTime) / 1000)
            }
        }
        
        logger('trace', 'getRollingActivityLevel', "Sensor ${sensorHistoryKV.key} level is $sensorTimeActive")
    
        timeActive += sensorTimeActive
    }
    
    // Each sensor (if fully active in the period) can take us to 50% on its own. Cap at 100%.
    def percentActive = Math.min(100, (int) (100 * timeActive / (60 * windowSizeInMinutes)))
    
    logger('debug', 'getRollingActivityLevel', "Activity level is $timeActive or $percentActive")
    setHomeActivityLevel(percentActive)
    
    if (percentActive >= 32) {
        updateAwayModeDisabledUntil(120)
        logger('trace', 'getRollingActivityLevel', "Very high activity in house; prevent away for at least two hours")
    } else if (percentActive >= 16) {
        updateAwayModeDisabledUntil(60)
        logger('trace', 'getRollingActivityLevel', "High activity in house; prevent away for at least one hour")
    } else if (percentActive >= 8) {
        updateAwayModeDisabledUntil(30)
        logger('trace', 'getRollingActivityLevel', "Moderate activity in house; prevent away for at least 30 minutes")
    } else if (percentActive >= 4) {
        updateAwayModeDisabledUntil(10)
        logger('trace', 'getRollingActivityLevel', "Low activity in house; prevent away for at least 10 minutes")
    }
}

def updateAwayModeDisabledUntil(minutes) {
    def newTimeRequested = now() + (60000 * minutes)
    state.disableAwayModeUntil = Math.max(state.disableAwayModeUntil, newTimeRequested)
    logger('debug', 'updateAwayModeDisabledUntil', "Preventing away mode until ${new Date(state.disableAwayModeUntil).format('HH:mm:ss')}")
}

def getTimeSinceAway() {
    if (state.awayStartTime == null) {
        return 0;
    }
 
    long msPerCycle = 1000*60*60; // Every 60 minutes is a cycle
    return (int) (((now() - state.awayStartTime) / msPerCycle))
}

def allowAwayMode() { 
    // when there was recent activity
    if (state.disableAwayModeUntil > now()) {
        logger('debug', 'allowAwayMode', "Recent activity detected; prevent away mode")
        return false
    }
    
    logger('debug', 'allowAwayMode', "No recent activity; allow away mode")
    return true
}