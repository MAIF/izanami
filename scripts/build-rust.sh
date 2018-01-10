#!/usr/bin/env bash


LOCATION=`pwd`

cd $LOCATION/izanami-clients/izanami-cli
cargo clean
cargo build --release

if test "$TRAVIS_OS_NAME" = "linux"
then
    curl -T ./target/release/izanami-cli -u${BINTRAY_USER}:${BINTRAY_PASSWORD} -H 'X-Bintray-Publish: 1' -H 'X-Bintray-Override: 1' -H 'X-Bintray-Version: latest' -H 'X-Bintray-Package: linux-izanamicli' https://api.bintray.com/content/maif/binaries/linux-izanamicli/latest/izanami-cli
fi

if test "$TRAVIS_OS_NAME" = "osx"
then
    curl -T ./target/release/izanami-cli -u${BINTRAY_USER}:${BINTRAY_PASSWORD} -H 'X-Bintray-Publish: 1' -H 'X-Bintray-Override: 1' -H 'X-Bintray-Version: latest' -H 'X-Bintray-Package: osx-izanamicli' https://api.bintray.com/content/maif/binaries/osx-izanamicli/latest/izanami-cli
fi

cd $LOCATION