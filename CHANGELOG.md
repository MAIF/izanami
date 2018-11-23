# Changelog 


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