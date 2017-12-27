#!/usr/bin/env bash

echo 'Publishing npm packages'

LOCATION=`pwd`

echo "//registry.npmjs.org/:_password=${NPM_PASSWORD}" > ~/.npmrc
echo "//registry.npmjs.org/:_authToken=${NPM_AUTH}" >> ~/.npmrc
echo "//registry.npmjs.org/:username=adelegue" >> ~/.npmrc
echo "//registry.npmjs.org/:email=aadelegue@gmail.com" >> ~/.npmrc

cd ${LOCATION}/izanami-clients/react
npm unpublish --force
npm publish

cd ${LOCATION}/izanami-clients/node
npm unpublish --force
npm publish

cd ${LOCATION}