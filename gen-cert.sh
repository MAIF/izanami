BASE=`pwd`
​
rm -rf $BASE/keycloak-tls
rm -rf $BASE/certs
​
mkdir -p $BASE/certs
​
cd certs

GREEN='\033[0;32m'
​NC='\033[0m'

printf "${GREEN} Generating keycloak CA for https ${NC}\n"
openssl genrsa -out $BASE/certs/ca-keycloak.key 2048
openssl rsa -in $BASE/certs/ca-keycloak.key -out $BASE/certs/ca-keycloak.key
openssl req -new -x509 -sha256 -days 365 -key $BASE/certs/ca-keycloak.key -out $BASE/certs/ca-keycloak.cer -subj "/CN=keycloak-ca"
​
printf "${GREEN} Generating keycloak CA for mtls ${NC}\n"
openssl genrsa -out $BASE/certs/ca-client.key 2048
openssl rsa -in $BASE/certs/ca-client.key -out $BASE/certs/ca-client.key
openssl req -new -x509 -sha256 -days 365 -key $BASE/certs/ca-client.key -out $BASE/certs/ca-client.cer -subj "/CN=client-ca"
​
printf "${GREEN} Generating keycloak certificate https ${NC}\n"
openssl genrsa -out $BASE/certs/keycloak-server.key 2048
openssl rsa -in $BASE/certs/keycloak-server.key -out $BASE/certs/keycloak-server.key
openssl req -new -key $BASE/certs/keycloak-server.key -sha256 -out $BASE/certs/keycloak-server.csr -subj "/CN=localhost"
openssl x509 -req -days 365 -sha256 -in $BASE/certs/keycloak-server.csr -CA $BASE/certs/ca-keycloak.cer -CAkey $BASE/certs/ca-keycloak.key -set_serial 1 -out $BASE/certs/keycloak-server.cer

printf "${GREEN} Generating keycloak certificate for mtls ${NC}\n"​
openssl genrsa -out $BASE/certs/izanami-client.key 2048
openssl rsa -in $BASE/certs/izanami-client.key -out $BASE/certs/izanami-client.key
openssl req -new -key $BASE/certs/izanami-client.key -out $BASE/certs/izanami-client.csr -subj "/CN=izanami"
openssl x509 -req -days 365 -sha256 -in $BASE/certs/izanami-client.csr -CA $BASE/certs/ca-client.cer -CAkey $BASE/certs/ca-client.key -set_serial 2 -out $BASE/certs/izanami-client.cer
​
cd $BASE
​
mkdir $BASE/keycloak-tls

printf "${GREEN} Building https bundles ${NC}\n"​
cp $BASE/certs/keycloak-server.cer $BASE/keycloak-tls/tls.crt
cp $BASE/certs/keycloak-server.key $BASE/keycloak-tls/tls.key
echo "" >> $BASE/keycloak-tls/tls.crt
cat $BASE/certs/ca-keycloak.cer >> $BASE/keycloak-tls/tls.crt
​
printf "${GREEN} Building mtls server side bundles ${NC}\n"​
cp $BASE/certs/ca-client.key $BASE/keycloak-tls/ca-client.bundle
echo "" >> $BASE/keycloak-tls/ca-client.bundle
cat $BASE/certs/ca-client.cer >> $BASE/keycloak-tls/ca-client.bundle
​
cd $BASE
​
printf "${GREEN} Building mtls client side bundles ${NC}\n"​
​cp $BASE/certs/keycloak-server.cer $BASE/keycloak-tls/server.pem
echo "" >> $BASE/keycloak-tls/server.pem
cat $BASE/certs/ca-keycloak.cer >> $BASE/keycloak-tls/server.pem

cp $BASE/certs/izanami-client.cer $BASE/keycloak-tls/client.pem
echo "" >> $BASE/keycloak-tls/client.pem
cat $BASE/certs/ca-client.cer >> $BASE/keycloak-tls/client.pem

openssl pkcs12 -export -clcerts -in $BASE/keycloak-tls/client.pem -inkey $BASE/certs/izanami-client.key -out $BASE/keycloak-tls/client.p12 -password pass:izanami
​
cd $BASE
