# Cast Craft native AirPlay receiver library.
# SPDX-License-Identifier: GPL-3.0-or-later
cmake_minimum_required(VERSION 3.18.1)
project(castcraft C)

set(CMAKE_C_STANDARD 11)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
set(TP ${CMAKE_CURRENT_SOURCE_DIR}/third_party)

# ---------------------------------------------------------------- OpenSSL
# Static libcrypto built by scripts/build-openssl.sh into
# third_party/openssl/<ABI>/{include,lib}. Can be overridden with -DOPENSSL_ROOT.
if(NOT OPENSSL_ROOT)
  if(ANDROID_ABI)
    set(OPENSSL_ROOT ${TP}/openssl/${ANDROID_ABI})
  endif()
endif()
if(OPENSSL_ROOT)
  if(NOT EXISTS ${OPENSSL_ROOT}/lib/libcrypto.a)
    message(FATAL_ERROR "libcrypto.a not found in ${OPENSSL_ROOT}/lib. Run scripts/build-openssl.sh first.")
  endif()
  add_library(crypto_static STATIC IMPORTED)
  set_target_properties(crypto_static PROPERTIES
    IMPORTED_LOCATION ${OPENSSL_ROOT}/lib/libcrypto.a
    INTERFACE_INCLUDE_DIRECTORIES ${OPENSSL_ROOT}/include)
  set(CRYPTO_LIB crypto_static)
else()
  find_package(OpenSSL 1.1.1 REQUIRED)
  set(CRYPTO_LIB OpenSSL::Crypto)
endif()

# ---------------------------------------------------------------- libplist
file(GLOB PLIST_SRC ${TP}/libplist/src/*.c ${TP}/libplist/libcnary/*.c)
add_library(plist STATIC ${PLIST_SRC})
target_include_directories(plist
  PUBLIC ${TP}/libplist/include
  PRIVATE ${TP}/libplist/src ${TP}/libplist/libcnary/include)
target_compile_definitions(plist PRIVATE
  PACKAGE_VERSION="2.6.0" HAVE_STRNDUP HAVE_MEMMEM HAVE_ATTRIBUTE_CONSTRUCTOR
  HAVE_GMTIME_R HAVE_LOCALTIME_R HAVE_TIMEGM HAVE_TM_TM_GMTOFF HAVE_TM_TM_ZONE HAVE_STRPTIME
  _GNU_SOURCE)
target_compile_options(plist PRIVATE -w)

# ---------------------------------------------------------------- UxPlay lib
set(UX ${TP}/uxplay/lib)
file(GLOB UX_SRC ${UX}/*.c ${UX}/playfair/*.c ${UX}/fairplay_v2/*.c ${UX}/llhttp/*.c)
list(FILTER UX_SRC EXCLUDE REGEX ".*/dnssd\\.c$")
add_library(airplay STATIC ${UX_SRC} ${UX}/dnssd.c dnssd_android.c)
target_include_directories(airplay PUBLIC ${TP} ${UX} ${UX}/playfair ${UX}/fairplay_v2 ${UX}/llhttp)
target_compile_definitions(airplay PRIVATE
  STANDALONE __STDC_CONSTANT_MACROS __STDC_LIMIT_MACROS TARGET_POSIX _LINUX _REENTRANT
  NOHOLD PLIST_210 PLIST_230 OPENSSL_API_COMPAT=0x10101000L _GNU_SOURCE)
target_compile_options(airplay PRIVATE -O2 -Wno-unused-parameter -Wno-deprecated-declarations)
target_link_libraries(airplay PUBLIC plist ${CRYPTO_LIB})

# ---------------------------------------------------------------- JNI library
add_library(castcraft SHARED jni_bridge.c)
if(NOT ANDROID)
  find_package(JNI REQUIRED)
  target_include_directories(castcraft PRIVATE ${JNI_INCLUDE_DIRS})
endif()
target_link_libraries(castcraft PRIVATE airplay)
if(ANDROID)
  target_link_libraries(castcraft PRIVATE log)
  target_link_options(castcraft PRIVATE -Wl,--exclude-libs,ALL -Wl,-z,max-page-size=16384)
else()
  target_link_libraries(castcraft PRIVATE pthread)
endif()
