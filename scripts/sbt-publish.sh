#!/usr/bin/env bash

BRANCH_NAME=`git branch --show-current`

echo "Branch name: ${BRANCH_NAME}"

IS_TAG=$( [ -z "$BRANCH_NAME" ] ; echo $? )
IS_MASTER=$( [ -n "$BRANCH_NAME" ] && [ "$BRANCH_NAME" == "master" ] ; echo $? )

if [ $IS_TAG == 0 ]
then
  echo "tag detected"
fi

if [ $IS_MASTER == 0 ]
then
  echo "master detected"
fi

if [ $IS_TAG == 0 ] || [ $IS_MASTER == 0 ]
then
  echo 'Tag or master, building and publishing'
  docker login -u ${DOCKER_USER} -p ${DOCKER_PASSWORD}
  sbt -J-Xmx2G -J-Xss20M -J-XX:ReservedCodeCacheSize=128m -Dsbt.color=always -Dsbt.supershell=false ";ci-release;izanami-server/dist;izanami-server/docker:publish"
else
  echo 'Not a tag or not master, just building'
  sbt  -Dsbt.color=always -Dsbt.supershell=false ";izanami-server/dist;izanami-server/docker:publishLocal;+jvm/publishLocal;izanami-spring/publishLocal;publishUberJar/publishLocal"
fi
