# storage.c on native_sim: LittleFS over Zephyr's flash simulator (RAM-backed).
cmake_minimum_required(VERSION 3.20.0)

find_package(Zephyr REQUIRED HINTS $ENV{ZEPHYR_BASE})
project(owl_storage_test)

set(OWL_SRC ${CMAKE_CURRENT_SOURCE_DIR}/../../src)
target_include_directories(app PRIVATE ${OWL_SRC})
target_sources(app PRIVATE
  src/test_storage.c
  ${OWL_SRC}/storage.c
  ${OWL_SRC}/seg_index.c
  ${OWL_SRC}/state.c
  ${OWL_SRC}/owl_proto.c
)
