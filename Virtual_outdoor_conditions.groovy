/**
 *  Source: https://github.com/sacua/hubitat/blob/main/Virtual_outdoor_conditions.groovy
 *
 *  This virtual device only work in Canada since the temperature and the humidity is taken from Environnement Canada
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

metadata {
    definition (name: "Virtual outdoor conditions", namespace: "sacua", author: "Samuel Cuerrier Auclair") {
        capability "TemperatureMeasurement"
        capability "RelativeHumidityMeasurement"
        
        command "updated"
        
    }
    preferences {
        input name: "logEnable", type: "bool", title: "Enable debug logging", defaultValue: true
        input name: "txtEnable", type: "bool", title: "Enable descriptionText logging", defaultValue: true
        input (name: "PollingInt", type: "number", title: "Polling interval in minute (1..120)", submitOnChange: true, required: true, range: "1..120", defaultValue: 5)
    }
}

def logsOff(){
    log.warn "debug logging disabled..."
    device.updateSetting("logEnable",[value:"false",type:"bool"])
}

def updated() {
    log.warn "installed..."
    Measurement()
    schedule("1.5 0/" + PollingInt +" * * * ?", Measurement)
}

def parse(String description) {
}

def Measurement() {
    float outdoorTemp;
    float outdoorHumidity;

    try
    {
      def params = [
        uri: "https://app.weather.gc.ca/v1/fr/Location/" + location.latitude + "," + location.longitude]

      httpGet(params) { resp ->
        if (resp.success)
        {
          if (resp.data)
            outdoorTemp = resp.data[0].observation.temperature.metricUnrounded.toFloat()
            outdoorHumidity = resp.data[0].observation.humidity.toFloat()
        }
      }
      if (getTemperatureScale() == 'F') {    
        outdoorTemp = fahrenheitToCelsius(outdoorTemp).toDouble()
      }
      
      def descriptionText = "The outside temp is ${outdoorTemp}"
      if (txtEnable) log.info "${descriptionText}"
         sendEvent(name: "temperature", value: outdoorTemp, descriptionText: descriptionText)
      
      descriptionText = "The humidity is ${outdoorHumidity}"
      if (txtEnable) log.info "${descriptionText}"
         sendEvent(name: "humidity", value: outdoorHumidity, descriptionText: descriptionText)
    }
    catch (Exception e1)
    {
      log.error "EC - Call failed: ${e1.message}"
    }
}
