#!/usr/bin/env bash


LOCATION=`pwd`

cd $LOCATION/izanami-clients/izanami-cli
cargo clean
cargo build --release

if [ -z "$TRAVIS_TAG" ];
then
    CLI_VERSION="latest"
else
    CLI_VERSION="${BINARIES_VERSION}"
fi

echo "Releasing rust with version: ${CLI_VERSION}"

curl -T ./target/release/izanami-cli -u${BINTRAY_USER}:${BINTRAY_PASSWORD} -H "X-Bintray-Publish: 1" -H "X-Bintray-Override: 1" -H "X-Bintray-Version: ${CLI_VERSION}" -H "X-Bintray-Package: ${TRAVIS_OS_NAME}-izanamicli" https://api.bintray.com/content/maif/binaries/${TRAVIS_OS_NAME}-izanamicli/${CLI_VERSION}/izanami-cli

cd $LOCATION