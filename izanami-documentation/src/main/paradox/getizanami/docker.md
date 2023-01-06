# From Docker


You can get the official image from the docker repository : 

```bash 
docker pull maif/izanami
```

AND then run it 

```bash 
docker run -p "8080:8080" -e "FILTER_CLAIM_SHAREDKEY=averyrandomandsecretvalue" maif/izanami 
```

you can also provide some ENV variable using the --env flag to customize your Izanami instance. Check the configuration documentation @see[here](../settings/settings.md) 

