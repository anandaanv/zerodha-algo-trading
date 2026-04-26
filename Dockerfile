FROM eclipse-temurin:21-jdk AS build

WORKDIR /code
COPY . .
RUN ./gradlew bootJar --no-daemon -x test

FROM eclipse-temurin:21-jre

WORKDIR /app
COPY --from=build /code/build/libs/kitecon-0.0.1-SNAPSHOT.jar /app/app.jar
COPY --from=build /code/start.sh /app/start.sh

EXPOSE 8080

ENV JAVA_TOOL_OPTIONS="-XX:ReservedCodeCacheSize=256m -XX:InitialCodeCacheSize=64m -Xms4g -Xmx6g -XX:+ExitOnOutOfMemoryError"

CMD ["java", "-jar", "/app/app.jar"]
