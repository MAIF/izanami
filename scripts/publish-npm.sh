#!/usr/bin/env bash

echo 'Publishing npm packages'

LOCATION=`pwd`

cd ${LOCATION}/izanami-clients/react
npm unpublish --force
npm publish

cd ${LOCATION}/izanami-clients/node
npm unpublish --force
npm publish

cd ${LOCATION}