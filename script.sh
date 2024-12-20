/usr/local/bin/docker-entrypoint.sh postgres&

echo "Waiting postgres to launch on 5432..."

while ! nc -z localhost 5432; do
  sleep 0.1 # wait for 1/10 of the second before check again
done

java -jar /app/izanami.jar