FROM eclipse-temurin:21-jre-jammy@sha256:affcab5356e1270ac35d2361ee83135af2f03e8d580eca78a6d5d6fdbf048db0

# Set time zone and application directory
ENV TZ=Asia/Shanghai \
    APP_HOME=/usr/app/jar

RUN ln -snf /usr/share/zoneinfo/$TZ /etc/localtime \
 && echo $TZ > /etc/timezone \
 && mkdir -p $APP_HOME \
 && apt-get update \
 && DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends python3 \
 && rm -rf /var/lib/apt/lists/*

WORKDIR $APP_HOME

# Copy product
COPY ./target/qdata-server.jar ./qdata.jar

EXPOSE 8080

ENTRYPOINT ["java", "-Xms512m", "-Xmx2g", "-Dspring.profiles.active=prod","-jar","qdata.jar"]
CMD ["--server.port=8080"]
