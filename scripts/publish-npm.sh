#!/usr/bin/env bash

echo 'Publishing npm packages'

LOCATION=`pwd`

cp ${LOCATION}/.npmrc ${LOCATION}/izanami-clients/react/

cp ${LOCATION}/.npmrc ${LOCATION}/izanami-clients/node/

cd ${LOCATION}/izanami-clients/react
npm unpublish --force
yarn publish --new-version ${PACKAGE_VERSION}

cd ${LOCATION}/izanami-clients/node
npm unpublish --force
yarn publish --new-version ${PACKAGE_VERSION}

cd ${LOCATION}