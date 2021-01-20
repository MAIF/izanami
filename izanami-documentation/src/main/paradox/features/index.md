# Feature flipping  


@@@ index

 * [UI](ui.md)
 * [Scripts](scripts.md)
 * [APIs](api.md)

@@@ 

@@toc { depth=3 }

One of the main feature of Izanami is feature flipping. Feature flipping allow you to activate or passivate a feature in real time. 

The clients can check if a feature is active using the REST API. 

When you create a feature, you have to select a strategy. You can choose between NO_STRATEGY, RELEASE_DATE, DATE_RANGE, PERCENTAGE, SCRIPT or GLOBAL_SCRIPT

## NO_STRATEGY 

this is the simpler one, the feature can be active or inactive

<img src="../img/features/no_strategy.png" width="60%" />
  
## RELEASE_DATE 

this kind of strategy allow you to enable a feature on a date value in addition to the active boolean.  

<img src="../img/features/release_date.png" width="60%" />

  
## DATE_RANGE 

this kind of strategy allow you to enable a feature on a range of dates in addition to the active boolean.  

<img src="../img/features/date_range.png" width="60%" />
  
## HOUR_RANGE 

this kind of strategy allow you to enable a feature on a range of hour for a day in addition to the active boolean.  

<img src="../img/features/hour_range.png" width="60%" />

## PERCENTAGE

this kind of strategy allow you to enable a feature for a percentage of clients. In this strategy, the client need to send a context with an `id` field in order to calculate if the feature is enabled or not. 

<img src="../img/features/percentage.png" width="60%" />

## SCRIPT 

this kind of strategy allow you to enable a feature using a script execution. On json context should be posted to evaluate if the feature is active or not. 

In this example, the feature is active if the user sent in the context is `ragnar.lodbrock@gmail.com` : 

<img src="../img/features/script.png" width="80%" />

You can go further following this @ref[link](./scripts.md).


## GLOBAL SCRIPT

Global script strategy is the same as script except that the script are shared between features.

You can find more details about global scripts @ref[on this page](../ui.md#create-or-update-a-script).


## CUSTOMERS LIST

This kind of strategy allow you to enable a feature for set of customers. A use case can be to enable a feature for beta testers. In this strategy, the client need to send a context with an `id` field in order to check if the feature is enabled or not.

<img src="../img/features/customers_list.png" width="60%" />

