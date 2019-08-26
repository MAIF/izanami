#!/usr/bin/env bash

wget --quiet https://sh.rustup.rs -O rustupinstall.sh
sh ./rustupinstall.sh -y
rustup update
rustup default stable