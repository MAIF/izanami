# CLI 

Izanami provide a command line interface to interact with Izanami server. 

## Download 

First, download the cli : 

```bash 
#for linux
wget --show-progress https://github.com/MAIF/izanami/releases/latest/download/izanami-cli
```

And then 

```bash 
chmod +x izanami-cli
```

## Usage 

```bash
./izanami-cli -h
```

```bash
izanami 1.9.0
adelegue <https://github.com/larousso>
.___                                     .__
|   |____________    ____ _____    _____ |__|
|   \___   /\__  \  /    \__  \  /     \|  |
|   |/    /  / __ \|   |  \/ __ \|  Y Y  \  |
|___/_____ \(____  /___|  (____  /__|_|  /__|
          \/     \/     \/     \/      \/
    Izanami CLI to interact with server

USAGE:
    izanami-cli [OPTIONS]

FLAGS:
    -h, --help       Prints help information
    -V, --version    Prints version information

OPTIONS:
    -f, --check_feature <check_feature>
            Check if a feature is active. Can be used is combinaison with arg context.

        --client_id <client_id>                          The client id to authenticate the client
        --client_id_header <client_id_header>            The client id header name. Default value is Izanami-Client-Id.
        --client_secret <client_secret>                  The client secret to authenticate the client
        --client_secret_header <client_secret_header>
            The client secret header name. Default value is Izanami-Client-Secret.

    -v, --config_tree <config_tree>                      Return the tree of configs. A pattern must be specified.
    -c, --context <context>
            Used to check feature depending on context. The context is json like {"user": "ragnar.lodbrock@gmail.com"}

        --create_config <create_config>...               Create a config. the id and the config must be specified
        --create_feature <create_feature>                Create a feature.
        --disable_feature <disable_feature>              Disable a feature.
        --enable_feature <enable_feature>                Enable a feature.
    -t, --feature_tree <feature_tree>
            Return the tree of features. Can be used is combinaison with arg context.

    -g, --get_config <get_config>                        Get a config. A pattern must be specified.
        --release_date <release_date>                    Date for a feature.
        --script <script>                                Script for a feature.
    -s, --set <set>...
            Set a config using the format key=value. Key must be a value in ["client_id", "client_id_header",
            "client_secret", "client_secret_header", "url"]
        --update_config <update_config>...               Update a config. the id and the config must be specified
        --update_feature <update_feature>                Update a feature.
    -u, --url <url>                                      The url of the server. For example http://izanami.com.
```