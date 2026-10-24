cmake_minimum_required(VERSION 3.22)
project(opusmt CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# ---- OPUS-MT (CTranslate2) offline engine ----
# Prebuilt static deps — rebuild with build-deps.sh if needed.
add_library(ct2 STATIC IMPORTED)
set_target_properties(ct2 PROPERTIES
  IMPORTED_LOCATION ${CMAKE_CURRENT_SOURCE_DIR}/deps/build-ct2/libctranslate2.a)

add_library(spm STATIC IMPORTED)
set_target_properties(spm PROPERTIES
  IMPORTED_LOCATION ${CMAKE_CURRENT_SOURCE_DIR}/deps/build-spm/src/libsentencepiece.a)

add_library(opusmt SHARED opusmt_jni.cpp)

target_include_directories(opusmt PRIVATE
  deps/ctranslate2/include
  deps/ctranslate2/third_party
  deps/sentencepiece/src)

target_link_libraries(opusmt ct2 spm log atomic)

target_link_options(opusmt PRIVATE -Wl,--gc-sections -Wl,--exclude-libs,ALL)

