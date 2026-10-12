FROM eclipse-temurin:21-jre-jammy

# Set time zone and application directory
ENV TZ=Asia/Shanghai \
    APP_HOME=/usr/app/jar

RUN ln -snf /usr/share/zoneinfo/$TZ /etc/localtime \
 && echo $TZ > /etc/timezone \
 && mkdir -p $APP_HOME

WORKDIR $APP_HOME

# Copy product
COPY ./target/qdata-ai-server.jar ./qdata-service-ai.jar

EXPOSE 8087

ENTRYPOINT ["java", "-Xms512m", "-Xmx2g", "-Dspring.profiles.active=prod","-jar","qdata-service-ai.jar"]
CMD ["--server.port=8087"]
