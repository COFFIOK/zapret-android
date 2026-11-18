# ByeDPI отдельной программой (ciadpi) — для теста стратегий: на каждую стратегию свой процесс и свой порт.
# После сборки gradle переименовывает её в libciadpi.so, чтобы Android положил её рядом с библиотеками (там её можно запускать).
LOCAL_PATH := $(call my-dir)
include $(CLEAR_VARS)
LOCAL_MODULE := ciadpi
BYEDPI := ../../cpp/byedpi
LOCAL_SRC_FILES := $(BYEDPI)/packets.c $(BYEDPI)/main.c $(BYEDPI)/conev.c $(BYEDPI)/proxy.c $(BYEDPI)/desync.c $(BYEDPI)/mpool.c $(BYEDPI)/extend.c
LOCAL_C_INCLUDES := $(LOCAL_PATH)/$(BYEDPI)
LOCAL_CFLAGS := -D_DEFAULT_SOURCE -std=c99 -O2 -Wno-unused
LOCAL_LDFLAGS := -Wl,-z,max-page-size=16384
include $(BUILD_EXECUTABLE)
