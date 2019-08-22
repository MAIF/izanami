#!/usr/bin/env bash

DOCKER=$1
TEST=$2

if [ -z "$DOCKER" ];
then 
    echo "No docker to run"
else 
    docker-compose -f docker-compose.test.yml up -d $DOCKER 
fi 
sbt "project izanami-server" "it:testOnly $TEST"
if [ -z "$DOCKER" ];
then 
    echo "No docker to stop"
else
    docker-compose -f docker-compose.test.yml stop
fi
