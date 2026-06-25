cmake_minimum_required(VERSION 3.22.1)
project("facegate_gst" CXX)

# ── GStreamer Android SDK location ────────────────────────────────────────────
#
# Set one of the following before building:
#
#   Option A — local.properties (recommended):
#     Add the line:   gstreamer.dir=/opt/gstreamer-1.0-android-universal-1.24.0
#     Then in app/build.gradle.kts add:
#       cmake { arguments("-DGSTREAMER_ROOT=${gstreamer.dir}") }
#
#   Option B — environment variable:
#     export GSTREAMER_ROOT=/opt/gstreamer-1.0-android-universal-1.24.0
#
#   Option C — direct path (for CI):
#     cmake -DGSTREAMER_ROOT=/path/to/sdk ..
#
# Download SDK from:
#   https://gstreamer.freedesktop.org/data/pkg/android/
#   Filename: gstreamer-1.0-android-universal-<version>.tar.xz
# ─────────────────────────────────────────────────────────────────────────────

if(NOT DEFINED GSTREAMER_ROOT)
    if(DEFINED ENV{GSTREAMER_ROOT})
        set(GSTREAMER_ROOT $ENV{GSTREAMER_ROOT})
    elseif(DEFINED ENV{GSTREAMER_ROOT_ANDROID})
        set(GSTREAMER_ROOT $ENV{GSTREAMER_ROOT_ANDROID})
    else()
        message(FATAL_ERROR
            "GSTREAMER_ROOT not set.\n"
            "Download the GStreamer Android Universal SDK from "
            "https://gstreamer.freedesktop.org/data/pkg/android/ "
            "and set gstreamer.dir in local.properties.\n"
            "See app/src/main/cpp/CMakeLists.txt for details.")
    endif()
endif()

# ABI sub-directory inside the universal SDK
if(ANDROID_ABI STREQUAL "arm64-v8a")
    set(GST_ABI_DIR "${GSTREAMER_ROOT}/arm64")
elseif(ANDROID_ABI STREQUAL "armeabi-v7a")
    set(GST_ABI_DIR "${GSTREAMER_ROOT}/armv7")
elseif(ANDROID_ABI STREQUAL "x86_64")
    set(GST_ABI_DIR "${GSTREAMER_ROOT}/x86_64")
elseif(ANDROID_ABI STREQUAL "x86")
    set(GST_ABI_DIR "${GSTREAMER_ROOT}/x86")
else()
    message(FATAL_ERROR "Unsupported ABI: ${ANDROID_ABI}")
endif()

# ── GStreamer Android CMake integration ───────────────────────────────────────
# The GStreamer Android SDK ships a CMake helper that sets up all include paths,
# plugin dependencies, and linker flags automatically.

set(GST_CMAKE_DIR "${GST_ABI_DIR}/share/gst-android/ndk-build/cmake")

if(EXISTS "${GST_CMAKE_DIR}/gst_android.cmake")
    include("${GST_CMAKE_DIR}/gst_android.cmake")
else()
    # Fallback: manual setup if the cmake helper is not available
    message(WARNING
        "GStreamer cmake helper not found at ${GST_CMAKE_DIR}. "
        "Using manual include/lib paths.")
    set(GST_INCLUDE_DIRS
        "${GST_ABI_DIR}/include/gstreamer-1.0"
        "${GST_ABI_DIR}/include/glib-2.0"
        "${GST_ABI_DIR}/lib/glib-2.0/include"
    )
    set(GST_LIBRARIES
        "${GST_ABI_DIR}/lib/libgstreamer-1.0.a"
        "${GST_ABI_DIR}/lib/libgstvideo-1.0.a"
        "${GST_ABI_DIR}/lib/libgstapp-1.0.a"
        "${GST_ABI_DIR}/lib/libgstbase-1.0.a"
        "${GST_ABI_DIR}/lib/libglib-2.0.a"
        "${GST_ABI_DIR}/lib/libgobject-2.0.a"
        "${GST_ABI_DIR}/lib/libgmodule-2.0.a"
        "${GST_ABI_DIR}/lib/libgthread-2.0.a"
        "${GST_ABI_DIR}/lib/libffi.a"
        "${GST_ABI_DIR}/lib/libz.a"
    )
    include_directories(${GST_INCLUDE_DIRS})
endif()

# ── Required GStreamer plugin groups ─────────────────────────────────────────
# These tell gst_android.cmake which plugin static libs to link in.
# Minimise the set to keep APK size small.

set(GSTREAMER_PLUGINS
    coreelements       # queue, tee, capsfilter, filesrc, …
    playback           # decodebin, urisourcebin, …
    rtp                # rtpjitterbuffer, rtph264depay, …
    rtsp               # rtspsrc
    soup               # souphttpsrc (HLS over HTTP)
    videoscale         # videoscale
    videoconvert       # videoconvert
    app                # appsink / appsrc
    typefindfunctions  # format detection
    # Hardware codecs on Android (OMX / MediaCodec)
    androidmedia       # amcviddec (H.264/H.265 HW decoder)
    openh264           # software H.264 fallback
    libav              # ffmpeg-based SW decode fallback
)

# ── Build the shared library ──────────────────────────────────────────────────

add_library(
    facegate_gst
    SHARED
    gst_pipeline.cpp
)

# Link Android system libraries
find_library(log-lib    log)
find_library(android-lib android)

target_link_libraries(
    facegate_gst
    PRIVATE
    ${log-lib}
    ${android-lib}
    ${GST_LIBRARIES}
)

# C++17 for structured bindings / if constexpr used in the source
target_compile_features(facegate_gst PRIVATE cxx_std_17)
