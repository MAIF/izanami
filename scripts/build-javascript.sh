#!/usr/bin/env bash

LOCATION=`pwd`

cd $LOCATION/izanami-server/javascript

npm config set scripts-prepend-node-path true

#yarn install
#yarn build
npm install --no-optional
npm run build