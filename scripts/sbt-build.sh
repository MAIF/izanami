#!/usr/bin/env bash
if [ -z "$TRAVIS_TAG" ];
then
    echo 'Not a tag, just run test'
    if test "$TRAVIS_PULL_REQUEST" = "false"
    then
        sbt -J-Xmx2G -J-Xss20M -J-XX:ReservedCodeCacheSize=128m ++$TRAVIS_SCALA_VERSION ";test;izanami-server/assembly;izanami-server/dist;izanami-server/docker:publish"
        sbt -J-Xmx2G -J-Xss20M -J-XX:ReservedCodeCacheSize=128m ++$TRAVIS_SCALA_VERSION ";+jvm/publishLocal;+jvm/publish"
        echo "Uploading izanami.jar"
        curl -T ./izanami-server/target/scala-2.12/izanami.jar -u${BINTRAY_USER}:${BINTRAY_PASSWORD} -H 'X-Bintray-Publish: 1' -H 'X-Bintray-Override: 1' -H 'X-Bintray-Version: latest' -H 'X-Bintray-Package: izanami.jar' https://api.bintray.com/content/maif/binaries/izanami.jar/latest/izanami.jar
        curl -T ./izanami-server/target/universal/izanami.zip -u${BINTRAY_USER}:${BINTRAY_PASSWORD} -H 'X-Bintray-Publish: 1' -H 'X-Bintray-Override: 1' -H 'X-Bintray-Version: latest' -H 'X-Bintray-Package: izanami-dist' https://api.bintray.com/content/maif/binaries/izanami-dist/latest/izanami-dist.zip
    else
        sbt -J-XX:ReservedCodeCacheSize=128m ++$TRAVIS_SCALA_VERSION ";test"
    fi
else
echo "Tag ${TRAVIS_TAG}, Publishing client"
    sbt -J-Xmx2G -J-Xss20M -J-XX:ReservedCodeCacheSize=128m ++$TRAVIS_SCALA_VERSION ";izanami-server/assembly;izanami-server/dist;izanami-server/docker:publish"
    sbt -J-Xmx2G -J-Xss20M -J-XX:ReservedCodeCacheSize=128m ++$TRAVIS_SCALA_VERSION "+publish"
    echo "Uploading izanami.jar"
    curl -T ./izanami-server/target/scala-2.12/izanami.jar -u${BINTRAY_USER}:${BINTRAY_PASSWORD} -H "X-Bintray-Publish: 1" -H "X-Bintray-Override: 1" -H "X-Bintray-Version: latest" -H "X-Bintray-Package: izanami.jar" https://api.bintray.com/content/maif/binaries/izanami.jar/latest/izanami.jar
    curl -T ./izanami-server/target/universal/izanami.zip -u${BINTRAY_USER}:${BINTRAY_PASSWORD} -H "X-Bintray-Publish: 1" -H "X-Bintray-Override: 1" -H "X-Bintray-Version: latest" -H "X-Bintray-Package: izanami-dist" https://api.bintray.com/content/maif/binaries/izanami-dist/latest/izanami-dist.zip

    BINARIES_VERSION=`echo "${TRAVIS_TAG}" | cut -d "v" -f 2`
    curl -T ./izanami-server/target/scala-2.12/izanami.jar -u${BINTRAY_USER}:${BINTRAY_PASSWORD} -H "X-Bintray-Publish: 1" -H "X-Bintray-Override: 1" -H "X-Bintray-Version: ${BINARIES_VERSION}" -H "X-Bintray-Package: izanami.jar" https://api.bintray.com/content/maif/binaries/izanami.jar/${BINARIES_VERSION}/izanami.jar
    curl -T ./izanami-server/target/universal/izanami.zip -u${BINTRAY_USER}:${BINTRAY_PASSWORD} -H "X-Bintray-Publish: 1" -H "X-Bintray-Override: 1" -H "X-Bintray-Version: ${BINARIES_VERSION}" -H "X-Bintray-Package: izanami-dist" https://api.bintray.com/content/maif/binaries/izanami-dist/${BINARIES_VERSION}/izanami-dist.zip
fi
