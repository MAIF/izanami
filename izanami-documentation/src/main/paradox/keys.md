# Key definition and best practices


The key format handled by Izanami is namespaces separated by a `:`. 

The recommended format is : 

 * `app namespace` `:` `env namespace` `:` `the rest of the key`
 
For example you could have keys defined like this : 

 * `myapp:dev:my:key`
 * `myapp:preprod:my:key`
 * `myapp:prod:my:key`

This king of key naming bring several advantages. 

## Handling user authorisations 

This kind of format for keys allow you to handle authorisations for different kind of applications. 

When you create a user, you can define "authorized patterns" ( @ref[see](ui.md#edit-a-user) ). 

You define a set of users with pattern `myapp:*` that will only see keys starting with `myapp:`, 
define a set of users with pattern `myapp2:*` that will only see keys starting with `myapp2:` and so on. 

The same rules are possible for api keys. 


## Filtering keys by pattern on the client side

When you query izanami, you can use pattern in order to filter only the keys you need for your application. 
The form of the key can help you to get only the data you need. 

You can for example define keys like 

* `myapp:prod:public:my:key1`
* `myapp:prod:public:my:key2`
* `myapp:prod:private:my:key1`

And use the pattern `myapp:prod:public:*` for the keys exposed to the UI. 

The kind of pattern are mandatory if you use the @ref[node](clients/node.md) or @ref[jvm](clients/jvm.md) clients. 

