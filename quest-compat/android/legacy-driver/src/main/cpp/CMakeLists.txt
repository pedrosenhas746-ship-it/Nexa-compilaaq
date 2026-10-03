cmake_minimum_required(VERSION 3.22)
project(hb_driver_package LANGUAGES CXX)
find_package(OpenXR REQUIRED CONFIG)
add_subdirectory(${CMAKE_CURRENT_SOURCE_DIR}/../../../../../compat/gearvr ${CMAKE_CURRENT_BINARY_DIR}/adapter)
