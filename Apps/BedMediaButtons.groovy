definition(
    name: 'Bed media buttons',
    namespace: 'dtherron',
    author: 'Dan Herron',
    description: 'Adjust media with buttons.',
    category: 'Green Living',
    iconUrl: 'TODO',
    iconX2Url: 'TODO'
)

preferences {
    page(name: 'Install', title: 'Bed media buttons', install: true, uninstall: true) {
        section('<b>Media</b>') {
            input 'mediaDevice', 'device.ChromecastAudio', title: 'Media device to use', multiple: false, required: true
        }
        
        section('<b>Buttons to control</b>') {
            input 'bedLeft', 'device.TuyaZigbeeSceneSwitch', title: 'Buttons on left of bed (facing bed)', multiple: false, required: true
            input 'bedRight', 'device.TuyaZigbeeSceneSwitch', title: 'Buttons on right of bed (facing bed)', multiple: false, required: true
        }

        section('<b>Home assistant</b>') {
                input(
                name: "homeAssistantAddress", 
                type: "string",
                title: "Home Assistant IP Address",
                description: "e.g. 192.168.1.200",
                required: true,
                displayDuringSetup: true
            )
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

    unsubscribe()
    
    if (bedLeft != null) {
        logger('info', 'initialize', "Subscribing to events for left button")
        subscribe(bedLeft, 'doubleTapped', buttonHandlerLeft)
        subscribe(bedLeft, 'released', buttonHandlerLeft)
        subscribe(bedLeft, 'pushed', buttonHandlerLeft)
    }

    if (bedRight != null) {
        logger('info', 'initialize', "Subscribing to events for right button")
        subscribe(bedRight, 'doubleTapped', buttonHandlerRight)
        subscribe(bedRight, 'released', buttonHandlerRight)
        subscribe(bedRight, 'pushed', buttonHandlerRight)
    }

    initializeIfNeeded()
}


/* LEFT 1 2     RIGHT 3 4
        4 3           2 1     */

def buttonHandlerLeft(evt) {
    logger('trace', 'buttonHandlerLeft', "Got event: ${evt.name}, ${evt.value}")
    if (!(evt.name == "pushed" || evt.name == "doubleTapped")) return;
    boolean isDoubleTap = evt.name == "doubleTapped"
    
    switch (evt.value) {
        case "1":
        return startStation(isDoubleTap, 2)
        
        case "2":
        return startStation(isDoubleTap, 1)

        case "3":
        return changevolume(isDoubleTap, "down")

        case "4":
        return changevolume(isDoubleTap, "up")
    }
}

def buttonHandlerRight(evt) {
    logger('trace', 'buttonHandlerRight', "Got event: ${evt.name}, ${evt.value}")
    if (!(evt.name == "pushed" || evt.name == "doubleTapped")) return;
    boolean isDoubleTap = evt.name == "doubleTapped"

    switch (evt.value) {
        case "1":
        return changevolume(isDoubleTap, "up")
        
        case "2":
        return changevolume(isDoubleTap, "down")

        case "3":
        return startStation(isDoubleTap, 2)

        case "4":
        return startStation(isDoubleTap, 1)
    }
}


def changevolume(isDoubleTap, direction) {
    logger('trace', 'changevolume', "Move volume $direction from a push (double == $isDoubleTap)")
    def currentVolume = mediaDevice.currentValue("volume")
    def volumeDelta = isDoubleTap ? 5 : 1
    def newVolume = (int) (direction == "up" ? currentVolume + volumeDelta : currentVolume - volumeDelta) 
    newVolume = Math.max(0, newVolume)
    newVolume = Math.min(100, newVolume)
    
    logger('trace', 'changevolume', "Current volume = $currentVolume; newVolume = $newVolume")
    initializeIfNeeded()
    mediaDevice.setVolume(newVolume)
}

def startStation(isDoubleTap, stationNumber) {
    logger('trace', 'startStation', "Start station $stationNumber from a push (double == $isDoubleTap)")
    def station  = 
        isDoubleTap ? (stationNumber == 1 ? "RAI1" : "RAI2") :
    (stationNumber == 1 ? "BBC" : "KUOW")

    if (state.lastStarted == null || now() - state.lastStarted > 18000000) {
        logger('trace', 'startStation', "First play in 5 hours; setting volume to 10")
        mediaDevice.setVolume(10)
    }
    state.lastStarted = now()

    logger('trace', 'startStation', "Start $station")
    httpPost("https://${homeAssistantAddress}/api/webhook/play_$station")
}

def initializeIfNeeded() {
    if (state.lastInitialized == null || now() - state.lastInitialized > 240000) {
        logger('trace', 'initializeIfNeeded', "re-initializing")
        mediaDevice.initialize();
        state.lastInitialized = now()
    }
}

// Send Web Request and get back response

private Map getHeaders() {
    Map headers = [:]
    headers.put("Host", homeAssistantAddress)
    headers.put("Content-Type", "application/x-www-form-urlencoded")
    return headers
}

private void httpPost(String uri) { 
  Map headers = getHeaders()
  log.info("httpGetAction for '$uri'...")
  try {
    httpPost(uri, "")
{ resp ->
            if (resp.success) {
                sendEvent(name: "switch", value: "on", isStateChange: true)
            }
            if (logEnable)
                if (resp.data) log.debug "${resp.data}"
			if (autooff)
				runIn(delay, off)
        }
  } catch (e) {
    log.error "Error in httpPost(uri): $e ('$uri')"
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
