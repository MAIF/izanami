extern crate clap;
extern crate regex;
extern crate futures;
extern crate tokio_core;
extern crate reqwest;
#[macro_use] 
extern crate hyper;
#[macro_use] 
extern crate frunk;
#[macro_use] 
extern crate serde_json;
extern crate json_color;

mod client;

use serde_json::{to_string_pretty};
use frunk::validated::*;
use std::io::BufReader;
use std::io::BufRead;
use std::fs::File;
use std::collections::HashMap;
use std::io::prelude::*;
use clap::{Arg, App, ArgMatches};
use regex::Regex;
use client::IzanamiSettings;
use client::IzanamiClient;
use json_color::Colorizer;
use serde_json::{Value};

static SETTINGS_REGEX: &str = r"(\w+)=(.+)";

static SETTING_KEYS: &'static [&'static str] = &[
    "client_id",
    "client_id_header",
    "client_secret",
    "client_secret_header",
    "url"
];

struct Settings {    
    settings: HashMap<String, Setting>
}

impl Settings {

    fn new() -> Settings {
        Settings { settings: HashMap::new() }
    }

    fn get(&self, key: &str) -> Option<String> {
        let setting: Option<&Setting> = self.settings.get(key);
        setting.map(|s| s.value.to_string())
    }

    fn add_setting(&mut self, setting: Setting) -> &mut Settings {
        let name = setting.name.to_string();
        &self.settings.insert(name, setting);
        self
    } 
}
struct Setting {
    name: String, 
    value: String
}

impl Setting {
    fn new (name: String, value: String) -> Setting {
        Setting { name, value }
    }
}

trait Stringify {
    fn print(&self) -> String;
}

trait Parsable<T> {
    fn parse(str: String) -> Option<T>;
}

impl Stringify for Setting {
    fn print(&self) -> String {
        format!("{}={}", self.name, self.value)
    }
}

impl Parsable<Setting> for Setting {
    fn parse(str: String) -> Option<Setting> {
        let set_regexp = Regex::new(SETTINGS_REGEX).unwrap();
        if set_regexp.is_match(&str) {
            let gr = set_regexp.captures(&str).unwrap();
            let key = &gr[1];
            let value = &gr[2];
            Some(Setting {
                name: String::from(key), 
                value: String::from(value)
            })
        } else {
            None
        }
    }
}

fn load_config() -> Settings {
     match std::env::home_dir() {
         Some(home) => {
             let config_file = format!("{}/.izanami", home.display());
             match File::open(config_file) {
                 Ok(f) => {
                    let mut file = BufReader::new(&f);
                    let mut settings = Settings::new();
                    for line in file.lines() {
                        let l = line.unwrap();
                        Setting::parse(l).map(|s| settings.add_setting(s));                         
                    }
                    settings
                 },
                 Err(_) => Settings::new()
             }        
        }, 
         None => Settings::new()
    }
}

fn save_config<'a>(settings: &'a Settings) -> &'a Settings {
    match std::env::home_dir() {
        Some(home) => {        
            let strings: &String = &settings.settings.values().map(|v| v.print()).fold(String::new(), |acc, s| format!("{}{}\n", acc, s));
            let config_file = home.join(".izanami");
            let display = config_file.display();
            let mut file = match File::create(&config_file) {
                Err(_) => panic!("couldn't create {}", display),
                Ok(file) => file,
            };
            match file.write_all(strings.as_bytes()) {
                Err(_) => println!("Error writing config"),
                Ok(_) => {}
            };
        }, 
        None => {}
    }
    settings
}


fn get_config<'a, 'b, 'c>(key: &str, settings: &'a Settings, matches: &'b ArgMatches<'c>) -> Result<String, String> {
    let res: Result<String, String> = matches.value_of(key)
            .map(|v| v.to_string())
            .or(settings.get(key))
            .ok_or(format!("{} is missing set config using --set {}=xxxx or use --{} xxxx", key, key, key));
    res
}

fn is_set_value(val: String) -> Result<(), String> {
    let set_regexp = Regex::new(SETTINGS_REGEX).unwrap();
    if set_regexp.is_match(&val) {
        Ok(())
    } else {    
        Err(format!("Set config should be key=value, with key a value in {:?}", SETTING_KEYS))
    }
}

fn color_ouput(colorizer: Colorizer, resp: &Value) -> String {
    let json_colorized_str = colorizer
        .colorize_json_str(to_string_pretty(resp).unwrap().as_str())
        .unwrap();
    json_colorized_str
}

fn build_feature<'b, 'c>(matches: &'b ArgMatches<'c>, key: &str, ) -> Value {
    matches.value_of("release_date").map(|date| {
        json!({
            "id":key,
            "activationStrategy":"RELEASE_DATE",
            "enabled":true, 
            "parameters":{
                "releaseDate": date
            }
        })
    }).or(
        matches.value_of("script").map(|script| {
            json!({
                "id":key,
                "activationStrategy":"SCRIPT",
                "enabled":true, 
                "parameters":{
                    "script": script
                }
            })
        })
    )
    .unwrap_or(
        json!({
            "id":key,
            "activationStrategy":"NO_STRATEGY",
            "enabled":true, 
            "parameters":{}
        })
    )
}

fn main() {
    let matches = App::new("izanami")
        .version("0.0.1")
        .author("Alexandre Delègue <aadelegue@gmail.com>")
        .about(".___                                     .__ 
|   |____________    ____ _____    _____ |__|
|   \\___   /\\__  \\  /    \\__  \\  /     \\|  |
|   |/    /  / __ \\|   |  \\/ __ \\|  Y Y  \\  |
|___/_____ \\(____  /___|  (____  /__|_|  /__|
          \\/     \\/     \\/     \\/      \\/    
    Izanami CLI to interact with server")        
        .arg(Arg::with_name("set")
            .long("set")            
            .short("s")            
            .required(false)
            .multiple(true)
            .takes_value(true)        
            .validator(is_set_value)
            .help(&format!("Set a config using the format key=value. Key must be a value in {:?}", SETTING_KEYS))
        )
        .arg(Arg::with_name("url")
            .long("url")            
            .short("u")            
            .required(false)
            .takes_value(true)        
            .help("The url of the server. For example http://izanami.com.")
        ) 
        .arg(Arg::with_name("client_id")
            .long("client_id")            
            .required(false)
            .takes_value(true)        
            .help("The client id to authenticate the client")
        ) 
        .arg(Arg::with_name("client_id_header")
            .long("client_id_header")          
            .required(false)
            .takes_value(true)        
            .help("The client id header name. Default value is Izanami-Client-Id.")
        ) 
        .arg(Arg::with_name("client_secret")
            .long("client_secret")                  
            .required(false)
            .takes_value(true)        
            .help("The client secret to authenticate the client")
        )         
        .arg(Arg::with_name("client_secret_header")
            .long("client_secret_header")       
            .required(false)
            .takes_value(true)        
            .help("The client secret header name. Default value is Izanami-Client-Secret.")
        )     
        .arg(Arg::with_name("check_feature")
            .long("check_feature")
            .short("f")                
            .required(false)
            .takes_value(true)        
            .help("Check if a feature is active. Can be used is combinaison with arg context.")
        )             
        .arg(Arg::with_name("enable_feature")
            .long("enable_feature")                        
            .required(false)
            .takes_value(true)        
            .help("Enable a feature.")
        ) 
        .arg(Arg::with_name("disable_feature")
            .long("disable_feature")                        
            .required(false)
            .takes_value(true)        
            .help("Disable a feature.")
        )         
        .arg(Arg::with_name("create_feature")
            .long("create_feature")                        
            .required(false)
            .takes_value(true)        
            .help("Create a feature.")
        )
        .arg(Arg::with_name("update_feature")
            .long("update_feature")                        
            .required(false)
            .takes_value(true)        
            .help("Update a feature.")
        )
        .arg(Arg::with_name("release_date")
            .long("release_date")                        
            .required(false)
            .takes_value(true)        
            .help("Date for a feature.")
        )
        .arg(Arg::with_name("script")
            .long("script")                        
            .required(false)
            .takes_value(true)        
            .help("Script for a feature.")
        )
        .arg(Arg::with_name("feature_tree")
            .long("feature_tree")
            .short("t")                
            .required(false)
            .takes_value(true)        
            .help("Return the tree of features. Can be used is combinaison with arg context.")
        ) 
        .arg(Arg::with_name("get_config")
            .long("get_config")
            .short("g")                
            .required(false)
            .takes_value(true)        
            .help("Get a config. A pattern must be specified.")
        ) 
        .arg(Arg::with_name("config_tree")
            .long("config_tree")
            .short("v")                
            .required(false)
            .takes_value(true)        
            .help("Return the tree of configs. A pattern must be specified.")
        )
        .arg(Arg::with_name("context")
            .long("context")
            .short("c")                
            .required(false)        
            .takes_value(true)        
            .help("Used to check feature depending on context. The context is json like {\"user\": \"ragnar.lodbrock@gmail.com\"}")
        )  
        .arg(Arg::with_name("create_config")
            .long("create_config")          
            .multiple(true)         
            .required(false)                            
            .takes_value(true)            
            .help("Create a config. the id and the config must be specified")
        )     
        .arg(Arg::with_name("update_config")
            .long("update_config")          
            .multiple(true)         
            .required(false)                            
            .takes_value(true)            
            .help("Update a config. the id and the config must be specified")
        ) 
        .get_matches(); 

    let set_regexp = Regex::new(SETTINGS_REGEX).unwrap();

    let mut current_settings = load_config();

    match matches.values_of("set") {
        Some(values) => {
            let vals: Vec<&str> = values.collect();
            for s in vals {
                if set_regexp.is_match(s) {
                    let gr = set_regexp.captures(s).unwrap();
                    let key = &gr[1];
                    let value = &gr[2];
                    let str_key: String = String::from(key);                    
                    if SETTING_KEYS.iter().map(|k| k.to_string()).any(|k| k == str_key) {
                        current_settings.add_setting(Setting::new(key.to_string(), value.to_string()));
                    } else {
                        println!("Unknow key {}, sould be one of {:?}", str_key, SETTING_KEYS);
                    }           
                }
            }
        }, 
        None => {}
    };
    save_config(&current_settings);

    let may_be_client_id = get_config("client_id", &current_settings, &matches);
    let may_be_client_id_header = get_config("client_id_header", &current_settings, &matches)
                                        .or_else(|_| Ok("Izanami-Client-Id".to_string()));
    let may_be_client_secret = get_config("client_secret", &current_settings, &matches);
    let may_be_client_secret_header = get_config("client_secret_header", &current_settings, &matches)
                                        .or_else(|_| Ok("Izanami-Client-Secret".to_string()));
    let may_be_url = get_config("url", &current_settings, &matches);
    
    let res = (
        may_be_client_id.into_validated() + 
        may_be_client_id_header +
        may_be_client_secret + 
        may_be_client_secret_header + 
        may_be_url
    ).into_result().and_then(|hlist_pat!(client_id, client_id_header, client_secret, client_secret_header, url)| {

        let settings = IzanamiSettings {
            client_id, 
            client_id_header, 
            client_secret, 
            client_secret_header, 
            url            
        };

        let iza_client = IzanamiClient::create(settings);
        
        let check_feature: Result<String, String> = matches.value_of("check_feature").map(|c| {
            let context = matches.value_of("context").map(|c| String::from(c));
            let resp = iza_client.check_feature_with_context(c, context);            
            println!("{}", color_ouput(Colorizer::arbitrary(), &resp));
            Ok("".to_string())
        }).unwrap_or(Ok("".to_string()));
        
        let feature_tree: Result<String, String> = matches.value_of("feature_tree").map(|c| {
            let context = matches.value_of("context").map(|c| String::from(c));
            let resp = iza_client.feature_tree(c, context);
            println!("{}", color_ouput(Colorizer::arbitrary(), &resp));
            Ok("".to_string())
        }).unwrap_or(Ok("".to_string()));

        let enable_feature: Result<String, String> = matches.value_of("enable_feature").map(|c| {            
            let resp = iza_client.toggle_feature(c, &true);
            println!("{}", color_ouput(Colorizer::arbitrary(), &resp));
            Ok("".to_string())
        }).unwrap_or(Ok("".to_string()));

        let disable_feature: Result<String, String> = matches.value_of("disable_feature").map(|c| {            
            let resp = iza_client.toggle_feature(c, &false);
            println!("{}", color_ouput(Colorizer::arbitrary(), &resp));
            Ok("".to_string())
        }).unwrap_or(Ok("".to_string()));
        
        let create_feature: Result<String, String> = matches.value_of("create_feature").map(|c| {                    
            let feature = build_feature(&matches, c);
            let resp = iza_client.create_feature(feature.to_string());
            println!("{}", color_ouput(Colorizer::arbitrary(), &resp));
            Ok("".to_string())
        }).unwrap_or(Ok("".to_string()));
        
        let update_feature: Result<String, String> = matches.value_of("update_feature").map(|c| {                    
            let feature = build_feature(&matches, c);
            let resp = iza_client.update_feature(c, feature.to_string());
            println!("{}", color_ouput(Colorizer::arbitrary(), &resp));
            Ok("".to_string())
        }).unwrap_or(Ok("".to_string()));
        
        let get_config: Result<String, String> = matches.value_of("get_config").map(|c| {
            let resp = iza_client.get_config(c);
            println!("{}", color_ouput(Colorizer::arbitrary(), &resp));
            Ok("".to_string())
        }).unwrap_or(Ok("".to_string()));
        
        let config_tree: Result<String, String> = matches.value_of("config_tree").map(|c| {
            let resp = iza_client.get_config_tree(c);
            println!("{}", color_ouput(Colorizer::arbitrary(), &resp));
            Ok("".to_string())
        }).unwrap_or(Ok("".to_string()));
        
        let create_config: Result<String, String> = matches.values_of("create_config").map(|values| {                
            let vals: Vec<&str> = values.collect();        
            if vals.len() != 2 {
                Err("create_config take 2 arguments".to_string())
            } else {
                let id = vals[0];
                let raw_config = vals[1];
                serde_json::from_str(raw_config).and_then(|config_json: Value| {
                    let json_config = json!({
                        "id": id,
                        "value": config_json
                    });
                    let resp = iza_client.create_config(json_config);
                    println!("{}", color_ouput(Colorizer::arbitrary(), &resp));
                    Ok("".to_string())
                }).map_err(|err| err.to_string())
            }        
        }).unwrap_or(Ok("".to_string())); 
        
        let update_config: Result<String, String> = matches.values_of("update_config").map(|values| {        
            let vals: Vec<&str> = values.collect();
            if vals.len() != 2 {
                Err("create_config take 2 arguments".to_string())
            } else {
                let id = vals[0];
                let raw_config = vals[1];
                serde_json::from_str(raw_config).and_then(|config_json: Value| {
                    let json_config = json!({
                        "id": id,
                        "value": config_json
                    });
                    let resp = iza_client.update_config(id, json_config);
                    println!("{}", color_ouput(Colorizer::arbitrary(), &resp));
                    Ok("".to_string())
                }).map_err(|err| err.to_string())
            }                
        }).unwrap_or(Ok("".to_string()));

        (
            check_feature.into_validated() +
            feature_tree + 
            enable_feature + 
            disable_feature + 
            create_feature + 
            update_feature + 
            get_config + 
            config_tree + 
            create_config + 
            update_config
        ).into_result()
    });

    match res {
        Err(e) => {
            println!("Error : \n{}", e.iter().fold(String::new(), |acc, s| format!("{} * {}\n", acc, s)))        
        },
        Ok(_) => {}
    };
}
