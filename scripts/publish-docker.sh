#!/usr/bin/env bash

echo 'Login to docker repository maif-docker-docker.bintray.io'
docker login -u ${BINTRAY_USER} -p ${BINTRAY_PASSWORD} maif-docker-docker.bintray.io

echo 'Tagging docker package'
docker tag izanami maif-docker-docker.bintray.io/izanami:latest

echo 'Pushing docker package'
docker push maif-docker-docker.bintray.io/izanami:latest
