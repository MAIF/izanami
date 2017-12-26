#!/usr/bin/env bash

echo 'Publishing docker package'

docker tag izanami maif-docker-docker.bintray.io/izanami

docker push maif-docker-docker.bintray.io/izanami
