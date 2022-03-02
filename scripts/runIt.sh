#!/usr/bin/env bash
set -e

DOCKER=$1
TEST=$2

if [ -z "$DOCKER" ];
then 
    echo "No docker to run"
else 
    docker-compose -f docker-compose.test.yml up -d $DOCKER 
fi 
sbt -Dsbt.color=always -Dsbt.supershell=false "project izanami-server" "it:testOnly $TEST"
export EXIT_CODE=$?

if [ -z "$DOCKER" ];
then 
    echo "No docker to stop"
else
    docker-compose -f docker-compose.test.yml stop
fi
exit $EXIT_CODE