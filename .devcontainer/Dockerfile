FROM tomcat:9-jdk8-temurin-jammy

ENV DEBIAN_FRONTEND=noninteractive
RUN set -eu; \
    apt-get update && apt-get install --no-install-recommends --yes \
        git \
        sudo \
        vim \
    ; \
    useradd -m vscode -G sudo -s /bin/bash; \
    echo "vscode ALL=(ALL) NOPASSWD:ALL" >> /etc/sudoers;
