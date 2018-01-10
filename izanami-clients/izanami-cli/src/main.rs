extern crate clap;
extern crate regex;
extern crate futures;
extern crate tokio_core;
extern crate reqwest;
#[macro_use] 
extern crate hyper;
#[macro_use] 
extern crate frunk;
extern crate serde_json;

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
                                        //Default value
                                        .or_else(|_| Ok("Izanami-Client-Id".to_string()));
    let may_be_client_secret = get_config("client_secret", &current_settings, &matches);
    let may_be_client_secret_header = get_config("client_secret_header", &current_settings, &matches)
                                        //Default value
                                        .or_else(|_| Ok("Izanami-Client-Secret".to_string()));
    let may_be_url = get_config("url", &current_settings, &matches);
    
    let res = (
        may_be_client_id.into_validated() + 
        may_be_client_id_header +
        may_be_client_secret + 
        may_be_client_secret_header + 
        may_be_url
    ).into_result().map(|hlist_pat!(client_id, client_id_header, client_secret, client_secret_header, url)| {
        let settings = IzanamiSettings {
            client_id, 
            client_id_header, 
            client_secret, 
            client_secret_header, 
            url            
        };

        let iza_client = IzanamiClient::create(settings);
        
        matches.value_of("check_feature").map(|c| {
            let context = matches.value_of("context").map(|c| String::from(c));
            let resp = iza_client.check_feature_with_context(c, context);
            println!("{}", to_string_pretty(&resp).unwrap());
        });
        matches.value_of("feature_tree").map(|c| {
            let context = matches.value_of("context").map(|c| String::from(c));
            let resp = iza_client.feature_tree(c, context);
            println!("{}", to_string_pretty(&resp).unwrap());
        });
        matches.value_of("get_config").map(|c| {
            let resp = iza_client.get_config(c);
            println!("{}", to_string_pretty(&resp).unwrap());
        });   
        matches.value_of("config_tree").map(|c| {
            let resp = iza_client.get_config_tree(c);
            println!("{}", to_string_pretty(&resp).unwrap());
        });   
    });

    match res {
        Err(e) => {
            if 
                matches.is_present("check_feature") ||
                matches.is_present("feature_tree")  || 
                matches.is_present("get_config")  || 
                matches.is_present("config_tree") {
                    println!("Error : \n{}", e.iter().fold(String::new(), |acc, s| format!("{} * {}\n", acc, s)))
            }
        },
        Ok(_) => {}
    };
}
