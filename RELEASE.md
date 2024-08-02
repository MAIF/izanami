# Releasing Izanami

For now there is 3 steps to release Izanami : github, docker & maven central.

It would be nice to group all these in a single github action.

## Github

Just launch the `release` github action, and specify version you want to release.

## Docker

```sh
# First build frontend
cd izanami-frontend
npm run build
# Then build backend
cd ..
sbt "set test in assembly := {}" clean assembly
# Build docker image
docker build -t izanami .
# Tag docker image
docker image tag izanami maif/izanami:<YOUR VERSION>
# Push docker image
docker image push maif/izanami:<YOUR VERSION>
```


## Maven central

First, you'll need to indicate target version in version.sbt file.

```shell
# Generate signed bundle
sbt publishSigned
sbt sonatypePrepare
# Upload to staging repository
sbt sonatypeBundleUpload
```

... and then go to sonatype UI and close then release staging repostory

Alternatively, this removes the need to manually close and release :

```shell
# Generate signed bundle
sbt publishSigned
# Upload to staging repository, then close and release staging repository
sbt sonatypeBundleRelease
```