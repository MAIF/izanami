


export const javascriptDefaultScript = `/**
 * context:  a JSON object containing app specific value 
 *           to evaluate the state of the feature
 * enabled:  a callback to mark the feature as active 
 *           for this request
 * disabled: a callback to mark the feature as inactive 
 *           for this request 
 * http:     a http client
 */ 
function enabled(context, enabled, disabled, http) {
  if (context.user === 'john.doe@gmail.com') {
    return enabled();
  }
  return disabled();
}`;

export const scalaDefaultScript = `/**
 * context:  a play JSON object containing app specific value
 *           to evaluate the state of the feature
 * enabled:  a callback to mark the feature as active
 *           for this request
 * disabled: a callback to mark the feature as inactive
 *           for this request
 * http:     the play http client
 */
 def enabled(context: play.api.libs.json.JsObject,
             enabled: () => Unit,
             disabled: () => Unit,
             http: play.api.libs.ws.WSClient)(implicit ec: ExecutionContext): Unit = {
             
    if ( (context \\ "user").asOpt[String].contains("john.doe@gmail.com")) {
      enabled()
    } else {
      disabled()
    }
    
}`;



export const kotlinDefaultScript = `/**
 * context:  a jackson JSON object containing app specific value
 *           to evaluate the state of the feature
 * enabled:  a callback to mark the feature as active
 *           for this request
 * disabled: a callback to mark the feature as inactive
 *           for this request
 * http:     a playframework http client
 */
fun enabled(context: JsonNode, enabled: () -> Unit, disabled: () -> Unit, wsClient: WSClient) {             
    if ( context.get("user")?.asText()?.contains("john.doe@gmail.com") ?: false) {
      enabled()
    } else {
      disabled()
    }
}`;