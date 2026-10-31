# Hardware-independent firmware logic on native_sim:
#   firmware/scripts/ncs.sh 'west twister -T firmware/tests -p native_sim -O build/twister'
cmake_minimum_required(VERSION 3.20.0)

find_package(Zephyr REQUIRED HINTS $ENV{ZEPHYR_BASE})
project(owl_unit)

set(OWL_SRC ${CMAKE_CURRENT_SOURCE_DIR}/../../src)
target_include_directories(app PRIVATE ${OWL_SRC})
target_sources(app PRIVATE
  src/test_proto.c
  src/test_control.c
  src/test_seg_index.c
  src/test_state.c
  src/test_button.c
  src/test_led.c
  src/test_vad.c
  src/test_pair.c
  ${OWL_SRC}/owl_proto.c
  ${OWL_SRC}/control.c
  ${OWL_SRC}/seg_index.c
  ${OWL_SRC}/state.c
  ${OWL_SRC}/power_policy.c
  ${OWL_SRC}/button_sm.c
  ${OWL_SRC}/led_pattern.c
  ${OWL_SRC}/vad.c
  ${OWL_SRC}/pair_policy.c
)
