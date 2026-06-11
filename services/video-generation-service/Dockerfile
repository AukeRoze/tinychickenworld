FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src ./src
RUN mvn -B -q -DskipTests package

FROM eclipse-temurin:21-jre
# ffprobe used to validate downloaded MP4s before declaring them OK.
RUN apt-get update && apt-get install -y --no-install-recommends ffmpeg && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY --from=build /app/target/*.jar app.jar
EXPOSE 8087
# Bound the heap; clip downloads stream to disk, not memory (code-review 2026-06-10).
ENV JAVA_TOOL_OPTIONS="-Xmx768m"
ENTRYPOINT ["java","-jar","/app/app.jar"]
