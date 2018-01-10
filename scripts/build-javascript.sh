#!/usr/bin/env bash

LOCATION=`pwd`

cd $LOCATION/izanami-server/javascript
yarn install
yarn build