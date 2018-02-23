# Angular client

`angular-izanami` is a simple set of Angular components to leverage the power of Opun Izanami feature flipping and experiments.

In order to use the client you need to proxify Izanami to avoid leaking the credentials to the client side. 

You can use the @see[jvm proxy](./jvm.md#exposing-izanami-with-a-proxy), the @see[node proxy](./node.md) or write your own proxy. 

## Install the client 

```bash
npm install angular-izanami
```

## Import

```js
import {IzanamiModule} from "angular-izanami";
```

Import module into your ngModule

```js
 imports: [
    //...,
    IzanamiModule
  ]
```

## Izanami provider

You can wrap your application in the izanami provider and let izanami fetch data from the proxy you have exposed:

```angular2html
<app-izanami-provider [children]="izanamiProviderChildren" fetchFrom="/api/izanami">
  <ng-template #izanamiProviderChildren>
  <!-- your app here -->
  </ng-template>
</app-izanami-provider>
```

You can pass fallbacks in the case of the server is not up: 

```angular2html
<app-izanami-provider [children]="izanamiProviderChildren" fetchFrom="/api/izanami" [featuresFallback]="{my: {feature: {active: true}}}" [experimentsFallback]="{my:{experiment:{variant: 'B'}}}">
  <ng-template #izanamiProviderChildren>
  <!-- your app here -->
  </ng-template>
</app-izanami-provider>
```

You can also pass features and experiments as props if you don't want the provider to fetch datas: 
```angular2html
<app-izanami-provider [children]="izanamiProviderChildren" [features]="{my: {feature: {active: true}}}" [experiments]="{my:{experiment:{variant: 'B'}}}">
  <ng-template #izanamiProviderChildren>
  <!-- your app here -->
  </ng-template>
</app-izanami-provider>
```

You can trigger manual reload using the Api class. For example during router transition : 

```js
 constructor(private izanamiProvider: IzanamiProviderComponent) {
 }
 
 ngOnInit() {
  this.izanamiProvider.izanamiReload();
 }
```

## Feature flipping

Once you the provider configured, you can switch code depending on a feature : 

```angular2html
<!-- Active only if feature with path "izanami:example:deleteAll" is active -->
<ng-template appFeature path="izanami:example:deleteAll">
    <tr>
      <td></td>
      <td></td>
      <td></td>
      <td><button type="button"  class="btn btn-sm btn-default" (click)={deleteAll}>Delete done items</button></td>
    </tr>
</ng-template>

<!-- Active only if feature with path "izanami:example:deleteAll" is non-active -->
<ng-template appFeature path="izanami:example:deleteAll" [activeIfEnabled]="false">
    <tr></tr>
</ng-template>
```


## Experiments (A/B testing) 

Once you the provider configured, you can displayed a specific variant for an experiment :

```angular2html
<ng-template appExperiment path="izanami:example:button"
                         notifyDisplayPath="/api/izanami/experiments/displayed" variant="A">
  <a routerLink="/todos/{{todo.name}}" (click)="onTodosClick" class="btn btn-sm btn-default">
    <i class="fa fa-eye"></i>
  </a>
  <button class="btn btn-sm btn-default" (click)="removeTodoList(todo.name)"><i class="glyphicon glyphicon-trash" /></button>
</ng-template>

<ng-template appExperiment path="izanami:example:button"
             notifyDisplayPath="/api/izanami/experiments/displayed" variant="B">
  <a routerLink="/todos/{{todo.name}}" (click)="onTodosClick" class="btn btn-sm btn-default">
    <i class="glyphicon glyphicon-pencil"></i>
  </a>
  <button class="btn btn-sm btn-primary" (click)="removeTodoList(todo.name)"><i class="glyphicon glyphicon-trash" /></button>
</ng-template>
```

If specified, the `notifyDisplayPath` field will call the registered url to notify Izanami that the current variant is displayed. 

You have to call yourself Izanami when a variant won because the react client can't decide this for you :).

In this example, the variant won if the client click on the button. 
For that we will do an http POST on `/api/izanami/experiments/won` when the client click on the link using this function :

```javascript
  notifyWon(key: string) {
    const headers: HttpHeaders = new HttpHeaders();
    headers.set('Accept', 'application/json');
    headers.set('Content-Type', 'application/json');
    
    const url = `/api/izanami/experiments/won?experiment=${key}`;
    return this.httpClient.post(url, {}, {headers,withCredentials: true});
  }
```

and then when the client click on the link: 

```javascript
onTodosClick() {
  this.appService.notifyWon("izanami:example:button");
}
```
