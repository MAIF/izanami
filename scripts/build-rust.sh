#!/usr/bin/env bash
set -e

LOCATION=`pwd`

cd $LOCATION/izanami-clients/izanami-cli
cargo clean
cargo build --release


