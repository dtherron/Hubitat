/*
    Forked from Aqara Single Gang Dual Switch by Benoit Tessier
*/

metadata {
        definition (name: 'Aqara Smart Light Switch (No Neutral, Double Rocker)', namespace: 'dtherron', author: 'Dan Herron / Benoit Tessier / Mike Maxwell') {
        capability 'Configuration'
        capability 'Refresh'

        //commands, these will create the appropriate component device if it doesn't already exist...
        command 'topSwitchOn'
        command 'topSwitchOff'
        command 'bottomSwitchOn'
        command 'bottomSwitchOff'

        fingerprint profileId:'0104', endpointId:'01', inClusters:'0000,0002,0003,0004,0005,0006,0009', outClusters:'000A,0019', model:'lumi.switch.b2laus01', manufacturer:'LUMI'
        }

        preferences {
            input name: 'logEnable', type: 'bool', title: 'Enable debug logging', defaultValue: true
            input name: 'txtEnable', type: 'bool', title: 'Enable descriptionText logging', defaultValue: true
        }
}

void logsOff() {
    log.warn 'debug logging disabled...'
    device.updateSetting('logEnable', [value:'false', type:'bool'])
}

void updated() {
    log.info 'updated...'
    log.warn "debug logging is: ${logEnable == true}"
    log.warn "description logging is: ${txtEnable == true}"
    if (logEnable) runIn(1800, logsOff)
}

def parse(String description) {
    if (logEnable) log.debug "Parsing '${description}'"
    if (description?.startsWith('read attr -')) {
        def descMap = parseDescriptionAsMap(description)
        if (logEnable) log.debug "parse endpoint '${descMap.endpoint}'"
        switch ("${descMap.endpoint}") {
            case '01':
                if (logEnable) log.debug 'parse Top'
                def cd = fetchChild('Top')
                switch ("${descMap.value}") {
                    case '01':
                        cd.parse([[name:'switch' , value:'on']])
                        break
                    case '00':
                        cd.parse([[name:'switch', value:'off']])
                        break
                }
                break
            case '02':
                if (logEnable) log.debug 'parse Bottom'
                def cd = fetchChild('Bottom')
                switch ("${descMap.value}") {
                    case '01':
                        cd.parse([[name:'switch' , value:'on']])
                        break
                    case '00':
                        cd.parse([[name:'switch', value:'off']])
                        break
                }
                break
        }
    }

    if (logEnable) log.debug 'parse done'
}

// custom commands

def topSwitchOn() {
    def cd = fetchChild('Top')
    cd.parse([[name:'switch', value:'on', descriptionText:"${cd.displayName} was turned on"]])
    def hubAction = new hubitat.device.HubAction("he cmd 0x${device.deviceNetworkId} 1 0x0006 1 {}", hubitat.device.Protocol.ZIGBEE)
    if (logEnable) log.debug "topSwitchOn sending '${hubAction}'"
    sendHubCommand(hubAction)
}

def topSwitchOff() {
    def cd = fetchChild('Top')
    cd.parse([[name:'switch', value:'off', descriptionText:"${cd.displayName} was turned off"]])
    def hubAction = new hubitat.device.HubAction("he cmd 0x${device.deviceNetworkId} 1 0x0006 0 {}", hubitat.device.Protocol.ZIGBEE)
    if (logEnable) log.debug "topSwitchOff sending '${hubAction}'"
    sendHubCommand(hubAction)
}

def bottomSwitchOn() {
    def cd = fetchChild('Bottom')
    cd.parse([[name:'switch', value:'on', descriptionText:"${cd.displayName} was turned on"]])
    def hubAction = new hubitat.device.HubAction("he cmd 0x${device.deviceNetworkId} 2 0x0006 1 {}", hubitat.device.Protocol.ZIGBEE)
    if (logEnable) log.debug "bottomSwitchOn sending '${hubAction}'"
    sendHubCommand(hubAction)
}

def bottomSwitchOff() {
    def cd = fetchChild('Bottom')
    cd.parse([[name:'switch', value:'off', descriptionText:"${cd.displayName} was turned off"]])
    def hubAction = new hubitat.device.HubAction("he cmd 0x${device.deviceNetworkId} 2 0x0006 0 {}", hubitat.device.Protocol.ZIGBEE)
    if (logEnable) log.debug "bottomSwitchOff sending '${hubAction}'"
    sendHubCommand(hubAction)
}

def fetchChild(String name) {
    String thisId = device.id
    def cd = getChildDevice("${thisId}-${name}")
    List<Map> defaultValues = []

    if (!cd) {
        cd = addChildDevice('hubitat', 'Generic Component Switch', "${thisId}-${name}", [name: "${device.displayName} ${name}", isComponent: true])
    }

    cd.parse(defaultValues)

    return cd
}

//child device methods
void componentRefresh(cd) {
    if (logEnable) log.info "received refresh request from ${cd.displayName}"
}

void componentOn(cd) {
    String txt = cd.getName()
    if (logEnable) log.info "received componentOn from $txt"
    getChildDevice(cd.deviceNetworkId).parse([[name:'switch', value:'on', descriptionText:"${cd.displayName} was turned on"]])
    switch (txt.minus("${device.displayName} ")) {
            case 'Top':
            if (logEnable) log.info 'turning on top'
            topSwitchOn()
            break
            case 'Bottom':
            if (logEnable) log.info 'turning on bottom'
            bottomSwitchOn()
            break
    }
}

void componentOff(cd) {
    String txt = cd.getName()
    if (logEnable) log.info "received componentOff from $txt"
    getChildDevice(cd.deviceNetworkId).parse([[name:'switch', value:'off', descriptionText:"${cd.displayName} was turned off"]])
    switch (txt.minus("${device.displayName} ")) {
            case 'Top':
            if (logEnable) log.info 'turning off top'
            topSwitchOff()
            break
            case 'Bottom':
            if (logEnable) log.info 'turning off bottom'
            bottomSwitchOff()
            break
    }
}

def configure() {
    //Config binding and report for each endpoint
    if (logEnable) log.debug "Executing 'configure'"
    [
        "zdo bind 0x${device.deviceNetworkId} 1 1 0x0006 {${device.zigbeeId}} {}", 'delay 200',
        'zcl global send-me-a-report 0x0006 0 0x10 0 600 {01}',
        "send 0x${device.deviceNetworkId} 1 1", 'delay 500',

        "zdo bind 0x${device.deviceNetworkId} 2 1 0x0006 {${device.zigbeeId}} {}", 'delay 200',
        'zcl global send-me-a-report 0x0006 0 0x10 0 600 {01}',
        "send 0x${device.deviceNetworkId} 1 2", 'delay 500',
    ]
}

def refresh() {
    //Read Attribute On Off Value of each endpoint
    if (logEnable)log.debug "Executing 'refresh'"
    [
        "st rattr 0x${device.deviceNetworkId} 1 0x0006 0", 'delay 200',
        "st rattr 0x${device.deviceNetworkId} 2 0x0006 0"
    ]
}

def parseDescriptionAsMap(description) {
    if (description?.startsWith('read attr -')) {
        (description - 'read attr - ').split(',').inject([:]) { map, param ->
            def nameAndValue = param.split(':')
            map += [(nameAndValue[0].trim()): nameAndValue[1].trim()]
        }
    }
    else if (description?.startsWith('catchall: ')) {
        def seg = (description - 'catchall: ').split(' ')
        def zigbeeMap = [:]
        zigbeeMap += [raw: (description - 'catchall: ')]
        zigbeeMap += [profileId: seg[0]]
        zigbeeMap += [clusterId: seg[1]]
        zigbeeMap += [sourceEndpoint: seg[2]]
        zigbeeMap += [destinationEndpoint: seg[3]]
        zigbeeMap += [options: seg[4]]
        zigbeeMap += [messageType: seg[5]]
        zigbeeMap += [dni: seg[6]]
        zigbeeMap += [isClusterSpecific: Short.valueOf(seg[7], 16) != 0]
        zigbeeMap += [isManufacturerSpecific: Short.valueOf(seg[8], 16) != 0]
        zigbeeMap += [manufacturerId: seg[9]]
        zigbeeMap += [command: seg[10]]
        zigbeeMap += [direction: seg[11]]
        zigbeeMap += [data: seg.size() > 12 ? seg[12].split('').findAll { it }.collate(2).collect {
            it.join('')
        } : []]

        zigbeeMap
    }
}
