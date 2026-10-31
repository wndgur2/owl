cmake_minimum_required(VERSION 3.20.0)

find_package(Zephyr REQUIRED HINTS $ENV{ZEPHYR_BASE})
project(owl_firmware)

# Hardware-independent logic (also built by tests/unit on native_sim).
target_sources(app PRIVATE
  src/owl_proto.c
  src/control.c
  src/seg_index.c
  src/state.c
  src/power_policy.c
  src/button_sm.c
  src/led_pattern.c
  src/vad.c
  src/pair_policy.c
)

# Zephyr glue.
target_sources(app PRIVATE
  src/main.c
  src/storage.c
  src/audio.c
  src/ble.c
  src/ui.c
  src/power.c
)

if(CONFIG_OWL_CODEC_OPUS)
  include(cmake/opus.cmake)
endif()
