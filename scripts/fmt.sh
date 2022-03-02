#!/usr/bin/env bash
set -e

LOCATION=`pwd`

fmt_cli () {
  cd $LOCATION/izanami-clients/izanami-cli
  rustup run stable cargo fmt -- --all
}


fmt_react_client () {
  cd $LOCATION/izanami-clients/react
  yarn prettier
}

fmt_node_client () {
  cd $LOCATION/izanami-clients/node
  yarn prettier
}

fmt_ui () {
  cd $LOCATION/izanami-server/javascript
  yarn prettier
}


fmt_scala () {
    sbt ';scalafmt;sbt:scalafmt;test:scalafmt'
}

case "${1}" in
  all)
    fmt_cli
    fmt_react_client
    fmt_node_client
    fmt_ui
    fmt_scala
    ;;
  cli)
    fmt_cli
    ;;
  react)
    fmt_react_client
    ;;
  node)
    fmt_node_client
    ;;
  ui)
    fmt_ui
    ;;
  scala)
    fmt_scala
    ;;
  *)
    fmt_cli
    fmt_react_client
    fmt_node_client
    fmt_ui
    fmt_scala
esac

exit ${?}