# React client

`react-izanami` is a simple set of React components to leverage the power of Izanami feature flipping and experiments.

In order to use the client you need to proxify Izanami to avoid leaking the credentials to the client side. 

You can use the @see[jvm proxy](./jvm.md#exposing-izanami-with-a-proxy), the @see[node proxy](./node.md) or write your own proxy. 

## Install the client 

```bash
npm install react-izanami
```

## Import 

 
```jsx harmony
import {IzanamiProvider, Feature, Enabled, Disabled, Experiment, Variant, Api as IzanamiApi} from 'react-izanami';
```

## Izanami provider 

You can wrap your application in the izanami provider and let izanami fetch data from the proxy you have exposed:

```jsx harmony
  <IzanamiProvider fetchFrom="/api/izanami">
    {/* your app */}    
  </IzanamiProvider>
```

Or

```jsx harmony
  <IzanamiProvider
          id = "provider1"
          fetchFrom={() =>
              fetch("/api/izanami", {
                  method: 'GET',
                  credentials: 'include'                
              })
          }>
    {/* your app */}    
  </IzanamiProvider>
```

If you register a function, you need an id because you can register multiple provider. 



You can pass fallbacks in the case of the server is not up: 

```jsx harmony
<IzanamiProvider fetchFrom="/api/izanami" featuresFallback={{my: {feature: {active: true}}}} experimentsFallback={{my:{experiment:{variant: 'B'}}}} >
{/* ... */}    
</IzanamiProvider>
```


You can also pass features and experiments as props if you don't want the provider to fetch datas: 

```jsx harmony
<IzanamiProvider features={{my: {feature: {active: true}}}} experiments={{my:{experiment:{variant: 'B'}}}} >
{/* ... */}
</IzanamiProvider>
```

You can trigger manual reload using the Api class. For example during router transition : 

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
Or if an id was used 

```jsx harmony
componentWillReceiveProps(nextProps) {
    // will be true
    const locationChanged = nextProps.location !== this.props.location;
    if (locationChanged) {
      //Reload izanami data on route change
      IzanamiApi.izanamiReload("provider1");
    }
}
``` 

## Feature flipping

Once you the provider configured, you can switch code depending on a feature : 

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

## Experiments (A/B testing) 

Once you the provider configured, you can displayed a specific variant for an experiment :

```jsx harmony
{this.state.todoLists.map(l =>
    <tr key={`td-${l.name}`}>
      <td><Link to={`/todos/${l.name}`} >{l.name}</Link></td>
      <td>{l.user}</td>
      <td>
        <Experiment path={"izanami:example:button"} notifyDisplay="/api/izanami/experiments/displayed" >
          <Variant id={"A"}>
            <Link to={`/todos/${l.name}`} onClick={this.onTodosClick} className="btn btn-sm btn-default"><i className="fas fa-eye" aria-hidden="true" /></Link>
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
If specified, the `notifyDisplay` field will call the registered url to notify Izanami that the current variant is displayed. 

You have to call yourself Izanami when a variant won because the react client can't decide this for you :).

In this example, the variant won if the client click on the button. 
For that we will do an http POST on `/api/izanami/experiments/won` when the client click on the link using this function :

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

and then when the client click on the link: 
```javascript
onTodosClick = () => {
  Service.notifyWon("izanami:example:button");
};
```
 
  

