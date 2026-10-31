# libopus integration check on native_sim: the same cmake/opus.cmake build of
# libopus 1.5.2 (fixed point, without the ARM inline asm) and the same encoder
# settings as src/audio.c. Verifies it builds, encodes at the target bitrate and
# decodes back; CPU time on the nRF54L15 can only be measured on the board.
cmake_minimum_required(VERSION 3.20.0)

find_package(Zephyr REQUIRED HINTS $ENV{ZEPHYR_BASE})
project(owl_opus_test)

target_sources(app PRIVATE src/test_opus.c)
include(${CMAKE_CURRENT_SOURCE_DIR}/../../cmake/opus.cmake)
