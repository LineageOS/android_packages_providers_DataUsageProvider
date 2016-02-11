LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)

LOCAL_MODULE_TAGS := optional

# LOCAL_JAVA_LIBRARIES := bouncycastle conscrypt telephony-common ims-common

LOCAL_STATIC_JAVA_LIBRARIES := \
    org.cyanogenmod.platform.sdk \
    android-support-v4 \
	android-support-v13 \
    gson

LOCAL_SRC_FILES := $(call all-java-files-under, src)

LOCAL_RESOURCE_DIR := $(LOCAL_PATH)/res

LOCAL_JAVA_LIBRARIES := 

LOCAL_PACKAGE_NAME := DataUsageProvider
LOCAL_CERTIFICATE := platform
LOCAL_PRIVILEGED_MODULE := true

# include frameworks/opt/setupwizard/navigationbar/common.mk
# include frameworks/opt/setupwizard/library/common.mk
# include frameworks/base/packages/SettingsLib/common.mk

include $(BUILD_PACKAGE)
