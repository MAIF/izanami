#!/usr/bin/env bash

if test "$TRAVIS_OS_NAME" = "linux"
then
    LOCATION=`pwd`

    cd $LOCATION/izanami-server/javascript
    yarn install
    yarn build
fi