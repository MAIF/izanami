# CLI 

Izanami provide a command line interface to interact with Izanami server. 

## Download 

First, odwnload the cli : 

```bash 
#if you use linux
wget --show-progress https://dl.bintray.com/maif/binaries/linux-izanamicli/latest/izanami-cli
#if you use osx 
wget --show-progress https://dl.bintray.com/maif/binaries/osx-izanamicli/latest/izanami-cli
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
izanami 0.0.1
Alexandre Delègue <aadelegue@gmail.com>
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

    -t, --feature_tree <feature_tree>
            Return the tree of features. Can be used is combinaison with arg context.

    -g, --get_config <get_config>                        Get a config. A pattern must be specified.
    -s, --set <set>...
            Set a config using the format key=value. Key must be a value in ["client_id", "client_id_header",
            "client_secret", "client_secret_header", "url"]
    -u, --url <url>                                      The url of the server. For example http://izanami.com.
```