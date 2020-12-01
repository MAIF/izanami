#!/usr/bin/env bash


LOCATION=`pwd`

cd $LOCATION/izanami-clients/izanami-cli
cargo clean
cargo build --release

BRANCH_NAME=`git branch --show-current`
OSINFO=`uname -a`

if [[ $OSINFO != Linux* ]];
then
  echo "Only Linux is supported : $OSINFO"
  exit 1
fi

OS_NAME="linux"

if [ -n "$BRANCH_NAME" ];
then
    if test "$BRANCH_NAME" = "master"
    then
        CLI_VERSION="latest"
        echo "Releasing rust with version: ${CLI_VERSION}"

        curl -T ./target/release/izanami-cli -u${BINTRAY_USER}:${BINTRAY_PASS} -H "X-Bintray-Publish: 1" -H "X-Bintray-Override: 1" -H "X-Bintray-Version: ${CLI_VERSION}" -H "X-Bintray-Package: ${OS_NAME}-izanamicli" https://api.bintray.com/content/maif/binaries/${OS_NAME}-izanamicli/${CLI_VERSION}/izanami-cli
    fi
else
    CLI_VERSION="${BINARIES_VERSION}"
    echo "Releasing rust with version: ${CLI_VERSION}"

    curl -T ./target/release/izanami-cli -u${BINTRAY_USER}:${BINTRAY_PASS} -H "X-Bintray-Publish: 1" -H "X-Bintray-Override: 1" -H "X-Bintray-Version: ${CLI_VERSION}" -H "X-Bintray-Package: ${OS_NAME}-izanamicli" https://api.bintray.com/content/maif/binaries/${OS_NAME}-izanamicli/${CLI_VERSION}/izanami-cli

    cd $LOCATION
fi

