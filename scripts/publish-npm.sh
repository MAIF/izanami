#!/usr/bin/env bash

LOCATION=`pwd`

if test "$TRAVIS_PULL_REQUEST" = "false"
then

    yarn config set version-git-tag false
    npm config set git-tag-version false

    if [ -z "$TRAVIS_TAG" ];
    then
        echo 'Not a tag publishing beta to npm registry'
        cd ${LOCATION}/izanami-clients/react
        npm install
        PACKAGE_CURRENT_VERSION=$(cat package.json | grep version | head -1 | awk -F: '{ print $2 }' | sed 's/[",]//g')
        PACKAGE_VERSION="${PACKAGE_CURRENT_VERSION}-alpha.${TRAVIS_BUILD_NUMBER}"
        npm version ${PACKAGE_VERSION}

        echo "//registry.npmjs.org/:_password=${NPM_PASSWORD}" > .npmrc
        echo "//registry.npmjs.org/:_authToken=${NPM_AUTH}" >> .npmrc
        echo "//registry.npmjs.org/:username=adelegue" >> .npmrc
        echo "//registry.npmjs.org/:email=aadelegue@gmail.com" >> .npmrc

        npm publish

        cd ${LOCATION}/izanami-clients/node

        PACKAGE_CURRENT_VERSION=$(cat package.json | grep version | head -1 | awk -F: '{ print $2 }' | sed 's/[",]//g')
        PACKAGE_VERSION="${PACKAGE_CURRENT_VERSION}-alpha.${TRAVIS_BUILD_NUMBER}"
        npm version ${PACKAGE_VERSION}

        echo "//registry.npmjs.org/:_password=${NPM_PASSWORD}" > .npmrc
        echo "//registry.npmjs.org/:_authToken=${NPM_AUTH}" >> .npmrc
        echo "//registry.npmjs.org/:username=adelegue" >> .npmrc
        echo "//registry.npmjs.org/:email=aadelegue@gmail.com" >> .npmrc

        npm publish
    else
        echo "Publishing npm packages for tag ${TRAVIS_TAG}"

        PACKAGE_VERSION=$(echo "${TRAVIS_TAG}" | cut -d "v" -f 2)
        cd ${LOCATION}/izanami-clients/react
        echo "//registry.npmjs.org/:_password=${NPM_PASSWORD}" > .npmrc
        echo "//registry.npmjs.org/:_authToken=${NPM_AUTH}" >> .npmrc
        echo "//registry.npmjs.org/:username=adelegue" >> .npmrc
        echo "//registry.npmjs.org/:email=aadelegue@gmail.com" >> .npmrc

        npm version ${PACKAGE_VERSION}
        npm install
        npm publish

        cd ${LOCATION}/izanami-clients/node
        echo "//registry.npmjs.org/:_password=${NPM_PASSWORD}" > .npmrc
        echo "//registry.npmjs.org/:_authToken=${NPM_AUTH}" >> .npmrc
        echo "//registry.npmjs.org/:username=adelegue" >> .npmrc
        echo "//registry.npmjs.org/:email=aadelegue@gmail.com" >> .npmrc

        npm version ${PACKAGE_VERSION}
        npm publish
    fi
fi

cd ${LOCATION}