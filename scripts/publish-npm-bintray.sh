#!/usr/bin/env bash

echo 'Publishing npm packages to bintray'

LOCATION=`pwd`

cd ${LOCATION}/izanami-clients/react
cp ${LOCATION}/.npmrc ${LOCATION}/izanami-clients/react/
echo 'Unpublishing izanami-react'
npm unpublish --force --registry https://api.bintray.com/npm/maif/npm
echo 'Publishing izanami-react'
npm publish --registry https://api.bintray.com/npm/maif/npm
rm ${LOCATION}/izanami-clients/react/.npmrc

cd ${LOCATION}/izanami-clients/node
cp ${LOCATION}/.npmrc ${LOCATION}/izanami-clients/node/
echo 'Unpublishing izanami-node'
npm unpublish --force --registry https://api.bintray.com/npm/maif/npm
echo 'Publishing izanami-node'
npm publish --registry https://api.bintray.com/npm/maif/npm
rm ${LOCATION}/izanami-clients/node/.npmrc

cd ${LOCATION}