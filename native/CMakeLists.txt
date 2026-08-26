cmake_minimum_required(VERSION 3.22.1)
project(miweatherlocation_native LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

add_library(miweatherlocation SHARED
    src/main.cpp
)

add_library(miweatherlocation_hyos SHARED
    src/zn_hyos_lifecycle_diag.cpp
)

foreach(target miweatherlocation miweatherlocation_hyos)
    target_include_directories(${target} PRIVATE include)
    target_compile_options(${target} PRIVATE
        -Wall
        -Wextra
        -Werror=return-type
        -fvisibility=hidden
        -fvisibility-inlines-hidden
    )
    target_link_options(${target} PRIVATE
        "-Wl,-z,max-page-size=16384"
        "-Wl,-z,common-page-size=16384"
    )
endforeach()

find_library(log-lib log)
find_library(dl-lib dl)

target_link_libraries(miweatherlocation PRIVATE ${log-lib} ${dl-lib})
target_link_libraries(miweatherlocation_hyos PRIVATE ${log-lib} ${dl-lib})
