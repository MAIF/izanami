#!/usr/bin/env bash

echo 'Publishing npm packages to bintray'

LOCATION=`pwd`

echo "registry=https://api.bintray.com/npm/maif/npm" > ~/.npmrc
echo "_auth=${BINTRAY_NPM_TOKEN}" > ~/.npmrc
echo "always-auth=true" > ~/.npmrc
echo "email=adelegue@hotmail.com" > ~/.npmrc

cd ${LOCATION}/izanami-clients/react
npm unpublish --force
npm publish

cd ${LOCATION}/izanami-clients/node
npm unpublish --force
npm publish

cd ${LOCATION}