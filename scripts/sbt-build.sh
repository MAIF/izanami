#!/usr/bin/env bash
if [ -z "$TRAVIS_TAG" ];
then
    if test "$TRAVIS_PULL_REQUEST" = "false" && test "$TRAVIS_BRANCH" = "master"
    then
        docker login -u ${DOCKER_USER} -p ${DOCKER_PASSWORD}
        echo 'Master branch, publishing snapshots'
        sbt -J-Xmx2G -J-Xss20M -J-XX:ReservedCodeCacheSize=128m ++$TRAVIS_SCALA_VERSION ";test"
        sbt -J-Xmx2G -J-Xss20M -J-XX:ReservedCodeCacheSize=128m ++$TRAVIS_SCALA_VERSION ";it:test"        
        sbt -J-Xmx2G -J-Xss20M -J-XX:ReservedCodeCacheSize=128m ++$TRAVIS_SCALA_VERSION ";izanami-server/assembly;izanami-server/dist;izanami-server/docker:publish;jvm/publishLocal;jvm/publish"
        sbt -J-Xmx2G -J-Xss20M -J-XX:ReservedCodeCacheSize=128m ++$TRAVIS_SCALA_VERSION ";jvm/publishLocal;jvm/publish"
        echo "Uploading izanami.jar"
        curl -T ./izanami-server/target/scala-2.12/izanami.jar -u${BINTRAY_USER}:${BINTRAY_PASS} -H 'X-Bintray-Publish: 1' -H 'X-Bintray-Override: 1' -H 'X-Bintray-Version: latest' -H 'X-Bintray-Package: izanami.jar' https://api.bintray.com/content/maif/binaries/izanami.jar/latest/izanami.jar
        curl -T ./izanami-server/target/universal/izanami.zip -u${BINTRAY_USER}:${BINTRAY_PASS} -H 'X-Bintray-Publish: 1' -H 'X-Bintray-Override: 1' -H 'X-Bintray-Version: latest' -H 'X-Bintray-Package: izanami-dist' https://api.bintray.com/content/maif/binaries/izanami-dist/latest/izanami-dist.zip
    else
        echo 'Other branch, just test ...'
        sbt  -J-Xmx2G -J-Xss20M -J-XX:ReservedCodeCacheSize=128m ++$TRAVIS_SCALA_VERSION "test"
        sbt  -J-Xmx2G -J-Xss20M -J-XX:ReservedCodeCacheSize=128m ++$TRAVIS_SCALA_VERSION "it:test"
    fi
else
    echo "Tag ${TRAVIS_TAG}, Publishing client"
    docker login -u ${DOCKER_USER} -p ${DOCKER_PASSWORD}
    sbt -J-Xmx2G -J-Xss20M -J-XX:ReservedCodeCacheSize=128m ++$TRAVIS_SCALA_VERSION ";izanami-server/assembly;izanami-server/dist;izanami-server/docker:publish;publish"

    echo "Uploading izanami.jar"
    curl -T ./izanami-server/target/scala-2.12/izanami.jar -u${BINTRAY_USER}:${BINTRAY_PASS} -H "X-Bintray-Publish: 1" -H "X-Bintray-Override: 1" -H "X-Bintray-Version: latest" -H "X-Bintray-Package: izanami.jar" https://api.bintray.com/content/maif/binaries/izanami.jar/latest/izanami.jar
    curl -T ./izanami-server/target/universal/izanami.zip -u${BINTRAY_USER}:${BINTRAY_PASS} -H "X-Bintray-Publish: 1" -H "X-Bintray-Override: 1" -H "X-Bintray-Version: latest" -H "X-Bintray-Package: izanami-dist" https://api.bintray.com/content/maif/binaries/izanami-dist/latest/izanami-dist.zip

    BINARIES_VERSION=`echo "${TRAVIS_TAG}" | cut -d "v" -f 2`
    curl -T ./izanami-server/target/scala-2.12/izanami.jar -u${BINTRAY_USER}:${BINTRAY_PASS} -H "X-Bintray-Publish: 1" -H "X-Bintray-Override: 1" -H "X-Bintray-Version: ${BINARIES_VERSION}" -H "X-Bintray-Package: izanami.jar" https://api.bintray.com/content/maif/binaries/izanami.jar/${BINARIES_VERSION}/izanami.jar
    curl -T ./izanami-server/target/universal/izanami.zip -u${BINTRAY_USER}:${BINTRAY_PASS} -H "X-Bintray-Publish: 1" -H "X-Bintray-Override: 1" -H "X-Bintray-Version: ${BINARIES_VERSION}" -H "X-Bintray-Package: izanami-dist" https://api.bintray.com/content/maif/binaries/izanami-dist/${BINARIES_VERSION}/izanami-dist.zip
fi
