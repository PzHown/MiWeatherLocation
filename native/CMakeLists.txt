cmake_minimum_required(VERSION 3.22.1)
project(miweatherlocation_native LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

add_library(miweatherlocation SHARED
    src/main.cpp
)

target_include_directories(miweatherlocation PRIVATE include)

target_compile_options(miweatherlocation PRIVATE
    -Wall
    -Wextra
    -Werror=return-type
    -fvisibility=hidden
    -fvisibility-inlines-hidden
)

# Android 15+ / Android 17 devices may use 16 KB memory pages.
target_link_options(miweatherlocation PRIVATE
    "-Wl,-z,max-page-size=16384"
    "-Wl,-z,common-page-size=16384"
)

find_library(log-lib log)
find_library(dl-lib dl)

target_link_libraries(miweatherlocation PRIVATE ${log-lib} ${dl-lib})
