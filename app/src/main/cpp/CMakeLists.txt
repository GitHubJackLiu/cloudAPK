cmake_minimum_required(VERSION 3.18)
project(xzopus C)

# opus 源码不在仓库里（3.6MB / 300+ 文件），由构建流程负责就位：
#   - CI：build.yml 里 curl 下载解压到 ${CMAKE_CURRENT_SOURCE_DIR}/opus-1.3.1
#   - 本地：手动 curl 同样放这个目录
# 这样仓库保持干净，推送不会被大对象拖死。
set(OPUS_DIR ${CMAKE_CURRENT_SOURCE_DIR}/opus-1.3.1)
if(NOT EXISTS ${OPUS_DIR}/src/opus.c)
    message(FATAL_ERROR
        "opus 源码缺失：${OPUS_DIR}\n"
        "CI 会在构建前自动下载；本地请执行：\n"
        "  curl -L https://downloads.xiph.org/releases/opus/opus-1.3.1.tar.gz | tar xz -C app/src/main/cpp/\n"
        "  mv app/src/main/cpp/opus-1.3.1 app/src/main/cpp/opus-1.3.1.tmp && \\\n"
        "  mkdir -p app/src/main/cpp/opus-1.3.1 && mv app/src/main/cpp/opus-1.3.1.tmp/* app/src/main/cpp/opus-1.3.1/")
endif()

# opus 1.3.1 源码。只取纯 C 路径（celt/silk/src 顶层 + silk/float），
# 不开 NEON/SSE 汇编——MT8167 是 Cortex-A35，纯 C 浮点实时编解码
# 16k 单声道余量足够，先把链路跑通，性能不够再上 NEON。
file(GLOB CELT_SRC   ${OPUS_DIR}/celt/*.c)
file(GLOB SILK_SRC   ${OPUS_DIR}/silk/*.c)
file(GLOB SILK_FLP   ${OPUS_DIR}/silk/float/*.c)
file(GLOB OPUS_SRC   ${OPUS_DIR}/src/*.c)

# 这些是命令行工具（各自带 main），静态库里留着会和 JNI 的入口冲突
list(REMOVE_ITEM OPUS_SRC
        ${OPUS_DIR}/src/opus_demo.c
        ${OPUS_DIR}/src/opus_compare.c
        ${OPUS_DIR}/src/repacketizer_demo.c)
list(REMOVE_ITEM CELT_SRC ${OPUS_DIR}/celt/opus_custom_demo.c)

add_library(opus STATIC
        ${CELT_SRC}
        ${SILK_SRC}
        ${SILK_FLP}
        ${OPUS_SRC})

target_include_directories(opus PUBLIC
        ${OPUS_DIR}/include
        ${OPUS_DIR}/celt
        ${OPUS_DIR}/silk
        ${OPUS_DIR}/silk/float)

target_compile_definitions(opus PRIVATE
        OPUS_BUILD
        VAR_ARRAYS
        FLOAT_APPROX
        HAVE_LRINT
        HAVE_LRINTF)

target_compile_options(opus PRIVATE -O3 -ffast-math)

add_library(xzopus SHARED opus_jni.c)
target_include_directories(xzopus PRIVATE ${OPUS_DIR}/include)
target_link_libraries(xzopus opus log)
target_compile_options(xzopus PRIVATE -O2)
