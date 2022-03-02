#!/usr/bin/env bash
set -e

LOCATION=`pwd`

cd $LOCATION/izanami-server/javascript

yarn install
yarn build