cmake_minimum_required(VERSION 3.22.1)

project(llamacpp_android CXX C)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_C_STANDARD 11)

# Include the newly cloned llama.cpp project directory
add_subdirectory(llama.cpp)

# Our native JNI wrapper implementation
add_library(llama_android SHARED
    LlmEngineJNI.cpp
)

# Find standard Android logging
find_library(log-lib log)

# Link the official llama library built from subdirectory with our wrapper JNI
target_link_libraries(llama_android
    PRIVATE
    llama
    ggml
    ${log-lib}
)
