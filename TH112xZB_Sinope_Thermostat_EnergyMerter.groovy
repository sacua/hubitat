/**
 *  Sinope TH1123ZB, TH1124ZB Device Driver for Hubitat
 *  Source: https://github.com/sacua/hubitat/blob/main/TH112xZB_Sinope_Thermostat_EnergyMerter.groovy
 *
 *  Code derived from Sinope's SmartThing thermostat for their Zigbee protocol requirements and from the driver of scoulombe79 and kris2k2
 *  Source: https://support.sinopetech.com/en/wp-content/uploads/sites/4/2019/08/Sinope-Technologies-Zigbee-Thermostat-V.1.0.0-SVN-547-1.txt
 *  Source: https://github.com/scoulombe79/Hubitat/blob/master/Drivers/Thermostat-Sinope-TH1123ZB.groovy
 *  Source: https://github.com/kris2k2/hubitat/blob/master/drivers/kris2k2-Sinope-TH112XZB.groovy
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

metadata
{
     definition(name: "TH112xZB Sinope Thermostat EnergyMerter V2", namespace: "sacua", author: "Samuel Cuerrier Auclair") {
        capability "Thermostat"
        capability "Configuration"
        capability "TemperatureMeasurement"
        capability "Refresh"
        capability "Lock"
        capability "PowerMeter"
        capability "EnergyMeter"
        capability "Notification" // Receiving temperature notifications via RuleEngine
        
        attribute "heatingDemand", "number"
        attribute "cost", "number"
        
        command "reset" //Reset the energy meter via RuleEngine or preferences settings
        command "refreshTime" //Refresh the clock on the thermostat
        command "displayOn"
        command "displayOff"
        command "refreshTemp" //To refresh only the temperature reading
        
        
        preferences {
          //Available on command input name: "prefDisplayBacklight", type:"enum", title: "Backlight setting", options: ["On Demand", "Sensing"], defaultValue: "Sensing"
          //Available on command input name: "prefKeyLock", type:"bool", title: "Keylock", description: "Set to true to enable the lock on the thermostat"
          input name: "prefDisplayClock", type: "bool", title: "Enable display of clock", defaultValue: true
          input name: "prefDisplayOutdoorTemp", type:"bool", title: "Display outdoor temperature", defaultValue: true
        
          input name: "energyPrice", type: "float", title: "c/kWh Cost:", description: "Electric Cost per Kwh in cent", range: "0..*", defaultValue: 9.38
          input name: "EnergyResetInterv", type: "enum", title: "Reset interval for the energy tracking", options: ["Daily", "Weekly", "Monthly", "Yearly", "Never"], defaultValue: "Daily"
          input name: "MinInterRep", type: "number", title: "Minimum interval reporting time for energy calculation, 30..600", range: "30..600", defaultValue: 60
          input name: "MaxInterRep", type: "number", title: "Maximum interval reporting time for energy calculation, 60..900", range: "60..900", defaultValue: 90
          input name: "txtEnable", type: "bool", title: "Enable logging info", defaultValue: true
        }

        fingerprint profileId: "0104", deviceId: "119C", manufacturer: "Sinope Technologies", model: "TH1123ZB", deviceJoinName: "TH1123ZB"
        fingerprint profileId: "0104", deviceId: "119C", manufacturer: "Sinope Technologies", model: "TH1124ZB", deviceJoinName: "TH1124ZB"
        
    }
}

//-- Installation ----------------------------------------------------------------------------------------

def installed() {
    if (txtEnable) log.info "installed() : running configure()"
    if (state.time == null)  
      state.time = now()
    if (state.energyValue == null) 
      state.energyValue = 0 as double
    if (state.powerValue == null)  
      state.powerValue = 0 as int
    configure()
}

def updated() {
    if (txtEnable) log.info "updated() : running configure()"
    
    if (state.time == null)  
      state.time = now()
    if (state.energyValue == null) 
      state.energyValue = 0 as double
    if (state.powerValue == null)  
      state.powerValue = 0 as int
    
    if (!state.updatedLastRanAt || now() >= state.updatedLastRanAt + 5000) {
      state.updatedLastRanAt = now()
      configure()
      refresh()
   }
}

def uninstalled() {
    if (txtEnable) log.info "uninstalled() : unscheduling configure() and reset()"
    try {    
        unschedule()
    } catch (errMsg) {
        log.info "uninstalled(): Error unschedule() - ${errMsg}"
    }
}

      
//-- Parsing -----------------------------------------------------------------------------------------

// parse events into attributes
def parse(String description) {
    def result = []
    def scale = getTemperatureScale()
    state?.scale = scale
    def cluster = zigbee.parse(description)
    if (description?.startsWith("read attr -")) {
        // log.info description
        def descMap = zigbee.parseDescriptionAsMap(description)
        result += createCustomMap(descMap)
        if(descMap.additionalAttrs){
               def mapAdditionnalAttrs = descMap.additionalAttrs
            mapAdditionnalAttrs.each{add ->
                add.cluster = descMap.cluster
                result += createCustomMap(add)
            }
        }
    }
    return result
}

private createCustomMap(descMap){
    def result = null
    def map = [: ]
        if (descMap.cluster == "0201" && descMap.attrId == "0000") {
            map.name = "temperature"
            map.value = getTemperature(descMap.value)
            
        } else if (descMap.cluster == "0201" && descMap.attrId == "0008") {
            map.name = "thermostatOperatingState"
            map.value = getHeatingDemand(descMap.value)
            if (map.value.toInteger() == 100)  state.heatingDemand = 100 as int
            map.value = (map.value.toInteger() < 5) ? "idle" : "heating"
            
            sendEvent(name: "heatingDemand", value: getHeatingDemand(descMap.value).toInteger(), unit: "%")         
            runIn(2, "refreshPower") // Heating changed .. Request energyReport in 2 seconds
        
        } else if (descMap.cluster == "0201" && descMap.attrId == "0012") {
            map.name = "heatingSetpoint"
            map.value = getTemperature(descMap.value)

        } else if (descMap.cluster == "0201" && descMap.attrId == "001C") {
            map.name = "thermostatMode"
            map.value = getModeMap()[descMap.value]
            
        } else if (descMap.cluster == "0204" && descMap.attrId == "0001") {
            map.name = "lock"
            map.value = getLockMap()[descMap.value]
            
        } else if (descMap.cluster == "0B04" && descMap.attrId == "050B") {
            map.name = "power"
            map.value = getActivePower(descMap.value)
            map.unit = "W"
            energyReport(state.powerValue)
            state.powerValue = map.value.toInteger()
            if (state.heatingDemand == 100)  {
              state.powerCapacity = map.value.toInteger()
              state.remove("heatingDemand")
            }
        }
        
    if (map) {
        def isChange = isStateChange(device, map.name, map.value.toString())
        map.displayed = isChange
        if ((map.name.toLowerCase().contains("temp")) || (map.name.toLowerCase().contains("setpoint"))) {
            map.scale = scale
        }
        result = createEvent(map)
    }
    return result
}
            
//-- Capabilities -----------------------------------------------------------------------------------------

def configure(){    
    if (txtEnable) log.info "configure()"    
    // Set unused default values
    sendEvent(name: "coolingSetpoint", value:getTemperature("0BB8")) // 0x0BB8 =  30 Celsius
    sendEvent(name: "thermostatFanMode", value:"auto") // We dont have a fan, so auto it is
    updateDataValue("lastRunningMode", "heat") // heat is the only compatible mode for this device
    
    try
    {
        unschedule()
    }
    catch (e)
    {
    }
    def d = new Date()
    int timesec = d.seconds
    int timemin = d.minutes
    int timehour = Math.abs( new Random().nextInt() % 11) 
    schedule(timesec + " " + timemin + " " + timehour + "/12 * * ? *",refreshTime) //refresh the clock at random begining and then every 12h
    if (EnergyResetInterv == "Daily")
      schedule("0 0 0 * * ? *", reset) 
    else if (EnergyResetInterv == "Weekly")
      schedule("0 0 0 ? * 1 *", reset)
    else if (EnergyResetInterv == "Monthly")
      schedule("0 0 0 1 * ? *", reset)
    else if (EnergyResetInterv == "Yearly")
      schedule("0 0 0 1 1 ? *", reset)
    
    // Prepare our zigbee commands
    def cmds = []

    // Configure Reporting
    cmds += zigbee.configureReporting(0x0201, 0x0000, 0x29, 19, 301, 50)     //local temperature
    cmds += zigbee.configureReporting(0x0201, 0x0008, 0x0020, 4, 300, 10)    //PI heating demand
    cmds += zigbee.configureReporting(0x0201, 0x0012, 0x0029, 15, 302, 40)   //occupied heating setpoint    
    cmds += zigbee.configureReporting(0x0204, 0x0000, 0x30, 1, 0)            //Attribute ID 0x0000 = temperature display mode, Data Type: 8 bits enum
    cmds += zigbee.configureReporting(0x0204, 0x0001, 0x30, 1, 0)            //Attribute ID 0x0001 = keypad lockout, Data Type: 8 bits enum
    cmds += zigbee.configureReporting(0x0B04, 0x050B, DataType.INT16, 30, 599, 0x64) //Thermostat power draw
    
    // Configure displayed scale
    if (getTemperatureScale() == 'C') {
        cmds += zigbee.writeAttribute(0x0204, 0x0000, 0x30, 0)    // Wr °C on thermostat display
    } else {
        cmds += zigbee.writeAttribute(0x0204, 0x0000, 0x30, 1)    // Wr °F on thermostat display 
    }

    // Configure keylock - Available on command
    //if (prefKeyLock) {
    //    cmds+= zigbee.writeAttribute(0x204, 0x01, 0x30, 0x01) // Lock Keys
    //} else {
    //    cmds+= zigbee.writeAttribute(0x204, 0x01, 0x30, 0x00) // Unlock Keys
    //}

    // Configure Outdoor Weather
    if (prefDisplayOutdoorTemp) {
        cmds += zigbee.writeAttribute(0xFF01, 0x0011, 0x21, 10800)  //set the outdoor temperature timeout to 3 hours
    } else {
        cmds += zigbee.writeAttribute(0xFF01, 0x0011, 0x21, 0)  //set the outdoor temperature timeout immediately
    }     
        
    // Configure Screen Brightness - Available on command
    //if (prefDisplayBacklight == "On Demand")
    //    cmds += zigbee.writeAttribute(0x0201, 0x0402, DataType.ENUM8, 0x0000) // set display brightnes to on demand
    //else
    //    cmds += zigbee.writeAttribute(0x0201, 0x0402, DataType.ENUM8, 0x0001) // set display brightnes to ambient lighting

    if (cmds)
      sendZigbeeCommands(cmds) // Submit zigbee commands
    return
}

def refresh() {
    if (txtEnable) log.info "refresh()"
    
    def cmds = []    
    cmds += zigbee.readAttribute(0x0201, 0x0000) //Read Local Temperature
    cmds += zigbee.readAttribute(0x0201, 0x0008) //Read PI Heating State  
    cmds += zigbee.readAttribute(0x0201, 0x0012) //Read Heat Setpoint
    cmds += zigbee.readAttribute(0x0201, 0x001C) //Read System Mode
    cmds += zigbee.readAttribute(0x0201, 0x401C, [mfgCode: "0x1185"]) //Read System Mode
    cmds += zigbee.readAttribute(0x0204, 0x0000) //Read Temperature Display Mode
    cmds += zigbee.readAttribute(0x0204, 0x0001) //Read Keypad Lockout
    cmds += zigbee.readAttribute(0x0B04, 0x050B)  //Read thermostat Active power
    
    if (cmds)
        sendZigbeeCommands(cmds) // Submit zigbee commands
}   

def refreshTemp() {
  def cmds = []
  cmds += zigbee.readAttribute(0x0201, 0x0000)  //Read Local Temperature
    
  if (cmds)
    sendZigbeeCommands(cmds)
}

def refreshTime() {
  def cmds = []
  def thermostatDate = new Date();
  def thermostatTimeSec = thermostatDate.getTime() / 1000;
  def thermostatTimezoneOffsetSec = thermostatDate.getTimezoneOffset() * 60;
  def currentTimeToDisplay = Math.round(thermostatTimeSec - thermostatTimezoneOffsetSec - 946684800);

  cmds += zigbee.writeAttribute(0xFF01, 0x0020, DataType.UINT32, zigbee.convertHexToInt(hex(currentTimeToDisplay)), [mfgCode: "0x119C"])

  if (cmds)
    sendZigbeeCommands(cmds)
}

def displayOn() {
  def cmds = []
  cmds += zigbee.writeAttribute(0x0201, 0x0402, DataType.ENUM8, 0x0001)

  if (cmds)
    sendZigbeeCommands(cmds) // Submit zigbee commands
}

def displayOff() {
  def cmds = []
  cmds += zigbee.writeAttribute(0x0201, 0x0402, DataType.ENUM8, 0x0000)

  if (cmds)
    sendZigbeeCommands(cmds) // Submit zigbee commands
}

def reset() {
  state.energyValue = 0.0
  state.time = now()
  sendEvent(name: "energy", value: state.energyValue)
  sendEvent(name: "cost", value: 0.0)
  state.resetTime = new Date().format('MM/dd/yy hh:mm a', location.timeZone)
}

def auto() {
    log.warn "auto(): mode is not available for this device. => Defaulting to heat mode instead."
    heat()
}

def cool() {
    log.warn "cool(): mode is not available for this device. => Defaulting to heat mode instead."
    heat()
}

def emergencyHeat() {
    log.warn "emergencyHeat(): mode is not available for this device. => Defaulting to heat mode instead."
    heat()
}

def fanAuto() {
    log.warn "fanAuto(): mode is not available for this device"
}

def fanCirculate() {
    log.warn "fanCirculate(): mode is not available for this device"
}

def fanOn() {
    log.warn "fanOn(): mode is not available for this device"
}

def heat() {
    if (txtEnable) log.info "heat(): mode set"
    
    def cmds = []
    cmds += zigbee.writeAttribute(0x0201, 0x001C, 0x30, 04, [:], 1000) // MODE
    cmds += zigbee.writeAttribute(0x0201, 0x401C, 0x30, 04, [mfgCode: "0x1185"]) // SETPOINT MODE
    
    // Submit zigbee commands
    sendZigbeeCommands(cmds)
}

def off() {
    log.warn "off(): mode set, it means no heating!"
    
    def cmds = []
    cmds += zigbee.writeAttribute(0x0201, 0x001C, 0x30, 0)
    
    // Submit zigbee commands
    sendZigbeeCommands(cmds)    
}

def setCoolingSetpoint(degrees) {
    log.warn "setCoolingSetpoint(${degrees}): is not available for this device"
}

def setHeatingSetpoint(preciseDegrees) {
    if (preciseDegrees != null) {
        def temperatureScale = getTemperatureScale()
        def degrees = new BigDecimal(preciseDegrees).setScale(2, BigDecimal.ROUND_HALF_UP)
        def cmds = []        
        
        if (txtEnable) log.info "setHeatingSetpoint(${degrees}:${temperatureScale})"
        
        def celsius = (temperatureScale == "C") ? degrees as Float : (fahrenheitToCelsius(degrees) as Float).round(2)
        int celsius100 = Math.round(celsius * 100)
        
        cmds += zigbee.writeAttribute(0x0201, 0x0012, 0x29, celsius100) //Write Heat Setpoint

        // Submit zigbee commands
        sendZigbeeCommands(cmds)         
    } 
}

//def setSchedule(JSON_OBJECT){
//    log.info "setSchedule(JSON_OBJECT): is not available for this device"
//}

def setThermostatFanMode(fanmode){
    log.warn "setThermostatFanMode(${fanmode}): is not available for this device"
}

def setThermostatMode(String value) {
    if (txtEnable) log.info "setThermostatMode(${value})"
    
    switch (value) {
        case "heat":
        case "emergency heat":
        case "auto":
        case "cool":
            return heat()
        
        case "off":
            return off()
    }
}

def unlock() {
  if (txtEnable) log.info "TH1123ZB >> unlock()"
  sendEvent(name: "lock", value: "unlocked")

  def cmds = []
  cmds += zigbee.writeAttribute(0x0204, 0x0001, DataType.ENUM8, 0x00)

  sendZigbeeCommands(cmds)
}

def lock() {
  if (txtEnable) log.info "TH1123ZB >> lock()"
  sendEvent(name: "lock", value: "locked")

  def cmds = []
  cmds += zigbee.writeAttribute(0x0204, 0x0001, DataType.ENUM8, 0x01)

  sendZigbeeCommands(cmds)
}

def deviceNotification(text) {
    double outdoorTemp = text.toDouble()
    def cmds = []

    if (prefDisplayOutdoorTemp) {
        if (txtEnable) log.info "deviceNotification() : Received outdoor weather : ${text} : ${outdoorTemp}"
    
        //the value sent to the thermostat must be in C
        if (getTemperatureScale() == 'F') {    
            outdoorTemp = fahrenheitToCelsius(outdoorTemp).toDouble()
        }        
        
        int outdoorTempDevice = outdoorTemp*100
        cmds += zigbee.writeAttribute(0xFF01, 0x0011, 0x21, 10800)   //set the outdoor temperature timeout to 3 hours
        cmds += zigbee.writeAttribute(0xFF01, 0x0010, 0x29, outdoorTempDevice, [mfgCode: "0x119C"]) //set the outdoor temperature as integer
    
        // Submit zigbee commands    
        sendZigbeeCommands(cmds)
    } else {
        log.info "deviceNotification() : Not setting any outdoor weather, since feature is disabled."  
    }
}

//-- Private functions -----------------------------------------------------------------------------------
private void sendZigbeeCommands(cmds) {
    cmds.removeAll { it.startsWith("delay") }
    def hubAction = new hubitat.device.HubMultiAction(cmds, hubitat.device.Protocol.ZIGBEE)
    sendHubCommand(hubAction)
}

private getTemperature(value) {
    if (value != null) {
        def celsius = Integer.parseInt(value, 16) / 100
        if (getTemperatureScale() == "C") {
            return celsius
        }
        else {
            return Math.round(celsiusToFahrenheit(celsius))
        }
    }
}

private getModeMap() {
  [
    "00": "off",
    "04": "heat"
  ]
}

private getLockMap() {
  [
    "00": "unlocked ",
    "01": "locked ",
  ]
}

private getActivePower(value) {
  if (value != null)
  {
    def activePower = Integer.parseInt(value, 16)

    return activePower
  }
}

private getTemperatureScale() {
    return "${location.temperatureScale}"
}

private getHeatingDemand(value) {
    if (value != null) {
        def demand = Integer.parseInt(value, 16)
        return demand.toString()
    }
}

private roundTwoPlaces(val)
{
  return Math.round(val * 100) / 100
}

private hex(value)
{
  String hex = new BigInteger(Math.round(value).toString()).toString(16)

  return hex
}

private refreshPower() {
    def cmds = []
    cmds += zigbee.readAttribute(0x0B04, 0x050B)  //Read thermostat Active power
    
    if (cmds)
        sendZigbeeCommands(cmds)
}

private energyReport(value) {
    def time = (now() - state.time) / 3600000 / 1000
    state.time = now()

    def energyValue = state.energyValue + (time * value)
    float EnergyValue = roundTwoPlaces(energyValue)
    
    sendEvent(name: "energy", value: EnergyValue, unit: "kWh")

    state.energyValue = energyValue

    float localCostPerKwh = 9.38
    if (energyPrice)
      localCostPerKwh = energyPrice as float

    def costValue = roundTwoPlaces(energyValue * localCostPerKwh / 100)

    sendEvent(name: "cost", value: costValue, unit: "\$")

    //Request energyReport in (between MinInterRep and MaxInterRep - Define in preferences) seconds if powerValue is greater than 0
    if (value > 0)
    {
      int mininter = 60
      if (MinInterRep)
        mininter = MinInterRep as int
      int maxinter = 90
      if (MaxInterRep)
        maxinter = MaxInterRep as int    
      int inSecs = Math.abs( new Random().nextInt() % (maxinter - mininter)) + mininter 
      runIn(inSecs, "refreshPower")
    }
}
