#!/usr/bin/env bash

LOCATION=$(pwd)

npm config set scripts-prepend-node-path true

echo 'Installing npm-install-peers'
npm install -g npm-install-peers

yarn config set version-git-tag false
npm config set git-tag-version false

BRANCH_NAME=`git branch --show-current`

if [ -n "$BRANCH_NAME" ]; then
  echo 'Not a tag, just building'
  echo 'Building react client ...'
  cd ${LOCATION}/izanami-clients/react
  echo 'Installing dependencies ...'
  npm-install-peers
  echo 'Building package'
  npm run build

  echo 'Building node client ...'
  cd ${LOCATION}/izanami-clients/node
  echo 'Installing dependencies ...'
  npm install
else

  TAG_NAME=`git describe --tags`
  echo "Publishing npm packages for tag ${TAG_NAME}"

  PACKAGE_VERSION=$(echo "${TAG_NAME}" | cut -d "v" -f 2)
  cd ${LOCATION}/izanami-clients/react
  echo "//registry.npmjs.org/:_authToken=${NPM_TOKEN}" >>.npmrc

  echo "Setting version to ${PACKAGE_VERSION}"
  npm version ${PACKAGE_VERSION} --allow-same-version
  echo 'Installing dependencies ...'
  npm-install-peers
  echo 'Publishing'
  npm publish

  cd ${LOCATION}/izanami-clients/node
  echo "//registry.npmjs.org/:_authToken=${NPM_TOKEN}" >>.npmrc

  echo "Setting version to ${PACKAGE_VERSION}"
  npm version ${PACKAGE_VERSION} --allow-same-version
  echo 'Publishing'
  npm publish
fi

cd ${LOCATION}
