#!/usr/bin/env bash

LOCATION=`pwd`

OSINFO=`uname -a`

if [[ $OSINFO != Linux* ]];
then
  echo "Only Linux is supported : $OSINFO"
  exit 1
fi

cd $LOCATION/izanami-clients/izanami-cli
cargo clean
cargo build --release
#
#rustup target add x86_64-apple-darwin
#cargo build --release --target=x86_64-apple-darwin


