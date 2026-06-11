FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src ./src
RUN mvn -B -q -DskipTests package

# Runtime needs ffmpeg to concatenate per-line MP3s into per-scene audio
FROM eclipse-temurin:21-jre
RUN apt-get update && apt-get install -y --no-install-recommends ffmpeg \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY --from=build /app/target/*.jar app.jar
EXPOSE 8083
# Bound the heap; ffmpeg children live OUTSIDE the JVM heap (code-review 2026-06-10).
ENV JAVA_TOOL_OPTIONS="-Xmx768m"
ENTRYPOINT ["java","-jar","/app/app.jar"]
