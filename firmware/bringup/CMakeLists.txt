# M0 hardware bring-up: a separate, tiny app that checks every soldered part.
#   firmware/scripts/ncs.sh 'west build -p -b xiao_nrf54l15/nrf54l15/cpuapp -d build/bringup firmware/bringup -- -DEXTRA_CONF_FILE=uart.conf'
cmake_minimum_required(VERSION 3.20.0)

find_package(Zephyr REQUIRED HINTS $ENV{ZEPHYR_BASE})
project(owl_bringup)

target_sources(app PRIVATE src/main.c)
