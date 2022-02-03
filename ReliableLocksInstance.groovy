/**
 *  Reliable Locks Instance v1.4
 *
 *  Copyright 2019 Joel Wetzel
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

import groovy.json.*

definition(
	parent: "joelwetzel:Reliable Locks",
    name: "Reliable Locks Instance",
    namespace: "joelwetzel",
    author: "Joel Wetzel",
    description: "Child app that is instantiated by the Reliable Locks app.  It creates the binding between the physical lock and the virtual reliable lock.",
    category: "Convenience",
	iconUrl: "",
    iconX2Url: "",
    iconX3Url: "")


def notificationDevice = [
		name:				"notificationDevice",
		type:				"capability.notification",
		title:				"Devices for Notifications",
		description:		"Send notifications to devices.  ie. push notifications to a phone.",
		required:			false,
		multiple:			true
	]


preferences {
	page(name: "mainPage", title: "", install: true, uninstall: true) {
		section(getFormat("title", "Reliable Lock Instance")) {
			input (
	            name:				"wrappedLock",
	            type:				"capability.lock",
	            title:				"Wrapped Lock",
	            description:		"Select the lock to WRAP IN RELIABILITY.",
	            multiple:			false,
	            required:			true
            )
			input (
                name:				"refreshTime",
	            type:				"number",
	            title:				"After sending commands to lock, delay this many seconds and then refresh the lock",
	            defaultValue:		6,
	            required:			true
            )
			input (
            	name:				"autoRefreshOption",
	            type:				"enum",
	            title:				"Auto refresh every X minutes?",
	            options:			["Never", "1", "5", "10", "30" ],
	            defaultValue:		"30",
	            required:			true
            )
			input (
                type:               "bool",
                name:               "retryLockCommands",
                title:              "Retry lock/unlock commands if the lock doesn't respond the first time?",
                required:           true,
                defaultValue:       false
            )
		}
        section(hideable: true, hidden: true, "Notifications") {
			input notificationDevice
			paragraph "This will send a notification if the lock doesn't respond even after repeated retries."
		}
        section() {
            input (
				type:               "bool",
				name:               "enableDebugLogging",
				title:              "Enable Debug Logging?",
				required:           true,
				defaultValue:       true
			)
        }
	}
}


def installed() {
	log.info "Installed with settings: ${settings}"

	addChildDevice("joelwetzel", "Reliable Lock Virtual Device", "Reliable-${wrappedLock.displayName}", null, [name: "Reliable-${wrappedLock.displayName}", label: "Reliable ${wrappedLock.displayName}", completedSetup: true, isComponent: true])
	
	initialize()
}


def uninstalled() {
    childDevices.each {
		log.info "Deleting child device: ${it.displayName}"
		deleteChildDevice(it.deviceNetworkId)
	}
}


def updated() {
	log.info "Updated with settings: ${settings}"

	unsubscribe()
	initialize()
}


def initialize() {
	def reliableLock = getChildDevice("Reliable-${wrappedLock.displayName}")
	
	subscribe(wrappedLock, "lock", wrappedLockHandler)
    subscribe(wrappedLock, "codeChanged", wrappedLockCodeHandler)
    subscribe(wrappedLock, "battery", batteryHandler)

	// Generate a label for this child app
	app.updateLabel("Reliable ${wrappedLock.displayName}")
	
	// Make sure the ReliableLock state matches the WrappedLock upon initialization.
	wrappedLockHandler(null)
	
	if (autoRefreshOption == "30") {
		runEvery30Minutes(refreshWrappedLock)
	}
	else if (autoRefreshOption == "10") {
		runEvery10Minutes(refreshWrappedLock)
	}
	else if (autoRefreshOption == "5") {
		runEvery5Minutes(refreshWrappedLock)
	}
	else if (autoRefreshOption == "1") {
		runEvery1Minute(refreshWrappedLock)
	}
	else {
		unschedule(refreshWrappedLock)	
	}
}


def lockWrappedLock() {
	def reliableLock = getChildDevice("Reliable-${wrappedLock.displayName}")
	
	log "${reliableLock.displayName}:locking detected"
	log "${wrappedLock.displayName}:locking"
	wrappedLock.lock()
    
    state.desiredLockState = "locked"
    state.retryCount = 0
	
	runIn(refreshTime, refreshWrappedLockAndRetryIfNecessary)
}


def unlockWrappedLock() {
	def reliableLock = getChildDevice("Reliable-${wrappedLock.displayName}")
	
	log "${reliableLock.displayName}:unlocking detected"
	log "${wrappedLock.displayName}:unlocking"
	wrappedLock.unlock()
    
    state.desiredLockState = "unlocked"
    state.retryCount = 0
	
	runIn(refreshTime, refreshWrappedLockAndRetryIfNecessary)
}

def setCodeWrappedLock(codeNumber, code, name = null) {
    def reliableLock = getChildDevice("Reliable-${wrappedLock.displayName}")

    log "${reliableLock.displayName}:setCode detected"
	log "${wrappedLock.displayName}:setCode"

    wrappedLock.setCode(codeNumber, code, name)
    
    state.desiredLockCodeState = "added"
    state.retryLockCodeCount = 0
    
    runIn(refreshTime, refreshWrappedLockCodeAndRetryIfNecessary, [data: [codeNumber:codeNumber, code:code, name:name]])
}

def deleteCodeWrappedLock(codeNumber) {
    def reliableLock = getChildDevice("Reliable-${wrappedLock.displayName}")

    log "${reliableLock.displayName}:deleteCode detected"
	log "${wrappedLock.displayName}:deleteCode"
    wrappedLock.deleteCode(codeNumber)
    
    state.desiredLockCodeState = "deleted"
    state.retryLockCodeCount = 0
    
    runIn(refreshTime, refreshWrappedLockCodeAndRetryIfNecessary, [data: [codeNumber:codeNumber, code:null, name:null]])
}

def refreshWrappedLock() {
	log "${wrappedLock.displayName}:refreshing"
	wrappedLock.refresh()
}


def refreshWrappedLockAndRetryIfNecessary() {
	log "${wrappedLock.displayName}:refreshing"
	wrappedLock.refresh()
    
    if (retryLockCommands) {
        runIn(5, retryIfCommandNotFollowed)
    }
}

def refreshWrappedLockCodeAndRetryIfNecessary(data) {
	log "${wrappedLock.displayName}:refreshing LockCode"
	wrappedLock.refresh()
    
    if (retryLockCommands) {
        runIn(5, retryIfCodeCommandNotFollowed, [data: data])
    }
}


def retryIfCommandNotFollowed() {
    log "${wrappedLock.displayName}:retryIfCommandNotFollowed"
    
    // Check if the command had been followed.
    def commandWasFollowed = wrappedLock.currentValue("lock") == state.desiredLockState
    
    if (!commandWasFollowed) {
        log "Command was not followed. RetryCount is ${state.retryCount}."
        
        // Check if we have exceeded 2 retries.
        if (state.retryCount < 2) {
            // If we still need to retry, fire off lockWrappedLock or unlockWrappedLock again.
            state.retryCount = state.retryCount + 1
            if (state.desiredLockState == "locked") {
                log "${wrappedLock.displayName}:locking"
	            wrappedLock.lock()
            }
            else {
                log "${wrappedLock.displayName}:unlocking"
	            wrappedLock.unlock()
            }
            runIn(refreshTime, refreshWrappedLockAndRetryIfNecessary)
        }
        else {
            if (notificationDevice) {
                def commandText = state.desiredLockState == "locked" ? "Lock" : "Unlock"
                notificationDevice.deviceNotification("${wrappedLock.displayName} did not respond to repeated retries of the '${commandText}' command.")
            }
        }
    }
}

def retryIfCodeCommandNotFollowed(data) {
    log "${wrappedLock.displayName}:retryIfCodeCommandNotFollowed"
    
    // Check if the command had been followed.
    def commandWasFollowed = false
    Map lockCodes = getLockCodes(wrappedLock)
    log "lockCodes- ${lockCodes}"
    Map codeMap = getCodeMap(lockCodes, data.codeNumber)
    
    // Check validity only when "added" is desired, because for deleteCode, no code or name is provided
    if (state.desiredLockCodeState == "added") {
        if (!changeIsValid(wrappedLock, lockCodes, codeMap, data.codeNumber, data.code, data.name)) return
    }
    
   	Map newMapData = [:]
    String value
    
    // if it exists, update if different, else, create.
    if (codeMap) {
        if (codeMap.name != data.name || codeMap.code != data.code) {
            codeMap = ["name":"${data.name}", "code":"${data.code}"]
            lockCodes."${data.codeNumber}" = codeMap
            newMapData = ["${codeNumber}":codeMap]
            value = "changed"
        } else {
            commandWasFollowed = true
            updateLockCodes(lockCodes)
        }
    } else {
        codeMap = ["name":"${name}", "code":"${code}"]
        newMapData = ["${codeNumber}":codeMap]
        lockCodes << newMapData
        value = "added"
    }
    
    if (!commandWasFollowed) {
        log "Command was not followed. RetryLockCodeCount is ${state.retryLockCodeCount}."
        
        // Check if we have exceeded 2 retries.
        if (state.retryLockCodeCount < 2) {
            // If we still need to retry, fire off setCodeWrappedLock or deleteCodeWrappedLock again.
            state.retryLockCodeCount = state.retryLockCodeCount + 1
            if (state.desiredLockCodeState == "added") {
                log "${wrappedLock.displayName}:setCode(${data.codeNumber}, ${data.code}, ${data.name})"
	            wrappedLock.setCode(data.codeNumber, data.code, data.name)
            }
            else {
                log "${wrappedLock.displayName}:deleteCode(${data.codeNumber})"
	            wrappedLock.deleteCode(data.codeNumber)
            }
            runIn(refreshTime, refreshWrappedLockCodeAndRetryIfNecessary, [data: data])
        }
        else {
            if (notificationDevice) {
                def commandText = state.desiredLockCodeState == "added" ? "Set Code" : "Delete Code"
                notificationDevice.deviceNotification("${wrappedLock.displayName} did not respond to repeated retries of the '${commandText}' command.")
            }
        }
    }
}


def wrappedLockHandler(evt) {
	def reliableLock = getChildDevice("Reliable-${wrappedLock.displayName}")

	if (wrappedLock.currentValue("lock") == "locked") {
		log "${wrappedLock.displayName}:locked detected"
		log "${reliableLock.displayName}:setting locked"
		reliableLock.markAsLocked()
        state.desiredLockState = "locked"
	}
	else {
		log "${wrappedLock.displayName}:unlocked detected"
		log "${reliableLock.displayName}:setting unlocked"
		reliableLock.markAsUnlocked()
        state.desiredLockState = "unlocked"
	}
}

def wrappedLockCodeHandler(evt) {
	def reliableLock = getChildDevice("Reliable-${wrappedLock.displayName}")
    def lockCodeData = evt.jsonData
    def codeNumber = lockCodeData.keySet()[0]
    def code = lockCodeData.get(codeNumber).code
    def name = lockCodeData.get(codeNumber).name

	if (wrappedLock.currentValue("codeChanged") == "added") {
		log "${wrappedLock.displayName}:codeChanged added detected"
		log "${reliableLock.displayName}:setting setCode"
		reliableLock.markAsSetCode(codeNumber, code, name)
        state.desiredLockState = "added"
	}
	else {
		log "${wrappedLock.displayName}:codeChanged deleted detected"
		log "${reliableLock.displayName}:setting deleteCode"
		reliableLock.markAsDeleteCode(codeNumber, code, name)
        state.desiredLockState = "deleted"
	}
}

def batteryHandler(evt) {
	def reliableLock = getChildDevice("Reliable-${wrappedLock.displayName}")

    log "${wrappedLock.displayName}:battery detected"
    log "${reliableLock.displayName}:setting battery"
    
    def batteryValue = null
    if (wrappedLock.currentValue("battery") != null) {
        batteryValue = wrappedLock.currentValue("battery")
    } else if (wrappedLock.currentBattery != null) {
        batteryValue = wrappedLock.currentBattery
    }
    
    reliableLock.setBattery(batteryValue)
}


def getFormat(type, myText=""){
	if(type == "header-green") return "<div style='color:#ffffff;font-weight: bold;background-color:#81BC00;border: 1px solid;box-shadow: 2px 3px #A9A9A9'>${myText}</div>"
    if(type == "line") return "\n<hr style='background-color:#1A77C9; height: 1px; border: 0;'></hr>"
	if(type == "title") return "<h2 style='color:#1A77C9;font-weight: bold'>${myText}</h2>"
}


def log(msg) {
	if (enableDebugLogging) {
		log.debug(msg)	
	}
}

/*>> LOCK CODE HELPERS >>*/
Map getCodeMap(lockCodes, codeNumber){
    Map codeMap = [:]
    Map lockCode = lockCodes?."${codeNumber}"
    if (lockCode) {
        codeMap = ["name":"${lockCode.name}", "code":"${lockCode.code}"]
    }
    return codeMap
}

Map getLockCodes(wrappedLock) {
    /*
	on a real lock we would fetch these from the response to a userCode report request
	*/
    String lockCodes = wrappedLock.currentValue("lockCodes")
    Map result = [:]
    if (lockCodes) {
        result = new JsonSlurper().parseText(lockCodes)
    }
    return result
}

Boolean changeIsValid(wrappedLock, lockCodes, codeMap, codeNumber, code, name){
    //validate proposed lockCode change
    Boolean result = true
    Integer maxCodeLength = wrappedLock.currentValue("codeLength")?.toInteger() ?: 4
    Integer maxCodes = wrappedLock.currentValue("maxCodes")?.toInteger() ?: 20
    Boolean isBadLength = code.size() > maxCodeLength
    Boolean isBadCodeNum = maxCodes < codeNumber
    if (lockCodes) {
        List nameSet = lockCodes.collect{ it.value.name }
        List codeSet = lockCodes.collect{ it.value.code }
        if (codeMap) {
            nameSet = nameSet.findAll{ it != codeMap.name }
            codeSet = codeSet.findAll{ it != codeMap.code }
        }
        Boolean nameInUse = name in nameSet
        Boolean codeInUse = code in codeSet
        if (nameInUse || codeInUse) {
            if (nameInUse) { log.warn "changeIsValid:false, name:${name} is in use:${ lockCodes.find{ it.value.name == "${name}" } }" }
            if (codeInUse) { log.warn "changeIsValid:false, code:${code} is in use:${ lockCodes.find{ it.value.code == "${code}" } }" }
            result = false
        }
    }
    if (isBadLength || isBadCodeNum) {
        if (isBadLength) { log.warn "changeIsValid:false, length of code ${code} does not match codeLength of ${maxCodeLength}" }
        if (isBadCodeNum) { log.warn "changeIsValid:false, codeNumber ${codeNumber} is larger than maxCodes of ${maxCodes}" }
        result = false
    }
    return result
}

void updateLockCodes(lockCodes){
    /*
	whenever a code changes we update the lockCodes event
	*/
    log "updateLockCodes: ${lockCodes}"
    String strCodes = JsonOutput.toJson(lockCodes)
    sendEvent(name:"lockCodes", value:strCodes, isStateChange:true)
}
/*<< LOCK CODE HELPERS <<*/


