# Linux for fs2-serial's tests.
#
# glibc 2.42 is the oldest the Linux backend supports (it made speed_t the rate itself), and
# Ubuntu 25.10 is the first Ubuntu to ship it: the usual JDK images are older. gcc is here for the
# small C programs that print platform constants (see AGENTS.md, rule 1), not for the build.
FROM ubuntu:25.10

ARG SBT_VERSION=2.0.12
ENV DEBIAN_FRONTEND=noninteractive

RUN apt-get update \
 && apt-get install -y --no-install-recommends \
      openjdk-25-jdk-headless ca-certificates curl gcc libc6-dev git \
 && rm -rf /var/lib/apt/lists/*

RUN curl -fsSL "https://github.com/sbt/sbt/releases/download/v${SBT_VERSION}/sbt-${SBT_VERSION}.tgz" \
  | tar -xz -C /opt

ENV PATH=/opt/sbt/bin:$PATH
WORKDIR /work
