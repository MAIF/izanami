#!/usr/bin/env bash

echo 'Publishing npm packages to bintray'

LOCATION=`pwd`

cd ${LOCATION}/izanami-clients/react
echo "registry=https://api.bintray.com/npm/maif/npm" > .npmrc
echo "_auth=${BINTRAY_NPM_TOKEN}" >> .npmrc
echo "always-auth=true" >> .npmrc
echo "email=adelegue@hotmail.com" >> .npmrc

echo 'Unpublishing izanami-react'
npm unpublish --force --registry https://api.bintray.com/npm/maif/npm
echo 'Publishing izanami-react'
npm publish --registry https://api.bintray.com/npm/maif/npm

cd ${LOCATION}/izanami-clients/node
echo "registry=https://api.bintray.com/npm/maif/npm" > .npmrc
echo "_auth=${BINTRAY_NPM_TOKEN}" >> .npmrc
echo "always-auth=true" >> .npmrc
echo "email=adelegue@hotmail.com" >> .npmrc

echo 'Unpublishing izanami-node'
npm unpublish --force --registry https://api.bintray.com/npm/maif/npm
echo 'Publishing izanami-node'
npm publish --registry https://api.bintray.com/npm/maif/npm

cd ${LOCATION}