# Changelog 

## Version 1.5.3

A bug fixes and feature release 

Main features :  
* import now have options to replace or keep existing datas

Major fixes are 
 * fix bugs with autocreate option on the jvm client 
 * fix bugs on the UI 

https://github.com/MAIF/izanami/issues?q=is%3Aissue+milestone%3Av1.5.3

## Version 1.5.2

Main features :  
 * Better prometheus metrics 
 * Creating feature can be done without "enabled" field (set at false by default) 

https://github.com/MAIF/izanami/issues?q=is%3Aissue+milestone%3Av1.5.2

## Version 1.5.1

Main features :  
 * The features now have a description 

Major fixes are
 *  a regression on table naming with dynamo db

https://github.com/MAIF/izanami/issues?q=is%3Aissue+milestone%3Av1.5.1

## Version 1.5.0

Main features : 
 * autocreate option 
 * feature strategy for a range of hours 
 * event are enrished with the user that has done the action  
 * metrics improved 

This release comes with a big internal refactoring of the server 

https://github.com/MAIF/izanami/issues?q=is%3Aissue+milestone%3Av1.5.O

## Version 1.4.4

A bug fixes release 

Major fixes are 
 * Multiple patterns can be used for searching in the api 
 * Docker image doesn't respond to SIGTERM
 * Multiple exclusion for sucured endpoints 
 
https://github.com/MAIF/izanami/issues?q=is%3Aissue+milestone%3Av1.4.4
 
## Version 1.4.3

A bug fixes release 

Major fixes are 
 * Upgrade to playframework 2.7.1
 * Problem saving webhooks patterns 
 * Healthcheck failure with dynamo DB 

https://github.com/MAIF/izanami/issues?q=is%3Aissue+milestone%3Av1.4.3

## Version 1.4.2

A bug fixes release 

### Bugfix

The bug fixes are listed here : 

https://github.com/MAIF/izanami/issues?q=is%3Aissue+milestone%3Av1.4.2


## Version 1.4.0

### Postgresql supported as database 

Postgresql is now supported as database for Izanami. 

### Bugfix 

The bug fixes are listed here : 

https://github.com/MAIF/izanami/issues?q=is%3Aissue+milestone%3Av1.4.0


## Version 1.3.1

:warning: Version 1.3.0 was tagged but didn't complete. 

### New scripting language 

You can now use kotlin as scripting language with the script and global script strategy on feature flipping.

### Spring config documentation and sample

The documentation has been improved for spring config usage. 
The spring sample app has also been updated to use spring config with Izanami.

### Bug fix 

A/B testing can generate a lot of events. Before this version this event were all sent to the UI for the dashboard.   
This version fix this problem with event aggregation.  

## Version 1.2.0

### New scripting language 

You can now use scala as scripting language with the script and global script strategy on feature flipping 

This feature break the API so be sure to upgrade the client before the server. 

### Script debug 

You can now debug your script in the UI using `println` for scala and `console.log` for javascript. 
The compile error are also displayed in the UI.

### Dynamo DB 

Dynamo DB is now supported as a database thanks to Lucas Coatanlem (cbm-lcoatanlem) and Gabriel Plassard (gabriel.plassard@carboatmedia.fr).

### Drop scala 2.11 support for jvm izanami client 

Scala 2.11 is not supported anymore for the jvm izanami client.

### React as a peer dependency for react client

React and lodash are now peer dependencies in the react client. 
 

### Upgrading  

:warning: This version break the api for global scripts and features when scripts are used. 

The json payload was 

```json 
{
   "id": "id",
   "enabled": true,
   "activationStrategy": "SCRIPT",
   "parameters": { 
    "script": " ...." 
   }
} 
``` 

and is now 

```json 
{
   "id": "id",
   "enabled": true,
   "activationStrategy": "SCRIPT",
   "parameters": { 
        "type" : "javascript", // or scala
        "script": " ...." 
    }
} 
``` 

This version stay compatible with the jvm client in older versions.



## Version 1.1.0

### Treeview 

In this version you will find a new way to display features, config and experiments in the UI. 
You can now choose between the table view and the tree view. 

The treeview is like browsing folders, each segment of a key is like a folder. 
This view could be really helpful when your keys are well named and organised.     

### Copy key as text 
You cans now copy a key (in edit) as text. 

### Spring config support 
Izanami can be used with spring cloud configuration as a config server. 

### Other features 

 * Izanami server can be used with jdk 11

 
### Bug fix 

You can fin the bufix in the github change log : https://github.com/MAIF/izanami/issues?utf8=%E2%9C%93&q=milestone%3Av1.1.0+


## Older version 

Older release changelogs are available here https://github.com/MAIF/izanami/releases