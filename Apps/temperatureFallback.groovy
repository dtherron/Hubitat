#include dtherron.logger

definition(
    name: 'Temperature Fallback',
    namespace: 'dtherron',
    author: 'Dan Herron',
    description: 'Virtual temperature reading from a primary or fallback device based on sensor state.',
    category: 'Green Living',
    iconUrl: 'TODO',
    iconX2Url: 'TODO'
)

preferences {
    page(name: 'mainPage', title: 'Temperature Fallback', install: true, uninstall: true) {
        section('<b>General</b>') {
            label(title: 'Device name')
        }
        
        section('<b>Temperature sensors</b>') {
            input name:'primaryTemperature', type:'capability.temperatureMeasurement', title: 'Primary temperature sensor', required: true
            input name:'fallbackTemperature', type:'capability.temperatureMeasurement', title: 'Fallback temperature sensor', required: true
        }

        section('<b>Contact sensors</b>') {
            input 'contactSensors', 'capability.contactSensor', title: 'Contact sensors to be used', multiple: true, required: true
            input (name: 'fallbackConditionState', type: 'enum', title: 'State to compare to', options: ['open', 'closed'], defaultValue: 'open', required: true)
            input (name: 'fallbackConditionType', type: 'enum', title: 'Comparison type', options: ['none', 'any', 'all'], defaultValue: 'none', required: true)
        }

        section('<b>Log Settings</b>') {
            input (name: 'logLevel', type: 'enum', title: 'Logging level: Messages with this level and higher will be logged', options: [[0: 'Disabled'], [1: 'Error'], [2: 'Warning'], [3: 'Info'], [4: 'Debug'], [5: 'Trace']], defaultValue: 3)
            input 'logDropLevelTime', 'number', title: 'Drop down to Info level after (minutes)', required: true, defaultValue: 5
        }
    }
}

def installed() {
    int loggingLevel
    if (settings.logLevel) {
        loggingLevel = settings.logLevel.toInteger()
    } else {
        loggingLevel = 3
    }

    initialize()
}

def updated() {
    initialize()
}

def uninstalled() {
    if (state.childDeviceId != null && getChildDevices().size() > 0) {
        logger('info', 'uninstalled', "Deleting child device")
        deleteChildDevice(state.childDeviceId)
    }    
    state.childDeviceId = null
}

def initialize() {
    def childDevice = createOrFindChildDevice()
    childDevice.setLabel(app.getLabel() + " device")
    logger('info', 'initialize', "Setting child device name to " + childDevice.getLabel())
    
    unsubscribe()
    subscribe(primaryTemperature, temperatureHandler, ['filterEvents': false])
    subscribe(fallbackTemperature, temperatureHandler, ['filterEvents': false])

    subscribe(contactSensors, 'contact', contactHandler)
    
    setFallbackBasedOnState()
    updateChildAttributes()
}

def createOrFindChildDevice() {
    if (state.childDeviceId != null) {
        def child = getChildDevices().size() > 0 ? getChildDevices().grep { it.getDeviceNetworkId() == state.childDeviceId }.first() : null
        if (child) {
            logger('trace', 'createOrFindChildDevice', "Returning existing child device")
            return child
        }
        logger('warn', 'createOrFindChildDevice', "Existing child device not found.")
    }

    state.childDeviceId = 'tfd' + Math.abs(new Random().nextInt() % 9999) + '_' + (now() % 9999)
    def childName = app.getLabel() + " device"
    logger('info', 'createOrFindChildDevice', "Creating new child device")
    def child = addChildDevice('hubitat', 'Virtual Temperature Sensor', state.childDeviceId, [label: label, name: childName, completedSetup: true])
    return child;
}

def temperatureHandler(evt) {
    logger('trace', 'temperatureHandler', "Got event: ${evt.name}=${evt.value} from ${evt.displayName}")
    def temperatureDevice = state.useFallbackDevice ? settings.fallbackTemperature : settings.primaryTemperature

    if (evt.device.getId() == temperatureDevice.getId()) {
        logger('trace', 'temperatureHandler', "This is the current temperature device")
        updateChildAttributes()
    }
}

def updateChildAttributes() {
    def childDevice = createOrFindChildDevice()
    def temperatureDevice = state.useFallbackDevice ? settings.fallbackTemperature : settings.primaryTemperature

    logger('trace', 'updateChildTemperature', "Updating temperature of child ${childDevice.displayName} to match ${temperatureDevice.displayName}")
    childDevice.setTemperature(temperatureDevice.currentValue('presence') == 'present' ? temperatureDevice.currentValue('temperature') : null)
}
            
def contactHandler(evt) {
    setFallbackBasedOnState()
    updateChildAttributes()
}

def setFallbackBasedOnState() {
    def useFallback = !shouldUsePrimary()
    logger('trace', 'setFallbackBasedOnState', "Setting state.useFallback to $useFallback")
    state.useFallbackDevice = useFallback
}

def shouldUsePrimary() {
    logger('trace', 'shouldUsePrimary', "Check if ${settings.fallbackConditionType} sensors are set to ${settings.fallbackConditionState}")
    for (contactSensor in contactSensors) {
        def sensorState = contactSensor.currentValue('contact')
        logger('trace', 'shouldUsePrimary', "Checking contact sensor $contactSensor (set to $sensorState)")
        if (settings.fallbackConditionType == 'none' && sensorState == settings.fallbackConditionState) {
            return false
        } else if (settings.fallbackConditionType == 'any' && sensorState == settings.fallbackConditionState) {
            return true
        } else if (settings.fallbackConditionType == 'all' && sensorState != settings.fallbackConditionState) {
            return false
        } 
    }

    // If 'none' or 'all', default to return true; for 'any' default false
    return settings.fallbackConditionType != 'any'
}
