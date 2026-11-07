#!/usr/bin/env bash
# Flashes a Docker build (firmware/scripts/ncs.sh) to the board over USB, from the Mac.
#
#   firmware/scripts/flash.sh build/bringup
#
# `west flash` can't do this: Docker on macOS has no USB access, and the build tree
# only has container paths. This runs the same OpenOCD commands the board's west
# runner does (read with `west -v flash --openocd /bin/echo`), natively.
# - needs `brew install openocd`
# - the board's OpenOCD config comes from the NCS volume, cached in build/ (Docker
#   only has to be running the first time)
set -euo pipefail

REPO="$(cd "$(dirname "$0")/../.." && pwd)"
BUILD="${1:?usage: flash.sh <build dir, e.g. build/bringup>}"
BOARD_CFG=/ncs/zephyr/boards/seeed/xiao_nrf54l15/support/openocd.cfg
CFG="${REPO}/build/xiao_nrf54l15-openocd.cfg"

# Sysbuild puts the app in a folder named after it: build/fw/firmware, build/bringup/bringup.
HEX="$(ls "${BUILD}"/*/zephyr/zephyr.hex 2>/dev/null | head -n 1 || true)"
if [[ -z "${HEX}" ]]; then
	echo "no ${BUILD}/*/zephyr/zephyr.hex - build it first (firmware/README.md)" >&2
	exit 1
fi

if [[ ! -s "${CFG}" ]]; then
	mkdir -p "$(dirname "${CFG}")"
	"${REPO}/firmware/scripts/ncs.sh" "cat ${BOARD_CFG}" >"${CFG}.tmp"
	mv "${CFG}.tmp" "${CFG}"
fi

echo "Flashing ${HEX}"
exec openocd -f "${CFG}" \
	-c init -c targets -c "reset init" \
	-c "nrf54l-load {${HEX}}" \
	-c "reset run" -c shutdown
