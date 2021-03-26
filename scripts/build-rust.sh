#!/usr/bin/env bash

LOCATION=`pwd`

cd $LOCATION/izanami-clients/izanami-cli
cargo clean
cargo build --release


