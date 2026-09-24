cmake_minimum_required(VERSION 3.22.1)
project("keyboardengine")

# C++17 for structured bindings / filesystem if needed later
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

add_library(native-lib SHARED
    native-lib.cpp
    KeyboardEngine.cpp
)

# SymSpell is header-only in this project
target_include_directories(native-lib PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})

# Android system libs
find_library(log-lib log)
find_library(android-lib android)

target_link_libraries(native-lib
    ${log-lib}
    ${android-lib}
)

# Optional: enable TFLite if headers + library are provided via ANDROID_TFLite
# Default build compiles WITHOUT TFLite (SymSpell-only) so CI always succeeds.
# To enable: pass -DTFLITE_AVAILABLE=ON and point to TFLite prebuilts.
option(TFLITE_AVAILABLE "Compile with TensorFlow Lite C++ API" OFF)

if(TFLITE_AVAILABLE)
    message(STATUS "Building with TFLite support")
    target_compile_definitions(native-lib PRIVATE TFLITE_AVAILABLE)
    # Example (adjust paths when prebuilts are present):
    # target_include_directories(native-lib PRIVATE ${TFLITE_INCLUDE_DIR})
    # target_link_libraries(native-lib ${TFLITE_LIB})
else()
    message(STATUS "Building SymSpell-only (TFLite next-word disabled at native layer)")
endif()
