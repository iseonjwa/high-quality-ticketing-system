# syntax=docker/dockerfile:1
# Jenkins EC2에서 도는 Jenkins 이미지(ISE-175). 설정은 JCasC(casc.yaml)로만 한다. 설계: docs/design/infra/deployment.md "Jenkins".
# LTS 2.580.2(JDK 21)를 digest로 고정한다. git·ssh·curl은 기본 이미지에 있고, 파이프라인이 쓰는 jq만 더한다.
FROM jenkins/jenkins:2.580.2-lts-jdk21@sha256:74401de6a6f92e1afe1b02d1c8e3d0c33f263bfb5275dd8813f9150355f2367b
USER root
RUN apt-get update \
    && apt-get install -y --no-install-recommends jq \
    && rm -rf /var/lib/apt/lists/*
USER jenkins
COPY --chown=jenkins:jenkins plugins.txt /usr/share/jenkins/ref/plugins.txt
RUN jenkins-plugin-cli --plugin-file /usr/share/jenkins/ref/plugins.txt
COPY --chown=jenkins:jenkins casc.yaml /usr/share/jenkins/casc.yaml
ENV CASC_JENKINS_CONFIG=/usr/share/jenkins/casc.yaml
