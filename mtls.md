# Generate certificates for MTLS

https://gist.github.com/Soarez/9688998

## Certificate 

### Generate the key 

```
openssl genrsa -out client.key 2048
```

This will generate a private/public key tuple.

If you want to extract the public key :
 
```
openssl rsa -in client.key -pubout -out client.pubkey
```

### Generate the CSR (Certificate Signing Request)

```
openssl req -new -key client.key -out client.csr
```

print the csr : 

```
openssl req -in client.csr -noout -text
```


## CA 

### Generate the key 

```
openssl genrsa -out ca.key 2048
```

Generate a self signed certificate for the CA:

```
openssl req -new -x509 -key ca.key -out ca.crt
```

## Sign the certificate 

``` 
openssl x509 -req -in client.csr -CA ca.crt -CAkey ca.key -CAcreateserial -out client.crt
```

## CA Bundle 

CA with server 

```
cat client.crt ca.crt > client.bundle.crt
```

## JKS 

```

openssl pkcs12 -export -password pass:"izanami" -out store.pkcs12 -inkey ca.key -certfile ca.crt -in client.bundle.crt -caname 'CA Root' -name client

keytool -importkeystore -noprompt -srckeystore store.pkcs12 -destkeystore client-keystore.jks -srcstoretype pkcs12 -srcstorepass "izanami" -srckeypass "izanami" -destkeypass "izanami" -deststorepass "izanami" -alias client

keytool -noprompt -keystore client-truststore.jks -alias CARoot -import -file ca.crt -storepass "izanami"

```