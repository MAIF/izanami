# Sample application using izanami

This app is a springboot and react application using izanami for feature flipping, A/B testing et shared configuration.  

## Run application 

### Build javascript 
```bash
cd javascript 
yarn install 
yarn build 
```
or In dev mode 

```bash
cd javascript 
yarn install 
yarn start 
```

### Run springboot app 

In the root folder 

```bash
sbt 'project example-spring' 'run'  
```

or in dev mode 

add profile dev in `src/main/resources/application.yml` : 

```yaml
spring:
  profiles:
    active:
      - izanamiProd
      - dev

``` 

Run app with the watch mode (the app is restarted on changes)

In the root folder

```bash
sbt 'project example-spring' '~reStart'
```


## Izanami 

This app show different features provided by izanami using the java client and the react client.

## Using izanami on the server side  

The java client is asynchronous by default. 
To use it in a springboot application you need to define a dispatcher that use a thread instead of using the akk default dispatcher. 

in the `src/main/resources/application.conf`

```hocon

izanami-example.blocking-io-dispatcher {
  type = Dispatcher
  executor = "thread-pool-executor"
  thread-pool-executor {
    fixed-pool-size = 32
  }
  throughput = 1
}
```
 
And then in the `src/main/java/izanami/example/Application.java`

```java 
@Bean
@Autowired
IzanamiClient izanamiClient(ActorSystem actorSystem) {
    String host = environment.getProperty("izanami.host");
    String clientId = environment.getProperty("izanami.clientId");
    String clientSecret = environment.getProperty("izanami.clientSecret");
    LOGGER.info("Creating izanami client with host {}, client id {}", host, clientId);
    return IzanamiClient.client(
                actorSystem,
                ClientConfig
                    .create(host)
                    .withClientId(clientId)
                    .withClientSecret(clientSecret)                   
                    .withDispatcher("izanami-example.blocking-io-dispatcher") // Here we reference the dispatcher 
                    .sseBackend()
            );
}
```

Now we can create the feature, experiment and config clients. 

For each clients we can define a fallback configuration used if the izanami server is not up. 

```yaml
  fallback:
    features: >
      [
        { "id": "izanami:example:emailNotifications", "enabled": false },
        { "id": "izanami:example:deleteAll", "enabled": false }
      ]
    configs: >
      [
        { "id": "izanami:example:config", "value": { "emailProvider": "test" } }
      ]
    experiments: >
      [
        {
          "id": "izanami:example:button",
          "name": "Test button",
          "description": "Test button",
          "enabled": true,
          "variant": {
            "id": "A",
            "name": "Variant A",
            "description": "Variant A"
          }
        }
      ]
```

And then the clients : 

```java 

@Bean
@Autowired
FeatureClient featureClient(IzanamiClient izanamiClient, Environment environment) {
    return izanamiClient.featureClient(
            // Define the strategy 
            Strategies.smartCacheWithSseStrategy("izanami:example:*"),
            // Define the fallback             
            Features.parseJson(environment.getProperty("izanami.fallback.features"))
    );
}

```

When we create the clients we have to define a strategy. We can choose 
 * dev : use only the fallback config 
 * fetch : fetch the server for each request 
 * fetch and cache : keep a cache with a TTL
 * smart cache with polling : keep a part of the data in memory and refresh the data asynchronously by polling the server  
 * smart cache with sse : keep a part of the data in memory and refresh the data listening server sent events
 
For the smart cache, we need to define a pattern for a subset of izanami features or configs. 

### Enable or disable a functionality 

In the dodo app, the functionality "delete all the items that are done" is feature flippable. 

We can control this in the `src/main/java/izanami/example/controller/TodoListController.java`. 

```java 
@DeleteMapping("/{name}/items")
public ResponseEntity deleteTodos(@PathVariable("name") String name, @RequestParam("done")  Boolean done) {
    return featureClient.featureOrElse(
            // The feature id 
            "izanami:example:deleteAll",
            // If the feature is enabled : 
            () -> {
                this.todoLists.get(name).forEach(l -> {
                    if (l.subscriptions != null) {
                        String message = MessageFormat.format("The list {0} was deleted", name);
                        notificationService.sendNotification(l.subscriptions, message);
                    }
                });
                this.items.deleteAllItems(name, done);
                return ResponseEntity.noContent().build();
            },
            // If the feature is disabled, we return a 400 code :
            () ->
                    ResponseEntity.badRequest().build()
    // The client return a Future, we block to get the value 
    ).get();
}

``` 

Another way to use izanami is by registering callback to get notified when there is an update on the server :  

/!\ : This functionality depends on the choosen strategy. 

```java
Registration registration = this.featureClient.onFeatureChanged(ACTIVATION_FEATURE_NAME, feature -> {
    if (feature.enabled()) {
        LOGGER.info("Email notification enabled");
        this.startNotification();
    } else {
        LOGGER.info("Email notification disabled");
        this.stopNotification();
    }
});
// Stop listening : 
registration.close();
```

## Using izanami on the client side

To use izanami on the client side, you need to proxify the http calls. Izanami is secured by api keys and you don't to leak this keys on the client. 

To do that, the java client provide a proxy, here is an example of this proxy using a springboot controller : 

```java

@RestController
@RequestMapping("/api/izanami")
public class IzanamiProxyController {

    private final Proxy proxy;

    @Autowired
    public IzanamiProxyController(Proxy proxy) {
        this.proxy = proxy;
    }


    @GetMapping()
    public CompletionStage<ResponseEntity<String>> proxy(
            @CookieValue(value = "clientId", required = false) String clientId) {

        Option<JsObject> context = Option.of(clientId).map(id -> Json.obj($("clientId", id)));

        return proxy.statusAndStringResponse(context, Option.of(clientId))
                .map(resp ->
                        new ResponseEntity<>(resp._2, HttpStatus.valueOf(resp._1))
                ).toCompletableFuture();
    }

    @PostMapping()
    public CompletionStage<ResponseEntity<String>> proxyWithContext(
            @CookieValue(value = "clientId", required = false) String clientId,
            @RequestBody String context
    ) {
        return proxy.statusAndStringResponse(Option.some(Json.parse(context).asObject()), Option.of(clientId))
                .map(resp ->
                        new ResponseEntity<>(resp._2, HttpStatus.valueOf(resp._1))
                ).toCompletableFuture();
    }

    @PostMapping("/experiments/won")
    public CompletionStage<ResponseEntity<String>> markWon(
            @RequestParam(value = "experiment") String id,
            @CookieValue(value = "clientId", required = false) String clientId) {

        return proxy.markVariantWonStringResponse(id, clientId)
                .map(resp ->
                        new ResponseEntity<>(resp._2, HttpStatus.valueOf(resp._1))
                ).toCompletableFuture();

    }

    @PostMapping("/experiments/displayed")
    public CompletionStage<ResponseEntity<String>> markDisplayed(
            @RequestParam(value = "experiment") String id,
            @CookieValue(value = "clientId", required = false) String clientId) {

        return proxy.markVariantDisplayedStringResponse(id, clientId)
                .map(resp ->
                        new ResponseEntity<>(resp._2, HttpStatus.valueOf(resp._1))
                ).toCompletableFuture();


    }
}
```

And then we can use the react izanami client with path exposed by the spring controller : 

First some import
 
```jsx harmony
import {IzanamiProvider, Feature, Enabled, Disabled, Experiment, Variant, Api as IzanamiApi} from 'izanami';
```

```jsx harmony
<IzanamiProvider fetchFrom="/api/izanami">
    <Router basename="/">
        <Switch>
          <Route path="/login" component={Login}/>
          <PrivateRoute path="/" component={MainApp}/>
        </Switch>
    </Router>
  </IzanamiProvider>
```
The json tree for features and experiments is fetch when the app is loaded. 

We can trigger refresh using the Api class. For example during router transition : 

```jsx harmony
componentWillReceiveProps(nextProps) {
    // will be true
    const locationChanged = nextProps.location !== this.props.location;
    if (locationChanged) {
      //Reload izanami data on route change
      IzanamiApi.izanamiReload("/api/izanami");
    }
}
``` 

To switch code depending on a feature : 

```jsx harmony
<Feature path="izanami.example.deleteAll">
  <Enabled>
    <tr>
      <td></td>
      <td></td>
      <td></td>
      <td><button type="button"  className="btn btn-sm btn-default" onClick={this.deleteAll}>Delete done items</button></td>
    </tr>
  </Enabled>
  <Disabled>
    <tr></tr>
  </Disabled>
</Feature>
```

To use A/B testing : 
```jsx harmony
{this.state.todoLists.map(l =>
    <tr key={`td-${l.name}`}>
      <td><Link to={`/todos/${l.name}`} >{l.name}</Link></td>
      <td>{l.user}</td>
      <td>
        <Experiment path={"izanami:example:button"} notifyDisplay="/api/izanami/experiments/displayed" >
          <Variant id={"A"}>
            <Link to={`/todos/${l.name}`} onClick={this.onTodosClick} className="btn btn-sm btn-default"><i className="fa fa-eye" aria-hidden="true" /></Link>
            <button className="btn btn-sm btn-default" onClick={this.removeTodoList(l.name)}><i className="glyphicon glyphicon-trash" /></button>
          </Variant>
          <Variant id={"B"}>
            <Link to={`/todos/${l.name}`} onClick={this.onTodosClick} className="btn btn-sm btn-primary"><i className="glyphicon glyphicon-pencil" /></Link>
            <button className="btn btn-sm btn-primary" onClick={this.removeTodoList(l.name)}><i className="glyphicon glyphicon-trash" /></button>
          </Variant>
        </Experiment>
      </td>
    </tr>
)}
```
Is that case, An http POST is done on `/api/izanami/experiments/displayed` when a variant is displayed.

We need to generate an events when a variant won. 
For that we do an http POST on `/api/izanami/experiments/won` when the client click on the link or the button using this function :

```javascript
export function notifyWon(key) {
  return fetch(`/api/izanami/experiments/won?experiment=${key}`, {
    method: 'POST',
    credentials: 'include',
    headers: {
      'Accept': 'application/json',
      'Content-Type': 'application/json'
    }
  });
}
``` 

A variant (A or B) is associated to a client. 
In this app we use the user email as an id to get the right variant. 
The clientId is stored in a cookie used by the proxy to communicate with the izanami server. 

```java

@PostMapping("/experiments/displayed")
public CompletionStage<ResponseEntity<String>> markDisplayed(
        @RequestParam(value = "experiment") String id,
        // We get the clientId cookie 
        @CookieValue(value = "clientId", required = false) String clientId) {

    // We use the cookie value to talk with izanami : 
    return proxy.markVariantDisplayedStringResponse(id, clientId)
            .map(resp ->
                    new ResponseEntity<>(resp._2, HttpStatus.valueOf(resp._1))
            ).toCompletableFuture();


}
```


    


