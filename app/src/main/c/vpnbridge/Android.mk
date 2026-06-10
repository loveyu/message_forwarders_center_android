# Android.mk for VPN bridge (hev-socks5-tunnel wrapper executable)

LOCAL_PATH := $(call my-dir)
HEV_SOCKS5_TUNNEL_DIR := $(HEV_SOCKS5_TUNNEL_DIR)

# --- libyaml (static) ---
include $(CLEAR_VARS)
LOCAL_MODULE := yaml
LOCAL_SRC_FILES := $(wildcard $(HEV_SOCKS5_TUNNEL_DIR)/third-part/yaml/src/*.c)
LOCAL_C_INCLUDES := $(HEV_SOCKS5_TUNNEL_DIR)/third-part/yaml/src
LOCAL_EXPORT_C_INCLUDES := $(HEV_SOCKS5_TUNNEL_DIR)/third-part/yaml/src
include $(BUILD_STATIC_LIBRARY)

# --- liblwip (static) ---
include $(CLEAR_VARS)
LOCAL_MODULE := lwip
rwildcard=$(foreach d,$(wildcard $1*),$(call rwildcard,$d/,$2) $(filter $(subst *,%,$2),$d))
LOCAL_SRC_FILES := $(call rwildcard,$(HEV_SOCKS5_TUNNEL_DIR)/third-part/lwip/src/,*.c)
LOCAL_C_INCLUDES := \
	$(HEV_SOCKS5_TUNNEL_DIR)/third-part/lwip/src/include \
	$(HEV_SOCKS5_TUNNEL_DIR)/third-part/lwip/src/ports/include
LOCAL_CFLAGS := -DFD_SET_DEFINED -DSOCKLEN_T_DEFINED
include $(BUILD_STATIC_LIBRARY)

# --- libhev-task-system (static) ---
include $(CLEAR_VARS)
LOCAL_MODULE := hev-task-system
LOCAL_SRC_FILES := $(call rwildcard,$(HEV_SOCKS5_TUNNEL_DIR)/third-part/hev-task-system/src/,*.c *.S)
LOCAL_C_INCLUDES := \
	$(HEV_SOCKS5_TUNNEL_DIR)/third-part/hev-task-system/include \
	$(HEV_SOCKS5_TUNNEL_DIR)/third-part/hev-task-system/src
LOCAL_CFLAGS := \
	-fvisibility=hidden \
	-DENABLE_STACK_OVERFLOW_DETECTION \
	-DENABLE_MEMALLOC_SLICE \
	-DENABLE_IO_SPLICE_SYSCALL \
	-DCONFIG_STACK_BACKEND=STACK_MMAP \
	-DCONFIG_STACK_OVERFLOW_DETECTION=1 \
	-DCONFIG_MEMALLOC_SLICE_ALIGN=16 \
	-DCONFIG_MEMALLOC_SLICE_MAX_SIZE=4096 \
	-DCONFIG_MEMALLOC_SLICE_MAX_COUNT=1000 \
	-DCONFIG_SCHED_CLOCK=CLOCK_NONE
ifeq ($(TARGET_ARCH_ABI),armeabi-v7a)
LOCAL_CFLAGS += -mfpu=neon
endif
include $(BUILD_STATIC_LIBRARY)

# --- libhev-socks5-tunnel (static) ---
include $(CLEAR_VARS)
LOCAL_MODULE := hev-socks5-tunnel
rwildcard2=$(foreach d,$(wildcard $1*),$(call rwildcard2,$d/,$2) $(filter $(subst *,%,$2),$d))
LOCAL_SRC_FILES := $(call rwildcard2,$(HEV_SOCKS5_TUNNEL_DIR)/src/,*.c)
LOCAL_C_INCLUDES := \
	$(HEV_SOCKS5_TUNNEL_DIR)/src \
	$(HEV_SOCKS5_TUNNEL_DIR)/src/misc \
	$(HEV_SOCKS5_TUNNEL_DIR)/src/core/include \
	$(HEV_SOCKS5_TUNNEL_DIR)/third-part/yaml/src \
	$(HEV_SOCKS5_TUNNEL_DIR)/third-part/lwip/src/include \
	$(HEV_SOCKS5_TUNNEL_DIR)/third-part/lwip/src/ports/include \
	$(HEV_SOCKS5_TUNNEL_DIR)/third-part/hev-task-system/include
LOCAL_CFLAGS := \
	-DFD_SET_DEFINED \
	-DSOCKLEN_T_DEFINED \
	-DENABLE_LIBRARY
ifeq ($(TARGET_ARCH_ABI),armeabi-v7a)
LOCAL_CFLAGS += -mfpu=neon
endif
LOCAL_STATIC_LIBRARIES := yaml lwip hev-task-system
include $(BUILD_STATIC_LIBRARY)

# --- vpnbridge executable ---
include $(CLEAR_VARS)
LOCAL_MODULE := vpnbridge
LOCAL_SRC_FILES := main.c
LOCAL_C_INCLUDES := \
	$(HEV_SOCKS5_TUNNEL_DIR)/include
LOCAL_LDLIBS := -llog
LOCAL_LDFLAGS := -Wl,-z,max-page-size=16384
LOCAL_STATIC_LIBRARIES := hev-socks5-tunnel yaml lwip hev-task-system
# ndk-build strips the "lib" prefix from shared libs and adds it to executables,
# but we want the output named libvpnbridge.so for APK packaging.
# We use BUILD_EXECUTABLE and rename afterwards via a post-build step.
include $(BUILD_EXECUTABLE)

# Rename the executable to lib*.so for APK jniLibs packaging
# (ndk-build places executables in a different directory than libs)
$(shell mkdir -p $(NDK_LIBS_OUT)/$(TARGET_ARCH_ABI))
$(shell cp -f $(LOCAL_BUILT_MODULE) $(NDK_LIBS_OUT)/$(TARGET_ARCH_ABI)/libvpnbridge.so 2>/dev/null || true)
