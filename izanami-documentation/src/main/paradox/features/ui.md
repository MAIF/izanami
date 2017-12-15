# The UI

You can access to the features clicking "features" on the left menu. 

<img src="../img/features/full.png" width="100%" />

You can then 

* Search using a pattern on feature key 
* add a feature 
* update a feature 
* delete a feature

If you're admin, you can also
 
* Download features 
* Upload features 

## Edit a feature  

When you create a feature, you have to select a strategy. You can choose between NO_STRATEGY, RELEASE_DATE, SCRIPT or GLOBAL_SCRIPT

### NO_STRATEGY 

this is the simpler one, the feature can be active or inactive

<img src="../img/features/no_strategy.png" width="60%" />
  
### RELEASE_DATE 

this kind of strategy allow you to enable a feature on a date value in addition to the active boolean.  

<img src="../img/features/release_date.png" width="60%" />

### SCRIPT 

this kind of strategy allow you to enable a feature using a script execution. On json context should be posted to evaluate if the feature is active or not. 

In this example, the feature is active if the user send in the context is `ragnar.lodbrock@gmail.com` : 

<img src="../img/features/script.png" width="80%" />

### GLOBAL SCRIPT 

Global script strategy is the same as script except that the script are shared between features. 

## Evaluate a feature 

You can evaluate if a feature is active or with the explorer screen. 

<img src="../img/features/explorer.png" width="80%" />

In this example, we have specified a pattern `*:script` to filter the feature. 

We have also specified a context to test the feature. The tested feature is a feature with a "script" strategy so we want to be sure that the script is correct.   


