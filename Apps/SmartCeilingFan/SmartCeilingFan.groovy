definition(
    name: 'Smart Ceiling Fan',
    namespace: 'dtherron',
    author: 'Dan Herron',
    description: 'Ceiling fan that adjusts with time and temperature.',
    category: 'Green Living',
    iconUrl: 'TODO',
    iconX2Url: 'TODO'
)

preferences {
    page(name: 'Install', title: 'Smart Ceiling Fan', install: true, uninstall: true) {
        section('<b>Config</b>') {
            input(name: 'dayLowFanTemp', type: 'number',  title: 'Temp to turn fan on at during the day', required: true, defaultValue: 68)
            input(name: 'dayHighFanTemp', type: 'number',  title: 'Temp to set fan to high at during the day', required: true, defaultValue: 78)
            input(name: 'nightLowFanTemp', type: 'number',  title: 'Temp to turn fan on at during the night', required: true, defaultValue: 60)
            input(name: 'nightHighFanTemp', type: 'number',  title: 'Temp to set fan to high at during the night', required: true, defaultValue: 75)
        }
        
        section('<b>Fan to control</b>') {
            input 'ceilingFan', 'capability.fanControl', title: 'Ceiling fan to control', multiple: false, required: true
        }

        section('<b>Temperature sensors</b>') {
            input 'remoteTempSensors', 'capability.temperatureMeasurement', title: 'Remote temperature sensors', multiple: true, required: true
        }
        
        section('<b>Log Settings</b>') {
            input (name: 'logLevel', type: 'enum', title: 'Live Logging Level: Messages with this level and higher will be logged', options: [[0: 'Disabled'], [1: 'Error'], [2: 'Warning'], [3: 'Info'], [4: 'Debug'], [5: 'Trace']], defaultValue: 3)
            input 'logDropLevelTime', 'number', title: 'Drop down to Info Level Minutes', required: true, defaultValue: 5
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
    logger('info', 'uninstalled', 'Removed')
}

def initialize() {
    int loggingLevel
    if (settings.logLevel) {
        loggingLevel = settings.logLevel.toInteger()
    } else {
        loggingLevel = 3
    }

    logger('info', 'initialize', "Initialize: $app.label.")

    // Log level was set to a higher level than 3, drop level to 3 in x number of minutes
    if (loggingLevel > 3) {
        logger('debug', 'initialize', "Revert log level to default in $settings.logDropLevelTime minutes")
        runIn(settings.logDropLevelTime.toInteger() * 60, logsDropLevel)
    }

    logger('info', 'initialize', "App logging level set to $loggingLevel")
    logger('info', 'initialize', "Initialize LogDropLevelTime: $settings.logDropLevelTime")

    logger('info', 'initialize', "Initializing ${remoteTempSensors.size()} remote sensor(s)")
        
    subscribe(remoteTempSensors, 'temperature', remoteTemperatureHandler)
    subscribe(location, "mode", locationModeChanged)

    updateRemoteTemperature()
}

def locationModeChanged(evt) {
    def newMode = evt.value
    logger('trace', 'locationModeChanged', "Got mode change event to $newMode")
    updateRemoteTemperature(true)
}

def remoteTemperatureHandler(evt) {
    logger('trace', 'remoteTemperatureHandler', "Got event: ${evt.name}, ${evt.value}")
    updateRemoteTemperature()
}

def updateRemoteTemperature(forceChange = false) {
    def currentHour = new Date(now()).format('H').toInteger()
    def isNight = location.currentMode.getName() == "Night" || currentHour >= 22 || currentHour <= 5
    def lowTemp = isNight ? settings.nightLowFanTemp : settings.dayLowFanTemp
    def highTemp = isNight ? settings.nightHighFanTemp : settings.dayHighFanTemp

    def total = 0
    def count = 0
    // Average across all sensors, but ignore any not reporting as present
    logger('trace', 'updateRemoteTemperature', "Checking ${remoteTempSensors.size()} for presence to update remote temp; hour is $currentHour and isNight is $isNight")
    for (sensor in remoteTempSensors) {
        if (sensor.currentValue('presence') == 'present') {
            total += sensor.currentValue('temperature')
            count++
        }
    }

    logger('trace', 'updateRemoteTemperature', "Found ${count} valid remote temperatures")
    if (count == 0) {
        logger('warn', 'updateRemoteTemperature', "No valid temperature readings found")
        return
    }

    def roomTemp = null
    def fanSpeed = 0
    
    roomTemp = Math.round(total / count)
    logger('trace', 'updateRemoteTemperature', "Room temp is $roomTemp")
    if (roomTemp < lowTemp) {
        fanSpeed = 0
    } else {
        def fanRange = highTemp - lowTemp
        fanSpeed = Math.round(6 * (roomTemp - lowTemp) / fanRange)
        fanSpeed = Math.max(1, fanSpeed)
        fanSpeed = Math.min(6, fanSpeed)
    }
    
    setFanSpeed(fanSpeed, forceChange)
}

def setFanSpeed(fanSpeed, forceChange = false) {
    logger('trace', 'setFanSpeed', "Requested with $fanSpeed")
    def fanSpeedName = speedToSpeedName(fanSpeed)
    if (forceChange || ceilingFan.currentValue("speed") != fanSpeedName) {
        logger('info', 'setFanSpeed', "Setting fan to $fanSpeedName")
        ceilingFan.setSpeed(fanSpeedName)
    }
}

def speedToSpeedName(speed) {
    switch(speed) {
        case 0: return "off"
        case 1: return "low"
        case 2: return "medium-low"
        case 3: return "low-medium"
        case 4: return "high-medium"
        case 5: return "medium-high"
        case 6: return "high"
    }
}

def logger(level, source) {
    logger(level, source, '')
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
    switch (level) {
        case 'error':
            if (loggingLevel >= 1) log.error "[${source}] ${msg}"
            break

        case 'warn':
            if (loggingLevel >= 2) log.warn "[${source}] ${msg}"
            break

        case 'info':
            if (loggingLevel >= 3) log.info "[${source}] ${msg}"
            break

        case 'debug':
            if (loggingLevel >= 4) log.debug "[${source}] ${msg}"
            break

        case 'trace':
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
    app.updateSetting('logLevel', [type:'enum', value:'3'])

    loggingLevel = app.getSetting('logLevel').toInteger()
    logger('info', 'logsDropLevel', "App logging level set to $loggingLevel")
}
