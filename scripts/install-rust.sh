#!/usr/bin/env bash

wget --quiet https://sh.rustup.rs -O rustupinstall.sh
sh ./rustupinstall.sh -y
export PATH=$PATH:$HOME/.cargo/bin
rustup update
rustup default stable