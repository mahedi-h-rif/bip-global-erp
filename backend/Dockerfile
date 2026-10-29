FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY . .
RUN chmod +x gradlew && ./gradlew :invoice-api:bootJar --no-daemon -x test

FROM eclipse-temurin:21-jre
WORKDIR /app
# tesseract reads uploaded iqamas, passports and licences (Documents); English and Arabic data.
RUN apt-get update \
    && apt-get install -y --no-install-recommends tesseract-ocr tesseract-ocr-eng tesseract-ocr-ara \
    && rm -rf /var/lib/apt/lists/*
RUN useradd --system --no-create-home fatoora
COPY --from=build /src/invoice-api/build/libs/invoice-api.jar app.jar
USER fatoora
EXPOSE 8080
ENV TZ=Asia/Riyadh
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "app.jar"]
