# libopus for the Owl firmware (encoder side, fixed point).
#
# NCS does not ship Opus, so we build it ourselves from the official release
# tarball, pinned by SHA-256. Why FetchContent and not a west module:
#   - the workspace stays a plain `west init -m sdk-nrf --mr v3.4.1` (CI and the
#     README don't need a custom manifest),
#   - no ~8 MB of third-party code vendored into this repo,
#   - the hash makes the download as reproducible as a pinned submodule.
# Offline / cached builds: pass -DFETCHCONTENT_SOURCE_DIR_OPUS=/path/to/opus-1.5.2
#
# Build flags, chosen for Cortex-M33 (nRF54L15, 128 MHz, has the DSP extension):
#   FIXED_POINT          integer math: same quality as float at 16 kHz voice,
#                        deterministic, no FPU context switching in our threads
#   DISABLE_FLOAT_API    drops the float API and the float-only tonality
#                        analysis (analysis.c), which is a large CPU cost
#   VAR_ARRAYS           scratch arrays on the caller's stack (C99 VLAs) instead
#                        of a global pseudo-stack: the audio thread stack is
#                        sized for it (CONFIG_OWL_AUDIO_STACK_SIZE)
#   OPUS_ARM_INLINE_ASM/EDSP  single-cycle SMULL/SMULWB/SMLAWB multiply-accumulate
#                        for the fixed-point macros (ARMv5E DSP subset, which
#                        ARMv8-M Mainline + DSP implements in Thumb-2)
#   -O2                  this library only; the rest of the image stays -Os

include(FetchContent)

set(OWL_OPUS_VERSION 1.5.2)
FetchContent_Declare(opus
  URL https://downloads.xiph.org/releases/opus/opus-${OWL_OPUS_VERSION}.tar.gz
  URL_HASH SHA256=65c1d2f78b9f2fb20082c38cbe47c951ad5839345876e46941612ee87f9a7ce1
  DOWNLOAD_EXTRACT_TIMESTAMP TRUE
)
FetchContent_GetProperties(opus)
if(NOT opus_POPULATED)
  if(POLICY CMP0169)
    cmake_policy(SET CMP0169 OLD) # FetchContent_Populate: we only want the sources
  endif()
  FetchContent_Populate(opus)
endif()

# Read a "NAME = a.c \ b.c" list from Opus' own *_sources.mk files, so the file
# list always matches the pinned release.
function(owl_opus_sources mkfile var out)
  file(READ ${opus_SOURCE_DIR}/${mkfile} txt)
  string(REGEX REPLACE "\\\\\n" " " txt "${txt}")
  string(REGEX MATCH "(^|\n)${var} = ([^\n]*)" _ "${txt}")
  string(REGEX REPLACE "[ \t]+" ";" files "${CMAKE_MATCH_2}")
  list(FILTER files EXCLUDE REGEX "^$")
  list(TRANSFORM files PREPEND ${opus_SOURCE_DIR}/)
  set(${out} ${files} PARENT_SCOPE)
endfunction()

owl_opus_sources(celt_sources.mk CELT_SOURCES celt_srcs)
owl_opus_sources(silk_sources.mk SILK_SOURCES silk_srcs)
owl_opus_sources(silk_sources.mk SILK_SOURCES_FIXED silk_fixed_srcs)
owl_opus_sources(opus_sources.mk OPUS_SOURCES opus_srcs)

add_library(opus STATIC ${celt_srcs} ${silk_srcs} ${silk_fixed_srcs} ${opus_srcs})
target_include_directories(opus
  PUBLIC  ${opus_SOURCE_DIR}/include
  PRIVATE ${opus_SOURCE_DIR} ${opus_SOURCE_DIR}/celt ${opus_SOURCE_DIR}/silk
          ${opus_SOURCE_DIR}/silk/fixed)
target_compile_definitions(opus PRIVATE
  OPUS_BUILD FIXED_POINT=1 DISABLE_FLOAT_API VAR_ARRAYS
  PACKAGE_VERSION="${OWL_OPUS_VERSION}")
if(CONFIG_OWL_OPUS_ARM_DSP)
  target_compile_definitions(opus PRIVATE OPUS_ARM_INLINE_ASM OPUS_ARM_INLINE_EDSP)
endif()
# Zephyr's CPU/ABI flags and include paths, then our optimisation level last.
target_link_libraries(opus PRIVATE zephyr_interface)
target_compile_options(opus PRIVATE -O2 -w)
add_dependencies(opus zephyr_generated_headers)

target_link_libraries(app PRIVATE opus)
