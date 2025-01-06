/usr/local/bin/docker-entrypoint.sh postgres&

echo "Waiting postgres to launch on 5432..."

while ! nc -z localhost 5432; do
  sleep 0.1
done

java -jar /app/izanami.jar