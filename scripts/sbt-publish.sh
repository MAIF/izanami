#!/usr/bin/env bash

BRANCH_NAME=`git branch --show-current`

if [ -n "$BRANCH_NAME" ];
then
    if test "$BRANCH_NAME" = "master"
    then
        docker login -u ${DOCKER_USER} -p ${DOCKER_PASSWORD}
        echo 'Master branch, publishing snapshots'
        sbt -J-Xmx2G -J-Xss20M -J-XX:ReservedCodeCacheSize=128m -Dsbt.color=always -Dsbt.supershell=false ";izanami-server/assembly;izanami-server/dist;izanami-server/docker:publish;"
        sbt -J-Xmx2G -J-Xss20M -J-XX:ReservedCodeCacheSize=128m -Dsbt.color=always -Dsbt.supershell=false ";jvm/publishLocal;+jvm/publish;izanami-spring/publish"
#        echo "Uploading izanami.jar"
#        curl -T ./izanami-server/target/scala-2.12/izanami.jar -u${BINTRAY_USER}:${BINTRAY_PASS} -H 'X-Bintray-Publish: 1' -H 'X-Bintray-Override: 1' -H 'X-Bintray-Version: latest' -H 'X-Bintray-Package: izanami.jar' https://api.bintray.com/content/maif/binaries/izanami.jar/latest/izanami.jar
#        curl -T ./izanami-server/target/universal/izanami.zip -u${BINTRAY_USER}:${BINTRAY_PASS} -H 'X-Bintray-Publish: 1' -H 'X-Bintray-Override: 1' -H 'X-Bintray-Version: latest' -H 'X-Bintray-Package: izanami-dist' https://api.bintray.com/content/maif/binaries/izanami-dist/latest/izanami-dist.zip
    else
        echo 'Other branch, nothing'
    fi
else
    TAG_NAME=`git describe --tags`
    echo "Tag ${TAG_NAME}, Publishing client"
    docker login -u ${DOCKER_USER} -p ${DOCKER_PASSWORD}
    sbt -J-Xmx2G -J-Xss20M -J-XX:ReservedCodeCacheSize=128m -Dsbt.color=always -Dsbt.supershell=false ";izanami-server/assembly;izanami-server/dist;izanami-server/docker:publish;+jvm/publish;izanami-spring/publish"

    echo "Uploading izanami.jar"
    curl -T ./izanami-server/target/scala-2.13/izanami.jar -u${BINTRAY_USER}:${BINTRAY_PASS} -H "X-Bintray-Publish: 1" -H "X-Bintray-Override: 1" -H "X-Bintray-Version: latest" -H "X-Bintray-Package: izanami.jar" https://api.bintray.com/content/maif/binaries/izanami.jar/latest/izanami.jar
    curl -T ./izanami-server/target/universal/izanami.zip -u${BINTRAY_USER}:${BINTRAY_PASS} -H "X-Bintray-Publish: 1" -H "X-Bintray-Override: 1" -H "X-Bintray-Version: latest" -H "X-Bintray-Package: izanami-dist" https://api.bintray.com/content/maif/binaries/izanami-dist/latest/izanami-dist.zip

    BINARIES_VERSION=`echo "${TAG_NAME}" | cut -d "v" -f 2`
    curl -T ./izanami-server/target/scala-2.13/izanami.jar -u${BINTRAY_USER}:${BINTRAY_PASS} -H "X-Bintray-Publish: 1" -H "X-Bintray-Override: 1" -H "X-Bintray-Version: ${BINARIES_VERSION}" -H "X-Bintray-Package: izanami.jar" https://api.bintray.com/content/maif/binaries/izanami.jar/${BINARIES_VERSION}/izanami.jar
    curl -T ./izanami-server/target/universal/izanami.zip -u${BINTRAY_USER}:${BINTRAY_PASS} -H "X-Bintray-Publish: 1" -H "X-Bintray-Override: 1" -H "X-Bintray-Version: ${BINARIES_VERSION}" -H "X-Bintray-Package: izanami-dist" https://api.bintray.com/content/maif/binaries/izanami-dist/${BINARIES_VERSION}/izanami-dist.zip
fi
