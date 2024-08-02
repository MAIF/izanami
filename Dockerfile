FROM eclipse-temurin:21
RUN mkdir /app
RUN groupadd -g 10001 javauser && useradd -u 10000 -g javauser javauser
ENV IZANAMI_CONTAINERIZED=true
COPY ./target/izanami.jar /app/izanami.jar
WORKDIR /app
RUN chown -R javauser:javauser /app
USER javauser
CMD "java" "-jar" "izanami.jar"