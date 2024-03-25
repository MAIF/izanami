#!/usr/bin/env bash

cd ./izanami-frontend && npm run build && cd ../ && sbt "set test in assembly := {}" clean assembly && cp ./target/scala-2.13/izanami.jar ./
