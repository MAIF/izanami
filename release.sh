#!/usr/bin/env bash

VERSION=$1

LOCATION=`pwd`

yarn config set version-git-tag false
npm config set git-tag-version false

cd ${LOCATION}/izanami-clients/react
npm version "${VERSION}"
git add package.json

cd ${LOCATION}/izanami-clients/node
npm version "${VERSION}"
git add package.json

cd ${LOCATION}/izanami-clients/izanami-cli

if [[ "$OSTYPE" == "darwin"* ]]; then
  sed -i "" "s/version = \".*\"/version = \"${VERSION}\"/g" Cargo.toml
else
  sed -i "s/version = \".*\"/version = \"${VERSION}\"/g" Cargo.toml
fi

