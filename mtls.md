# Generate certificates for MTLS

```
sh gen-cert.sh
```

```
openssl s_client -connect localhost:8943 > tls-opt.txt  
```
find client-ca ! 

It's ok 

Test the mtls settings: 
```
curl -k -v https://localhost:8943 # works because of -f
curl -v https://localhost:8943 --cacert ./keycloak/cert/ca-keycloak.cer # works because no client cert but good server CA
curl -v https://localhost:8943 --cacert ./keycloak/cert/ca-keycloak.cer --key ./keycloak/cert/keycloak-server.key --cert ./keycloak/cert/keycloak-server.cer # fails
curl -v https://localhost:8943 --cacert ./keycloak/cert/ca-keycloak.cer --key ./keycloak/cert/izanami-client.key --cert ./keycloak/cert/izanami-client.cer   # works
```
​

https://gist.github.com/Soarez/9688998

## Certificate 

### Server certificate 

```
openssl req -new -newkey rsa:2048 -x509 -sha256 -days 100 -nodes -out tls.crt -keyout tls.key -subj "/CN=localhost"
```

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

Client bundle 
```
cat client.crt ca.crt > client.bundle.crt
```

Server bundle 
```
cat client.key client.crt ca.crt > client.fullbundle.crt
```

## JKS 

```

openssl pkcs12 -export -password pass:"izanami" -out store.pkcs12 -inkey ca.key -certfile ca.crt -in client.bundle.crt -caname 'CA Root' -name client

keytool -importkeystore -noprompt -srckeystore store.pkcs12 -destkeystore client-keystore.jks -srcstoretype pkcs12 -srcstorepass "izanami" -srckeypass "izanami" -destkeypass "izanami" -deststorepass "izanami" -alias client

keytool -noprompt -keystore client-truststore.jks -alias CARoot -import -file ca.crt -storepass "izanami"

```