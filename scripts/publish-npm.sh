#!/usr/bin/env bash

echo 'Publishing npm packages'

LOCATION=`pwd`

cd ${LOCATION}/izanami-clients/react
npm unpublish --force
yarn publish --new-version ${PACKAGE_VERSION}

cd ${LOCATION}/izanami-clients/node
npm unpublish --force
yarn publish --new-version ${PACKAGE_VERSION}

cd ${LOCATION}