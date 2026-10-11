FROM maven:3.9.11-eclipse-temurin-21-alpine AS build
WORKDIR /app
COPY pom.xml ./
RUN mvn -q -DskipTests dependency:go-offline
COPY src ./src
RUN mvn -q -DskipTests package

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
# root以外で実行する
RUN addgroup -S app && adduser -S app -G app
COPY --from=build /app/target/receipt-backend-0.0.1-SNAPSHOT.jar app.jar
USER app
EXPOSE 8081
# メモリ不足のときは中途半端に生き残らず、終了して再起動させる
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=70", "-XX:+ExitOnOutOfMemoryError", "-jar", "app.jar"]
