#!/bin/bash -e

APPLICATION_SECRET=$(head -c 64 /dev/urandom | base64)

HOST=$(awk 'END{print $1}' /etc/hosts)


exec /opt/docker/bin/izanami -Dlogger.file=./conf/docker-logger.xml -Dcluster.akka.remote.netty.tcp.hostname="${HOST}"  -Dcluster.akka.remote.netty.tcp.bind-hostname="${HOST}"  -Dplay.server.pidfile.path=/dev/null $@
