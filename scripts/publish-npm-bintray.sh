#!/usr/bin/env bash

echo 'Publishing npm packages to bintray'

LOCATION=`pwd`

cd ${LOCATION}/izanami-clients/react
npm install
curl -u${BINTRAY_USER}:${BINTRAY_PASSWORD} https://api.bintray.com/npm/maif/npm/auth -o ${LOCATION}/izanami-clients/react/.npmrc
echo 'Unpublishing izanami-react'
npm unpublish --force --registry https://api.bintray.com/npm/maif/npm

PACKAGE_VERSION=$(cat package.json \
  | grep version \
  | head -1 \
  | awk -F: '{ print $2 }' \
  | sed 's/[",]//g' \
  | tr -d '[[:space:]]')

echo "Publishing izanami-react with version ${PACKAGE_VERSION}"

npm publish --registry https://api.bintray.com/npm/maif/npm
rm ${LOCATION}/izanami-clients/react/.npmrc



cd ${LOCATION}/izanami-clients/node
curl -u${BINTRAY_USER}:${BINTRAY_PASSWORD} https://api.bintray.com/npm/maif/npm/auth -o ${LOCATION}/izanami-clients/node/.npmrc
echo 'Unpublishing izanami-node'
npm unpublish --force --registry https://api.bintray.com/npm/maif/npm
echo 'Publishing izanami-node'
npm publish  --registry https://api.bintray.com/npm/maif/npm

rm ${LOCATION}/izanami-clients/node/.npmrc

cd ${LOCATION}