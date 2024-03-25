# EXTISM_REPO=extism
EXTISM_REPO=MAIF

EXTISM_VERSION=$(curl https://api.github.com/repos/${EXTISM_REPO}/extism/releases/latest | jq -r '.name')

echo "latest extism version is: ${EXTISM_VERSION}"

rm -rfv ./conf/native
rm -rfv ./conf/darwin-*
rm -rfv ./conf/linux-*

mkdir ./conf/native

curl -L -o "./conf/native/libextism-aarch64-apple-darwin-${EXTISM_VERSION}.tar.gz" "https://github.com/${EXTISM_REPO}/extism/releases/download/${EXTISM_VERSION}/libextism-aarch64-apple-darwin-${EXTISM_VERSION}.tar.gz"
curl -L -o "./conf/native/libextism-aarch64-apple-darwin-${EXTISM_VERSION}.tar.gz" "https://github.com/${EXTISM_REPO}/extism/releases/download/${EXTISM_VERSION}/libextism-aarch64-apple-darwin-${EXTISM_VERSION}.tar.gz"
curl -L -o "./conf/native/libextism-x86_64-unknown-linux-gnu-${EXTISM_VERSION}.tar.gz" "https://github.com/${EXTISM_REPO}/extism/releases/download/${EXTISM_VERSION}/libextism-x86_64-unknown-linux-gnu-${EXTISM_VERSION}.tar.gz"
curl -L -o "./conf/native/libextism-aarch64-unknown-linux-gnu-${EXTISM_VERSION}.tar.gz" "https://github.com/${EXTISM_REPO}/extism/releases/download/${EXTISM_VERSION}/libextism-aarch64-unknown-linux-gnu-${EXTISM_VERSION}.tar.gz"
curl -L -o "./conf/native/libextism-x86_64-apple-darwin-${EXTISM_VERSION}.tar.gz" "https://github.com/${EXTISM_REPO}/extism/releases/download/${EXTISM_VERSION}/libextism-x86_64-apple-darwin-${EXTISM_VERSION}.tar.gz"

mkdir ./conf/darwin-aarch64
mkdir ./conf/darwin-x86-64
mkdir ./conf/linux-aarch64
mkdir ./conf/linux-x86-64

tar -xvf "./conf/native/libextism-aarch64-apple-darwin-${EXTISM_VERSION}.tar.gz" --directory ./conf/native/
mv ./conf/native/libextism.dylib ./conf/darwin-aarch64/libextism.dylib
tar -xvf "./conf/native/libextism-x86_64-apple-darwin-${EXTISM_VERSION}.tar.gz" --directory ./conf/native/
mv ./conf/native/libextism.dylib ./conf/darwin-x86-64/libextism.dylib

tar -xvf "./conf/native/libextism-aarch64-unknown-linux-gnu-${EXTISM_VERSION}.tar.gz" --directory ./conf/native/
mv ./conf/native/libextism.so ./conf/linux-aarch64/libextism.so
tar -xvf "./conf/native/libextism-x86_64-unknown-linux-gnu-${EXTISM_VERSION}.tar.gz" --directory ./conf/native/
mv ./conf/native/libextism.so ./conf/linux-x86-64/libextism.so
rm -rfv ./conf/native
