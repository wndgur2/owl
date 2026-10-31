#!/usr/bin/env bash
# Runs a command inside the pinned nRF Connect SDK toolchain container.
#
#   firmware/scripts/ncs.sh west build -p -b xiao_nrf54l15/nrf54l15/cpuapp firmware
#
# - the NCS workspace lives in the Docker volume owl-ncs-$NCS_VERSION at /ncs
#   (created by `firmware/scripts/ncs.sh --setup`, ~5 GB, once)
# - the repo is mounted inside it at /ncs/owl, so west finds the workspace;
#   commands run from there (paths like `firmware` work as in the README)
# - build output goes to firmware/build* in the repo (gitignored)
# The image is amd64-only; on Apple Silicon Docker runs it under emulation.
set -euo pipefail

NCS_VERSION="${NCS_VERSION:-v3.4.1}"
# Local image = official toolchain image + make/multilib for native_sim (Dockerfile).
IMAGE="owl-ncs:${NCS_VERSION}"
VOLUME="owl-ncs-${NCS_VERSION}"
REPO="$(cd "$(dirname "$0")/../.." && pwd)"

run() {
	docker run --rm --platform linux/amd64 \
		-v "${VOLUME}:/ncs" -v "${REPO}:/ncs/owl" -w /ncs/owl \
		"${IMAGE}" "$*"
}

if [[ "${1:-}" == "--setup" ]]; then
	docker build --platform linux/amd64 --build-arg "NCS_VERSION=${NCS_VERSION}" \
		-t "${IMAGE}" "$(dirname "$0")"
	docker volume create "${VOLUME}" >/dev/null
	docker run --rm --platform linux/amd64 -v "${VOLUME}:/ncs" -w /ncs "${IMAGE}" \
		"(test -d .west || west init -m https://github.com/nrfconnect/sdk-nrf --mr ${NCS_VERSION}) && west update --narrow -o=--depth=1"
	exit 0
fi

run "$@"
