# §TZ-DESKTOP-NATIVE-2026-05 — NativeUtils.dll build for Windows.
#
# Ожидает что Microsoft.Web.WebView2 NuGet package скачан в `packages/`
# через `nuget install Microsoft.Web.WebView2 -Version 1.0.2210.55 -OutputDirectory packages`.
# CI workflow делает это перед `cmake -B build`.
#
# Билд:
#   cmake -B build -G "Visual Studio 17 2022" -A x64
#   cmake --build build --config Release
#
# Output: NativeUtils.dll в `<repo>/desktop/src/main/resources/win32-x86-64/`
# (jpackage потом вшивает в EXE через JNA classpath resource).

cmake_minimum_required(VERSION 3.20)
project(NativeUtils CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_WINDOWS_EXPORT_ALL_SYMBOLS OFF)  # Используем __declspec(dllexport) явно.

# Путь к WebView2 SDK. CI скачивает в `desktop/native/win/NativeUtils/packages/`
# через nuget install. Версия pinning'ится в release-desktop.yml CI step.
set(WEBVIEW2_SDK_VERSION "1.0.2210.55" CACHE STRING "WebView2 SDK version")
set(WEBVIEW2_SDK_DIR "${CMAKE_SOURCE_DIR}/packages/Microsoft.Web.WebView2.${WEBVIEW2_SDK_VERSION}"
    CACHE PATH "WebView2 SDK directory")

if(NOT EXISTS "${WEBVIEW2_SDK_DIR}/build/native/include/WebView2.h")
    message(FATAL_ERROR
        "WebView2 SDK not found at ${WEBVIEW2_SDK_DIR}.\n"
        "Run before cmake:\n"
        "  nuget install Microsoft.Web.WebView2 -Version ${WEBVIEW2_SDK_VERSION} -OutputDirectory packages")
endif()

# Архитектура — x64 only (Compose Desktop Win runner = amd64).
# Поддержка ARM64 — отдельная задача в будущем (jpackage пока не таргетит).
set(WEBVIEW2_ARCH "x64")

add_library(NativeUtils SHARED NativeUtils.cpp)

target_include_directories(NativeUtils PRIVATE
    "${WEBVIEW2_SDK_DIR}/build/native/include"
)

target_link_libraries(NativeUtils PRIVATE
    "${WEBVIEW2_SDK_DIR}/build/native/${WEBVIEW2_ARCH}/WebView2LoaderStatic.lib"
    ole32
    oleaut32
    user32
    advapi32
    shlwapi
    runtimeobject
)

# §TZ-DESKTOP-NATIVE-2026-05 — output прямо в JNA resources directory.
# JNA автоматически грузит `NativeUtils.dll` из classpath `win32-x86-64/`.
set(JNA_RESOURCES_DIR "${CMAKE_SOURCE_DIR}/../../../src/main/resources/win32-x86-64")

set_target_properties(NativeUtils PROPERTIES
    RUNTIME_OUTPUT_DIRECTORY "${JNA_RESOURCES_DIR}"
    RUNTIME_OUTPUT_DIRECTORY_RELEASE "${JNA_RESOURCES_DIR}"
    RUNTIME_OUTPUT_DIRECTORY_DEBUG "${JNA_RESOURCES_DIR}"
    OUTPUT_NAME "NativeUtils"
    PREFIX ""  # без `lib` префикса — на Win JNA ищет `NativeUtils.dll`.
)

# Static C runtime — DLL не зависит от vcruntime140.dll/msvcp140.dll юзера.
# Без этого юзеры без VC++ Redistributable получат `0xC000007B` при load.
if(MSVC)
    target_compile_options(NativeUtils PRIVATE /W3 /EHsc /MT$<$<CONFIG:Debug>:d>)
    set_property(TARGET NativeUtils PROPERTY
        MSVC_RUNTIME_LIBRARY "MultiThreaded$<$<CONFIG:Debug>:Debug>")
endif()

target_compile_definitions(NativeUtils PRIVATE
    UNICODE _UNICODE
    WIN32_LEAN_AND_MEAN
    NOMINMAX
)
