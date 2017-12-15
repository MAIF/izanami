# UI usage

@@toc { depth=3 }

## Global search 

On the top of the screen you can quick search and access "features", "configurations", "experiments" or "global script". 

The search can be refined by clicking the buttons: 

<img src="img/features/search.png" width="100%" />

Clicking one item will bring you to the edit page of the selected item.  

## Features 

Visit to the @ref[features UI doc](features/ui.md) 

## Configurations 

Visit to the @ref[features UI doc](configs/ui.md)


## Experiments (A/B testing)

Visit to the @ref[experiments UI doc](experiments/ui.md)

## Scripts 

You can write script once and reuse it between strategies. Just click to the `Global Scripts` menu. 

![Scripts](img/scripts/list.png)

### Create or update a script 

Hit the `Add item` or the pencil button to edit a script 

![Scripts](img/scripts/script.png)

When writing a script, you have access to 

* `context`: A json object send by the client 
* `enabled`: A function to call, the feature is enabled
* `disabled`: A function to call, the feature is disabled
* `http`: An http client that can be used to request an API.  

The http client expose the call method that take two args :
 
* `options`, an object with the following possible attributes 
    * `url` (required): The url to call. 
    * `method` (default get): The http method betwwen `get`, `post`, `put`, `delete`, `option`, `patch`
    * `headers` : A object with headerName -> Value 
    * `body` : An optional json string
* `callback`: A bifunction with failure or success. 

```javascript
function enabled(context, enabled, disabled, http) {
    http.call({
      url: "http://localhost:9000/api/features/feature:with:script/check", 
      method: "post", 
      headers: {
        "Izanami-Client-Id": "xxxx",
        "Izanami-Client-Secret": "xxxx", 
        "Content-Type": "application/json"
      }, 
      body: JSON.stringify({
        user: context.user
      })
    }, 
    function (error, success) {
      if (error) {
        return enabled()
      } else {
        var resp = JSON.parse(success)
        if (resp.active) {
          return enabled(); 
        } else {
          return disabled();
        }
      }
    }
  )
}
```


## Web hooks

Like the other screen you can see the existing hooks on a table: 

![Hooks](img/hooks/all.png)

## Manage web hooks 

![Hook](img/hooks/hook.png)

The registered hooks will be called when new events occur

### Download and Upload

If you're admin you have the right to download or upload. 

<img src="img/download-upload.png" width="50%" />

## Manage users  

You can manage user if you're an admin. 

![Users](img/users/access-users.png)

Like the other screen you can see the existing users on a table: 

![Users](img/users/all.png)

### Edit a user 

To create or edit a user, you have to 

* an Id 
* A name 
* An email 
* A password 
* Specified if the user is admin 
* Patterns to apply restriction on user

![Edit user](img/users/user.png)

### Download and Upload

If you're admin you have the right to download or upload. 

<img src="img/download-upload.png" width="50%" />

## Manage API keys  

You can manage api keys if you're an admin. 

![Apikeys](img/apikeys/access-apikeys.png)

Like the other screen you can see the existing api keys on a table: 

![Apikeys](img/apikeys/all.png)

### Edit a api key 

To create or edit a api key, you have to 
 
* A name 
* A client id 
* A client secret 
* Patterns to apply restriction on api key

![Edit an api key](img/apikeys/apikey.png)

### Download and Upload

If you're admin you have the right to download or upload. 

<img src="img/download-upload.png" width="50%" />

