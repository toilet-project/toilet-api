FROM eclipse-temurin:21-jdk-alpine

RUN apk add --no-cache python3 py3-pillow

WORKDIR /app

ARG JAR_FILE=build/libs/*-SNAPSHOT.jar
COPY ${JAR_FILE} app.jar
COPY scripts/normalize_profile_photo.py /app/normalize_profile_photo.py

ENTRYPOINT ["java", "-Duser.timezone=Asia/Seoul", "-jar", "app.jar"]
