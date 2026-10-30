# Build inside the image, so a fresh clone needs nothing but Docker.
FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY .mvn .mvn
COPY mvnw pom.xml ./
COPY planner-core/pom.xml planner-core/pom.xml
COPY app/pom.xml app/pom.xml
RUN ./mvnw -q -B -DskipTests dependency:go-offline -pl app -am || true
COPY planner-core/src planner-core/src
COPY app/src app/src
RUN ./mvnw -q -B -DskipTests package -pl app -am

FROM eclipse-temurin:21-jre
RUN useradd --system --no-create-home evfleet
USER evfleet
COPY --from=build /src/app/target/app-*.jar /app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app.jar"]
