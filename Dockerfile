FROM eclipse-temurin:17.0.7_7-jdk-jammy
COPY build/libs/meringue-0.0.1-SNAPSHOT.jar app.jar
ENTRYPOINT ["java","-jar","/app.jar"]