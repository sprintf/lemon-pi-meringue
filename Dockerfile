#FROM eclipse-temurin:17.0.12_7-jre-jammy
FROM mcr.microsoft.com/playwright:v1.58.0-jammy
RUN apt update && apt-get -y install openjdk-17-jre unzip
COPY build/libs/meringue-0.0.1-SNAPSHOT.jar app.jar
# Playwright's driver-bundle.zip must be a real file on disk - ZipFileSystem cannot
# resolve it through Spring Boot's nested JAR classloader, so we extract the fat JAR.
RUN mkdir /app && cd /app && unzip /app.jar
WORKDIR /app
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
