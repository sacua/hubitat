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
  definition(name: "TH112xZB Sinope Thermostat EnergyMerter", namespace: "sacua", author: "Samuel Cuerrier Auclair")
  {
    capability "Configuration"
    capability "Thermostat"
    capability "Temperature Measurement"
    capability "Refresh"
    capability "Lock"
    capability "PowerMeter"
    capability "EnergyMeter"
    capability "Notification" //For outside temperature

    attribute "heatingDemand", "number"
    attribute "cost", "number"

    command "reset"
    command "displayOn"
    command "displayOff"
    command "refreshTemp" //To refresh only the temperature reading

    fingerprint manufacturer: "Sinope Technologies", model: "TH1123ZB", deviceJoinName: "Sinope TH1123ZB Thermostat", inClusters: "0000,0003,0004,0005,0201,0204,0402,0B04,0B05,FF01", outClusters: "0019,FF01"
    fingerprint manufacturer: "Sinope Technologies", model: "TH1124ZB", deviceJoinName: "Sinope TH1124ZB Thermostat", inClusters: "0000,0003,0004,0005,0201,0204,0402,0B04,0B05,FF01", outClusters: "0019,FF01"
  }
}

preferences
{
  input("prefDisplayBacklight", "enum", title: "Backlight setting", options: ["On Demand", "Sensing"], defaultValue: "Sensing")
  input("prefDisplayOutdoorTemp", "bool", title: "Display outdoor temperature", defaultValue: true)
  input("prefKeyLock", "bool", title: "Keylock", description: "Set to true to enable the lock on the thermostat")

  input (name: "energyPrice", type: "number", title: "\$/kWh Cost:", description: "Electric Cost per Kwh in cent", range: "0..*", defaultValue: 9.38)

  input (name: "EnergyResetInterv", type: "enum", title: "Reset interval for the energy tracking", options: ["Daily", "Weekly", "Monthly", "Yearly", "Never"], defaultValue: "Daily")
  input (name: "MinInterRep", type: "number", title: "Minimum interval reporting time for energy calculation, 30..600", range: "30..600", defaultValue: 60)
  input (name: "MaxInterRep", type: "number", title: "Maximum interval reporting time for energy calculation, 60..900", range: "60..900", defaultValue: 90)
  input("trace", "bool", title: "Trace", description: "Enable tracing")
}

def configure()
{
  if (settings.trace)
    log.trace "TH1123ZB >> configure()"

  if (state.resetTime == null)
    reset()

  try
    {
      unschedule()
    }
  catch (e)
    {
    }
    
  schedule("23 1 2 * * ? *", refreshTime)
  
  if (EnergyResetInterv == "Daily")
      schedule("0 0 0 * * ? *", reset) 
  else if (EnergyResetInterv == "Weekly")
      schedule("0 0 0 ? * 1 *", reset)
  else if (EnergyResetInterv == "Monthly")
      schedule("0 0 0 1 * ? *", reset)
  else if (EnergyResetInterv == "Yearly")
      schedule("0 0 0 1 1 ? *", reset)

  setParams()

  def cmds = []

  cmds += zigbee.configureReporting(0x0201, 0x0000, DataType.INT16, 19, 301, 50)      // local temperature
  cmds += zigbee.configureReporting(0x0201, 0x0008, DataType.UINT8, 4, 300, 10)       // heating demand
  cmds += zigbee.configureReporting(0x0201, 0x0012, DataType.INT16, 15, 302, 40)      // occupied heating setpoint

  return cmds + refresh()
}

def powerReport()
{
  def cmds = []
  
  cmds += zigbee.readAttribute(0x0B04, 0x050B)  //Read thermostat Active power  descMap.cluster == "0B04" && descMap.attrId == "050B"
    
  if (cmds)
    fireCommand(cmds)
}

def refreshTemp()
{
  def cmds = []
  
  cmds += zigbee.readAttribute(0x0201, 0x0000)  //Rd thermostat Local temperature
    
  if (cmds)
    fireCommand(cmds)
}

def updated()
{
  if (settings.trace)
    log.trace "TH1123ZB >> updated()"

  if (!state.updatedLastRanAt || now() >= state.updatedLastRanAt + 5000)
  {
    state.updatedLastRanAt = now()

    if (settings.trace)
      log.trace "TH1123ZB >> updated() => Device is now updated"

    setParams()

  try
    {
      unschedule()
    }
  catch (e)
    {
    }
  schedule("23 1 2 * * ? *", refreshTime)
  
  if (EnergyResetInterv == "Daily")
      schedule("0 0 0 * * ? *", reset) 
  else if (EnergyResetInterv == "Weekly")
      schedule("0 0 0 ? * 1 *", reset)
  else if (EnergyResetInterv == "Monthly")
      schedule("0 0 0 1 * ? *", reset)
  else if (EnergyResetInterv == "Yearly")
      schedule("0 0 0 1 1 ? *", reset)
      
    def cmds = refresh()

    if (cmds)
      fireCommand(cmds)
  }
}

void setParams()
{
   
  def cmds = []

  // Backlight
  if (prefDisplayBacklight == "On Demand")
    cmds += zigbee.writeAttribute(0x0201, 0x0402, DataType.ENUM8, 0x0000)
  else
    cmds += zigbee.writeAttribute(0x0201, 0x0402, DataType.ENUM8, 0x0001)

  // Lock / Unlock
  if (!prefKeyLock)
    unlock()
  else
    lock()

  // °C or °F
  if (state?.scale == 'C')
    cmds += zigbee.writeAttribute(0x0204, 0x0000, DataType.ENUM8, 0)  // °C on thermostat display
  else
    cmds += zigbee.writeAttribute(0x0204, 0x0000, DataType.ENUM8, 1)  // °F on thermostat display

  if (cmds)
    fireCommand(cmds)
}

def reset()
{
  state.energyValue = 0.0
  state.costValue = 0.0
  state.time = now()
  sendEvent(name: "energy", value: state.energyValue)
  sendEvent(name: "cost", value: state.costValue)
  state.resetTime = new Date().format('MM/dd/yy hh:mm a', location.timeZone)
}

def displayOn()
{
  def cmds = []

  cmds += zigbee.writeAttribute(0x0201, 0x0402, DataType.ENUM8, 0x0001)

  if (cmds)
    fireCommand(cmds)
}

def displayOff()
{
  def cmds = []

  cmds += zigbee.writeAttribute(0x0201, 0x0402, DataType.ENUM8, 0x0000)

  if (cmds)
    fireCommand(cmds)
}

def parse(String description)
{
  def result = []

  try
  {
    def scale = getTemperatureScale()
    state?.scale = scale
    def cluster = zigbee.parse(description)

    if (description?.startsWith("read attr -"))
    {
      def descMap = zigbee.parseDescriptionAsMap(description)

      result += createCustomMap(descMap, cluster, description)

      if (descMap.additionalAttrs)
      {
        def mapAdditionnalAttrs = descMap.additionalAttrs

        mapAdditionnalAttrs.each { add ->
          add.cluster = descMap.cluster
          result += createCustomMap(add, cluster, description)
        }
      }
    }
    else if (!description?.startsWith("catchall:") && settings.trace)
      log.trace "TH1123ZB >> parse(description) ==> " + description
  }
  catch (Exception e)
  {
    log.error "parse : ${e.getStackTrace()}"
  }

  return result
}

def createCustomMap(descMap, cluster, description)
{
  def result = []
  def map = [: ]

  if (descMap.cluster == "0201" && descMap.attrId == "0000")
  {
    map.name = "temperature"
    map.value = getTemperatureValue(descMap.value)
  }
  else if (descMap.cluster == "0201" && descMap.attrId == "0008")
  {
    map.name = "heatingDemand"
    map.value = getHeatingDemand(descMap.value)
    map.scale = "%"

    // Operating State
    def operatingState = (map.value.toInteger() < 5) ? "idle" : "heating"
    def mapOperatingState = [: ]

    mapOperatingState.name = "thermostatOperatingState"
    mapOperatingState.value = operatingState

    def isChange = isStateChange(device, mapOperatingState.name, mapOperatingState.value.toString())
    mapOperatingState.displayed = isChange

    result += createEvent(mapOperatingState)

    // Heating changed .. Request powerReport in 5 seconds
    runIn(5, "powerReport")
  }
  else if (descMap.cluster == "0201" && descMap.attrId == "0012")
  {
    map.name = "heatingSetpoint"
    map.value = getTemperatureValue(descMap.value)//, true)
  }
  else if (descMap.cluster == "0201" && descMap.attrId == "001C")
  {
    map.name = "thermostatMode"
    map.value = getModeMap()[descMap.value]
  }
  else if (descMap.cluster == "0204" && descMap.attrId == "0001")
  {
    map.name = "lock"
    map.value = getLockMap()[descMap.value]
  }
  else if (descMap.cluster == "0B04" && descMap.attrId == "050B")
  {
    def intVal = Integer.parseInt(descMap.value, 16)

    def powerValue = intVal

    if (isStateChange(device, "power", powerValue.toString()) || state.powerValue != powerValue)
      sendEvent(name: "power", value: powerValue, unit: "W")

    if (state.time == null)
      state.time = now()

    def time = (now() - state.time) / 3600000 / 1000
    state.time = now()

    def energyValue = safeToDec((state.energyValue != null ? state.energyValue : 0) + (time * state.powerValue))
    float EnergyValue = roundTwoPlaces(energyValue)
    state.powerValue = powerValue
      
    if (isStateChange(device, "energy", energyValue.toString()) || state.energyValue != energyValue)
      sendEvent(name: "energy", value: EnergyValue, unit: "kWh")

    state.energyValue = energyValue

    float localCostPerKwh = 9.38
    if (energyPrice)
      localCostPerKwh = energyPrice as float

    def costValue = roundTwoPlaces(energyValue * localCostPerKwh / 100)

    if (isStateChange(device, "cost", costValue.toString()) || state.costValue != costValue)
      sendEvent(name: "cost", value: costValue, unit: "\$")

    state.costValue = costValue

      // Received powerValue .. Request powerReport in (between MinInterRep and MaxInterRep - Define in preference¸s) seconds if powerValue is greater than 0
    if (powerValue > 0)
    {
      int mininter = 60
      if (MinInterRep)
        mininter = MinInterRep as int
      int maxinter = 90
      if (MaxInterRep)
        maxinter = MaxInterRep as int    
      int inSecs = Math.abs( new Random().nextInt() % (maxinter - mininter)) + mininter 
      runIn(inSecs, "powerReport")
    }

    // Operating State
    def operatingState = (powerValue < 1) ? "idle" : "heating"

    if (isStateChange(device, "thermostatOperatingState", operatingState))
      sendEvent(name: "thermostatOperatingState", value: operatingState)

    if (state.resetTime == null)
      state.resetTime = new Date().format('MM/dd/yy hh:mm a', location.timeZone)
	}
  else if (settings.trace)
    log.trace("TH1123ZB >> createCustomMap(descMap) ==> " + description)

  if (map)
  {
    def isChange = isStateChange(device, map.name, map.value.toString())
    map.displayed = isChange

    if ((map.name.equalsIgnoreCase("temperature")) || (map.name.equalsIgnoreCase("heatingSetpoint")))
      map.scale = getTemperatureScale()

    result += createEvent(map)
  }
  return result
}

def getTemperatureValue(value, doRounding = false)
{
  def scale = state?.scale

  if (value != null)
  {
    double celsius = (Integer.parseInt(value, 16) / 100).toDouble()

    if (scale == "C")
    {
      if (doRounding)
      {
        def tempValueString = String.format('%2.2f', celsius)

        if (tempValueString.matches(".*([.,][456])"))
          tempValueString = String.format('%2d.5', celsius.intValue())
        else if (tempValueString.matches(".*([.,][789])"))
        {
          celsius = celsius.intValue() + 1
          tempValueString = String.format('%2d.0', celsius.intValue())
        }
        else
          tempValueString = String.format('%2d.0', celsius.intValue())

        return tempValueString.toDouble().round(2)
      }
      else
        return celsius.round(2)
    }
    else
      return Math.round(celsiusToFahrenheit(celsius))
  }
}

def getHeatingDemand(value)
{
  if (value != null)
  {
    def demand = Integer.parseInt(value, 16)

    return demand.toString()
  }
}

def getModeMap()
{
  [
    "00": "off",
    "04": "heat"
  ]
}

def getLockMap()
{
  [
    "00": "unlocked ",
    "01": "locked ",
  ]
}

def unlock()
{
  if (settings.trace)
    log.trace "TH1123ZB >> unlock()"

  sendEvent(name: "lock", value: "unlocked")

  def cmds = []
  cmds += zigbee.writeAttribute(0x0204, 0x0001, DataType.ENUM8, 0x00)

  fireCommand(cmds)
}

def lock()
{
  if (settings.trace)
    log.trace "TH1123ZB >> lock()"

  sendEvent(name: "lock", value: "locked")

  def cmds = []
  cmds += zigbee.writeAttribute(0x0204, 0x0001, DataType.ENUM8, 0x01)

  fireCommand(cmds)
}

def refresh()
{
  if (settings.trace)
    log.trace "TH1123ZB >> refresh()"

  def cmds = []

  if (!state.updatedLastRanAt || now() >= state.updatedLastRanAt + 20000)
  {
    state.updatedLastRanAt = now()

    state?.scale = getTemperatureScale()

    cmds += zigbee.readAttribute(0x0201, 0x0000)  // Rd thermostat Local temperature
    cmds += zigbee.readAttribute(0x0204, 0x0001)  // Rd thermostat Keypad lock
    cmds += zigbee.readAttribute(0x0201, 0x0008)  // Rd thermostat PI heating demand
    cmds += zigbee.readAttribute(0x0201, 0x0012)  // Rd thermostat Occupied heating setpoint
    cmds += zigbee.readAttribute(0x0201, 0x001C)  // Rd thermostat System Mode

    refreshTime()
  }
  else if (settings.trace)
    log.trace "TH1123ZB >> refresh() --- Ran within last 20 seconds so aborting"

  return cmds
}

def deviceNotification(text) {
    //log.info "deviceNotification(${text})"
    double outdoorTemp = text.toDouble()
    def cmds = []

    if (settings.prefDisplayOutdoorTemp) {
        log.info "deviceNotification() : Received outdoor weather : ${text} : ${outdoorTemp}"
    
        //the value sent to the thermostat must be in C
        if (getTemperatureScale() == 'F') {    
            outdoorTemp = fahrenheitToCelsius(outdoorTemp).toDouble()
        }       
        
        int outdoorTempDevice = outdoorTemp*100
        cmds += zigbee.writeAttribute(0xFF01, 0x0011, 0x21, 10800)   //set the outdoor temperature timeout to 3 hours
        cmds += zigbee.writeAttribute(0xFF01, 0x0010, 0x29, outdoorTempDevice,[mfgCode: "0x119C"]) //set the outdoor temperature as integer
    
        // Submit zigbee commands    
        if (cmds)
            fireCommand(cmds)
    } else {
        log.info "deviceNotification() : Not setting any outdoor weather, since feature is disabled."  
    }
}

def refreshTime()
{
  if (settings.trace)
    log.trace "TH1123ZB >> refreshTime()"

  def cmds = []

  def thermostatDate = new Date();
  def thermostatTimeSec = thermostatDate.getTime() / 1000;
  def thermostatTimezoneOffsetSec = thermostatDate.getTimezoneOffset() * 60;
  def currentTimeToDisplay = Math.round(thermostatTimeSec - thermostatTimezoneOffsetSec - 946684800);

  cmds += zigbee.writeAttribute(0xFF01, 0x0020, DataType.UINT32, zigbee.convertHexToInt(hex(currentTimeToDisplay)), [mfgCode: "0x119C"])

  if (cmds)
    fireCommand(cmds)
}

def setHeatingSetpoint(degrees)
{
  def scale = getTemperatureScale()
  degrees = checkTemperature(degrees)
  def degreesDouble = degrees as Double
  String tempValueString

  if (scale == "C")
  tempValueString = String.format('%2.2f', degreesDouble)
  else
    tempValueString = String.format('%2d', degreesDouble.intValue())

  sendEvent(name: "heatingSetpoint", value: tempValueString, unit: scale)

  def celsius = (scale == "C") ? degreesDouble : (fahrenheitToCelsius(degreesDouble) as Double).round(2)
    
  def cmds = []
  cmds += zigbee.writeAttribute(0x0201, 0x12, DataType.INT16,  zigbee.convertHexToInt(hex(celsius * 100)))
  return cmds
}

def setCoolingSetpoint(JSON_OBJECT)
{
  log.info "TH1123ZB >> setCoolingSetpoint() is not available for this device"
}

def setThermostatFanMode(fanmode)
{
  log.info "TH1123ZB >> setThermostatFanMode() is not available for this device"
}


def setThermostatMode(String value) {
    log.info "setThermostatMode(${value})"
    def currentMode = device.currentState("thermostatMode")?.value
    def lastTriedMode = state.lastTriedMode ?: currentMode ?: "heat"
    def modeNumber;
    Integer setpointModeNumber;
    def modeToSendInString;
    switch (value) {
        case "heat":
        case "emergency heat":
        case "auto":
            return heat()
        
        case "eco":
        case "cool":
            return eco()
        
        default:
            return off()
    }
}

def eco() {
    log.info "eco()"
    
    def cmds = []
    cmds += zigbee.writeAttribute(0x0201, 0x001C, 0x30, 04, [:], 1000) // MODE
    cmds += zigbee.writeAttribute(0x0201, 0x401C, 0x30, 05, [mfgCode: "0x1185"]) // SETPOINT MODE    
    
    // Submit zigbee commands
    fireCommand(cmds)   
}

def auto() {
    log.info "auto(): mode is not available for this device. => Defaulting to heat mode instead."
    heat()
}

def cool() {
    log.info "cool(): mode is not available for this device. => Defaulting to eco mode instead."
    eco()
}

def emergencyHeat() {
    log.info "emergencyHeat(): mode is not available for this device. => Defaulting to heat mode instead."
    heat()
}

def heat() {
    log.info "heat(): mode set"
    
    def cmds = []
    cmds += zigbee.writeAttribute(0x0201, 0x001C, 0x30, 04, [:], 1000) // MODE
    cmds += zigbee.writeAttribute(0x0201, 0x401C, 0x30, 04, [mfgCode: "0x1185"]) // SETPOINT MODE
    
    // Submit zigbee commands
    fireCommand(cmds)
}

def off() {
    log.info "off(): mode set"
    
    def cmds = []
    cmds += zigbee.writeAttribute(0x0201, 0x001C, 0x30, 0)
    
    // Submit zigbee commands
    fireCommand(cmds)    
}

def fanAuto() {
    log.info "fanAuto(): mode is not available for this device"
}

def fanCirculate() {
    log.info "fanCirculate(): mode is not available for this device"
}

def fanOn() {
    log.info "fanOn(): mode is not available for this device"
}

private def checkTemperature(def number)
{
  def scale = getTemperatureScale()

  if (scale == 'F')
  {
    if (number < 41)
      number = 41
    else if (number > 86)
      number = 86
  }
  else //scale == 'C'
  {
    if (number < 5)
      number = 5
    else if (number > 30)
      number = 30
  }

  return number
}

private fireCommand(List commands)
{
  if (commands != null && commands.size() > 0)
  {
    if (settings.trace)
      log.trace("Executing commands:" + commands)

    for (String value : commands)
      sendHubCommand([value].collect {new hubitat.device.HubAction(it, hubitat.device.Protocol.ZIGBEE)})
  }
}

private hex(value)
{
  String hex = new BigInteger(Math.round(value).toString()).toString(16)

  return hex
}

private safeToDec(val, defaultVal=0)
{
  return "${val}"?.isBigDecimal() ? "${val}".toBigDecimal() : defaultVal
}

private roundTwoPlaces(val)
{
  return Math.round(safeToDec(val) * 100) / 100
}
