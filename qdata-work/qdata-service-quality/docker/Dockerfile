FROM eclipse-temurin:21-jre-jammy

# Set time zone and application directory
ENV TZ=Asia/Shanghai \
    APP_HOME=/usr/app/jar

RUN ln -snf /usr/share/zoneinfo/$TZ /etc/localtime \
 && echo $TZ > /etc/timezone \
 && mkdir -p $APP_HOME \
 && mkdir -p /usr/app/jar/job-log

WORKDIR $APP_HOME

# Copy product
COPY ./target/qdata-service-quality.jar ./qdata-service-quality.jar

EXPOSE 8083

ENTRYPOINT ["java", "-Xms512m", "-Xmx2g", "-Dspring.profiles.active=prod","-jar","qdata-service-quality.jar"]
CMD ["--server.port=8083"]
