#FROM eclipse-temurin:17.0.12_7-jre-jammy
FROM mcr.microsoft.com/playwright:v1.47.2-jammy
RUN apt update
RUN apt-get -y install openjdk-17-jre
RUN apt-get -y install chromium-browser
COPY build/libs/meringue-0.0.1-SNAPSHOT.jar app.jar
ENTRYPOINT ["java","-jar","/app.jar"]