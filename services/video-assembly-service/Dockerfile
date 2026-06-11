FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src ./src
RUN mvn -B -q -DskipTests package

# Runtime image with ffmpeg installed
FROM eclipse-temurin:21-jre
RUN apt-get update && apt-get install -y --no-install-recommends ffmpeg fonts-comfortaa && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY --from=build /app/target/*.jar app.jar
EXPOSE 8082
# Bound the heap; the heavy lifting (ffmpeg) runs as child processes OUTSIDE
# the JVM heap, so the Java side stays small (code-review 2026-06-10).
ENV JAVA_TOOL_OPTIONS="-Xmx768m"
ENTRYPOINT ["java","-jar","/app/app.jar"]
