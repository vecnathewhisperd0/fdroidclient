LOCAL_PATH:= $(call my-dir)

include $(CLEAR_VARS)

LOCAL_MODULE := F-Droid
LOCAL_MODULE_TAGS := optional
LOCAL_MODULE_CLASS := APPS

fdroid_root  := $(LOCAL_PATH)
fdroid_dir   := app
fdroid_out   := $(call intermediates-dir-for,$(LOCAL_MODULE_CLASS),$(LOCAL_MODULE))
fdroid_apk   := $(fdroid_out)/$(fdroid_dir)/outputs/apk/$(fdroid_dir)-release-unsigned.apk

$(fdroid_apk):
	gradle -Dbuild.dir=$(fdroid_out) -p$(fdroid_root)/$(fdroid_dir) assembleRelease

LOCAL_CERTIFICATE := platform
LOCAL_PREBUILT_MODULE_FILE := $(fdroid_apk)
LOCAL_MODULE_SUFFIX := $(COMMON_ANDROID_PACKAGE_SUFFIX)

include $(BUILD_PREBUILT)
