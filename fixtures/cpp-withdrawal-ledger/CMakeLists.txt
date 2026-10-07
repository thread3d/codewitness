cmake_minimum_required(VERSION 3.20)

project(cpp_withdrawal_ledger VERSION 1.0.0 LANGUAGES CXX)

# C++17 is what the ledger uses for std::optional-free, value-returning code and what the compiler
# defaults to on the platforms this fixture is verified on; it is stated rather than inherited so that
# a machine whose compiler defaults to an older standard fails the build instead of compiling something
# else.
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

add_library(ledger STATIC src/withdrawal_ledger.cpp)
target_include_directories(ledger PUBLIC ${CMAKE_CURRENT_SOURCE_DIR}/include)

add_executable(withdrawal_ledger_test tests/withdrawal_ledger_test.cpp)
target_link_libraries(withdrawal_ledger_test PRIVATE ledger)

# The test is registered under the name of the file that implements it. That is the convention the
# CodeWitness requirement mapper reads: it derives a test's identifier from its file name, and CTest
# reports a test under the name it was registered with, so the two agree without either side guessing.
enable_testing()
add_test(NAME withdrawal_ledger_test COMMAND withdrawal_ledger_test)
