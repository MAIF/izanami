#!/usr/bin/env bash

VERSION=$1

LOCATION=`pwd`

yarn config set version-git-tag false
cd ${LOCATION}/izanami-clients/react
#echo "${VERSION}" | cut -d "v" -f 2 | yarn version
echo "${VERSION}" | yarn version
git add package.json

cd ${LOCATION}/izanami-clients/node
#echo "${VERSION}" | cut -d "v" -f 2 | yarn version
echo "${VERSION}" | yarn version
git add package.json

