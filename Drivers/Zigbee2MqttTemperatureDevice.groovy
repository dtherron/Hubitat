import groovy.json.JsonSlurper
import groovy.json.JsonOutput

metadata {
    definition (name: "Zigbee2Mqtt Temperature Device", 
        namespace: "dtherron", 
        author: "Dan Herron",
        importUrl: "https://raw.githubusercontent.com/dtherron/Hubitat/main/Drivers/Zigbee2MqttTemperatureDevice.groovy") {
        
        capability "Sensor"
        capability "PresenceSensor"
        capability "Initialize"

        capability "Battery"
        capability "TemperatureMeasurement"
        capability "RelativeHumidityMeasurement"
        capability "PressureMeasurement"

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
                title: "MQTT topic for zigbee2mqtt",
                description: "e.g. zigbee2mqtt",
                defaultValiue: "zigbee2mqtt",
                required: true,
                displayDuringSetup: true
            )
            input(
                name: "deviceFriendlyName", 
                type: "string",
                title: "Friendly Name for the device in Zigbee2Mqtt",
                description: "e.g. DownstairsTemp",
                required: true,
                displayDuringSetup: true
            )
           input (
               name: 'logLevel',
               type: 'enum',
               title: 'Logging level: Messages with this level and higher will be logged',
               options: [[0: 'Disabled'], [1: 'Error'], [2: 'Warning'], [3: 'Info'], [4: 'Debug'], [5: 'Trace']], 
               defaultValue: 5
           ) 
        }
    }
}

void initialize() {
    logger("info", "initialize", "Device installed. Initializing to default..")
    updated()
}

def updated() {
    def logLevel = settings.logLevel.toInteger()
    logger("info", "updated", "Setting log level $logLevel.")
    state.loggingLevel = logLevel
    unschedule()
    subscribe()
    runEvery15Minutes("checkPresence")
}

def subscribe() {
    if (!mqttConnected()) {
        connectMqtt()
    }

    def topic = "${settings.mqttTopic}/${settings.deviceFriendlyName}"
    logger("info", "subscribe", "topic: $topic")
    interfaces.mqtt.subscribe(topic)
}

def connectMqtt() {
    logger("info", "connectMqtt", "Connecting to MQTT broker as client ${getClientId()}")
    
    try {   
        interfaces.mqtt.connect(getBrokerUri(),
                           getClientId(), 
                           settings?.brokerUser, 
                           settings?.brokerPassword)
       
        // delay for connection
        pauseExecution(1000)        
    } catch(Exception e) {
        logger("error", "connectMqtt", "Connecting MQTT failed: ${e}")
        return
    }
}

def disconnectMqtt() {
    logger("info", "disconnectMqtt", "Disconnecting from MQTT broker")
    try {
        interfaces.mqtt.disconnect()
    } catch(e) {
        logger("warn", "disconnectMqtt", "Disconnection from broker failed: ${e.message}")
    }
}

// ========================================================
// MQTT METHODS
// ========================================================

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

def mqttConnected() {
    return interfaces.mqtt.isConnected()
}

// Parse incoming message from the MQTT broker
def parse(String event) {
    def message = interfaces.mqtt.parseMessage(event)  
    def (topic, friendlyName) = message.topic.tokenize( '/' )
    
    logger("trace", "parse", "Received MQTT message on topic ${topic} from FN ${friendlyName} with value ${message.payload}")

    def slurper = new groovy.json.JsonSlurper()
    def result = slurper.parseText(message.payload)

    sendEvent(name: "presence", value: "present") 
    state.lastReceivedData = now()

    if (result.temperature != null) {
        def receivedTemp = celsiusToFahrenheit(result.temperature)
        if (receivedTemp != device.currentValue("temperature")) {
            logger("trace", "parse", "Set temperature to $receivedTemp")
            sendEvent(name: "temperature", value: receivedTemp, unit: "°F", isStateChange: true) 
        }
    }
    
    if (result.battery != null && result.battery != device.currentValue("battery")) {
        logger("trace", "parse", "Set battery to ${result.battery}")
        sendEvent(name: "battery", value: result.battery, isStateChange: true) 
    }

    if (result.pressure != null && result.pressure != device.currentValue("pressure")) {
        logger("trace", "parse", "Set pressure to ${result.pressure}")
        sendEvent(name: "pressure", value: result.pressure, isStateChange: true)
    }

    if (result.humidity != null && result.humidity != device.currentValue("humidity")) {
        logger("trace", "parse", "Set humidity to ${result.humidity}")
        sendEvent(name: "humidity", value: result.humidity, isStateChange: true)
    }
}

def checkPresence() {
    if (!mqttConnected()) {
        connectMqtt()
    }

    if (state.lastReceivedData != null && (state.lastReceivedData < (now() - 14400000))) {
        logger("warn", "checkPresence", "No updates in 4 hours; marking not present")
        sendEvent(name: "presence", value: "not present") 
        state.lastReceivedData = null
    }
}

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
