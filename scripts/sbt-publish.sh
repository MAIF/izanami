#!/usr/bin/env bash

docker login -u ${DOCKER_USER} -p ${DOCKER_PASSWORD}
sbt -J-Xmx2G -J-Xss20M -J-XX:ReservedCodeCacheSize=128m -Dsbt.color=always -Dsbt.supershell=false ";izanami-server/assembly;izanami-server/dist;izanami-server/docker:publish;+jvm/publish;izanami-spring/publish"
