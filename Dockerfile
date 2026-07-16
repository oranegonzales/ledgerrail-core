FROM maven:3.9.15-eclipse-temurin-26 AS build
WORKDIR /workspace
COPY pom.xml .
RUN mvn -B dependency:go-offline
COPY src src
RUN mvn -B -DskipTests package

FROM eclipse-temurin:21-jre-jammy
RUN groupadd --system ledgerrail && useradd --system --gid ledgerrail ledgerrail
WORKDIR /app
COPY --from=build /workspace/target/ledgerrail-core-*.jar app.jar
USER ledgerrail
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-jar", "app.jar"]
