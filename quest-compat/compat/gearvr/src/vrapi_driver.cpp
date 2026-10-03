// Experimental proc-address entry for the legacy loader contract from the supplied source.
// Acceptance by any particular game loader and SDK ABI still requires device testing.

#include <dlfcn.h>
#include <jni.h>
#include <android/log.h>

#include <cstring>
#include <string>

namespace {

void *
self()
{
	Dl_info info = {};
	if (dladdr(reinterpret_cast<void *>(&self), &info) == 0 || info.dli_fname == nullptr) {
		return nullptr;
	}
	return dlopen(info.dli_fname, RTLD_NOW | RTLD_NOLOAD);
}

// The loader asks for every VrApi function by its exported name.
void *
get_proc(const char *name)
{
	static void *handle = self();
	if (handle == nullptr || name == nullptr) {
		return nullptr;
	}
	void *function = dlsym(handle, name);
	// SubmitFrame2_temp has a different signature; never alias it to SubmitFrame2.
	if (function == nullptr) {
		__android_log_print(ANDROID_LOG_WARN, "Compatibility-Layer-VrApi", "driver: no %s", name);
	}
	return function;
}

} // namespace

extern "C" __attribute__((visibility("default"))) JNIEXPORT jlong JNICALL
Java_com_oculus_systemdriver_DriverLoader_procAddress(JNIEnv *, jclass)
{
	__android_log_print(ANDROID_LOG_INFO, "Compatibility-Layer-VrApi", "Compatibility-Layer VrApi driver loaded");
	return static_cast<jlong>(reinterpret_cast<intptr_t>(&get_proc));
}
