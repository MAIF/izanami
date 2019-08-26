#!/usr/bin/env bash

wget -qO- https://raw.githubusercontent.com/creationix/nvm/v0.33.11/install.sh | bash
export NVM_DIR="/home/travis/.nvm" 
sh $NVM_DIR/nvm.sh 
nvm install 10.13.0 
nvm use 10.13.0 
curl -o- -L https://yarnpkg.com/install.sh | bash -s -- --version 1.12.3 
export PATH=$HOME/.yarn/bin:$PATH 