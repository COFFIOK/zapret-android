# Zapret для Android — нативная часть: движок ByeDPI (hufrea/byedpi, MIT) + запуск туннеля hev-socks5-tunnel.
# Сборка взята из ByeByeDPI (romanvht/ByeByeDPI, GPL-3.0).
cmake_minimum_required(VERSION 3.22.1)
project(zapret_native)

file(GLOB BYE_DPI_SRC byedpi/*.c)
list(REMOVE_ITEM BYE_DPI_SRC ${CMAKE_CURRENT_SOURCE_DIR}/byedpi/win_service.c)

add_library(byedpi SHARED ${BYE_DPI_SRC} byedpi-jni.c hev-jni.c)
target_include_directories(byedpi PRIVATE byedpi)
target_compile_options(byedpi PRIVATE -std=c99 -O2 -Wall -Wno-unused -Wextra -Wno-unused-parameter)
target_compile_definitions(byedpi PRIVATE ANDROID_APP)
target_link_libraries(byedpi PRIVATE android log dl)
target_link_options(byedpi PRIVATE "-Wl,--wrap=daemon" "-Wl,--wrap=connect")
set(CMAKE_SHARED_LINKER_FLAGS "${CMAKE_SHARED_LINKER_FLAGS} -Wl,-z,max-page-size=16384")
