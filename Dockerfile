FROM gradle:jdk11

ARG DEBIAN_FRONTEND=noninteractive
RUN apt-get update && apt-get -y upgrade
RUN apt-get -y install git wget

USER root

RUN mkdir code
COPY . /code
#RUN mkdir -p /root/.gradle/wrapper/dists/gradle-6.6.1-bin
#ADD ./gradle-6.6.1-bin /root/.gradle/wrapper/dists/gradle-6.6.1-bin
RUN cd /code && ./gradlew bootjar

# Ensure JVM receives larger code cache by default and ~4g heap
ENV JAVA_TOOL_OPTIONS="-XX:ReservedCodeCacheSize=256m -XX:InitialCodeCacheSize=64m -Xms4g -Xmx4g"

CMD /code/start.sh