#!/usr/bin/env bash
# Runs serial4s's whole test suite on Linux, in Docker.
#
# Builds a local image from docker/Dockerfile (this pulls ubuntu:25.10 the first time: run it only
# if you approve that), then:
#   1. as root: the format checks and every test;
#   2. as a normal user: the two suites that hold the "permission denied" tests, which skip
#      themselves as root.
#
# The project is mounted read-only and copied, so nothing is written to your working tree. The sbt
# and dependency caches live in named Docker volumes, so only the first run is slow. To remove
# everything this creates:
#   docker rmi serial4s-linux
#   docker volume rm fs2serial-cache fs2serial-sbt fs2serial-ivy
set -euo pipefail

cd "$(dirname "$0")/.."

IMAGE=serial4s-linux
SBT_VERSION=$(sed -n 's/^sbt.version *= *//p' project/build.properties)

if ! docker info >/dev/null 2>&1; then
  echo "The Docker daemon is not running." >&2
  exit 1
fi
if [ -z "$SBT_VERSION" ]; then
  echo "Could not read sbt.version from project/build.properties." >&2
  exit 1
fi

echo "== image $IMAGE (sbt $SBT_VERSION)"
docker build --quiet --tag "$IMAGE" --build-arg "SBT_VERSION=$SBT_VERSION" docker >/dev/null

VOLUMES=(
  -v fs2serial-cache:/root/.cache
  -v fs2serial-sbt:/root/.sbt
  -v fs2serial-ivy:/root/.ivy2
)

# The same copy of the project for both runs: no build output, no IDE files.
PREPARE='cp -a /src/. /work/ && cd /work && rm -rf target project/target project/project .idea .bsp'

run() {
  docker run --rm -v "$PWD":/src:ro "${VOLUMES[@]}" "$IMAGE" bash -c "$1"
}

echo "== as root: format checks and every test"
run "$PREPARE && sbt -batch \"scalafmtCheckAll; scalafmtSbtCheck; testFull\""

echo "== as a normal user: the permission tests"
run "chmod 755 /root && chmod -R a+rwX /root/.cache /root/.sbt /root/.ivy2 \
  && $PREPARE && chmod -R a+rwX /work \
  && setpriv --reuid=1000 --regid=1000 --clear-groups env HOME=/root \
     sbt -batch \"testOnly *PortListerSuite *PortOpenerSuite\""

echo "== Linux: all green"
