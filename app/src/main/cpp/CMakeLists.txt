cmake_minimum_required(VERSION 3.22.1)
project("prolock360")

add_library(
    prolock360
    SHARED
    native_lib.cpp
    polar_tracker.cpp
    kalman_filter.cpp
)

find_library(log-lib log)
find_library(graphics-lib jnigraphics)
find_library(GLESv2-lib GLESv2)
find_library(EGL-lib EGL)

target_link_libraries(
    prolock360
    ${log-lib}
    ${graphics-lib}
    ${GLESv2-lib}
    ${EGL-lib}
)
