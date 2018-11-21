#!/usr/bin/env bash

LOCATION=`pwd`

npm config set scripts-prepend-node-path true

echo 'Installing npm-install-peers'
npm install -g npm-install-peers


if test "$TRAVIS_PULL_REQUEST" = "false"
then

    yarn config set version-git-tag false
    npm config set git-tag-version false

    if [ -z "$TRAVIS_TAG" ];
    then
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

        echo 'Building angular client ...'
        cd ${LOCATION}/izanami-clients/angular
        echo 'Installing dependencies ...'
        npm install --unsafe-perm=true
        echo 'Building package'
        npm run packagr
    else
        echo "Publishing npm packages for tag ${TRAVIS_TAG}"

        PACKAGE_VERSION=$(echo "${TRAVIS_TAG}" | cut -d "v" -f 2)
        cd ${LOCATION}/izanami-clients/react
        echo "//registry.npmjs.org/:_authToken=${NPM_TOKEN}" >> .npmrc

        echo "Setting version to ${PACKAGE_VERSION}"
        npm version ${PACKAGE_VERSION}
        echo 'Installing dependencies ...'
        npm-install-peers
        echo 'Publishing'
        npm publish

        cd ${LOCATION}/izanami-clients/node
        echo "//registry.npmjs.org/:_authToken=${NPM_TOKEN}" >> .npmrc

        echo "Setting version to ${PACKAGE_VERSION}"
        npm version ${PACKAGE_VERSION}
        echo 'Publishing'
        npm publish

        cd ${LOCATION}/izanami-clients/angular
        echo "//registry.npmjs.org/:_authToken=${NPM_TOKEN}" >> .npmrc

        echo "Setting version to ${PACKAGE_VERSION}"
        npm version ${PACKAGE_VERSION}
        echo 'Installing dependencies ...'
        npm install
        echo 'Building package'
        npm run packagr
        echo 'Publishing'
        npm publish dist
    fi
else
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

    echo 'Building angular client ...'
    cd ${LOCATION}/izanami-clients/angular
    echo 'Installing dependencies ...'
    npm install --unsafe-perm=true
    echo 'Building package'
    npm run packagr
fi

cd ${LOCATION}