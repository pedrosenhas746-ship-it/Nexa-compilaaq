#include "xr_backend.h"

#include <android/log.h>

#include <cstdio>
#include <cmath>
#include <algorithm>
#include <cstdlib>
#include <cstring>
#include <string>

#include <unistd.h>

#define LOG(...) __android_log_print(ANDROID_LOG_INFO, "Compatibility-Layer-GearVR", __VA_ARGS__)
#define FAIL(result, what) (LOG("%s failed: %d", what, static_cast<int>(result)), false)

namespace compatibility_layer {

namespace {

XrPath
path(XrInstance instance, const char *text)
{
	XrPath value = XR_NULL_PATH;
	xrStringToPath(instance, text, &value);
	return value;
}

XrAction
make_action(XrActionSet set, XrActionType type, const char *name, const char *localized, const XrPath *hands)
{
	XrActionCreateInfo info{XR_TYPE_ACTION_CREATE_INFO};
	info.actionType = type;
	std::strncpy(info.actionName, name, XR_MAX_ACTION_NAME_SIZE - 1);
	std::strncpy(info.localizedActionName, localized, XR_MAX_LOCALIZED_ACTION_NAME_SIZE - 1);
	info.countSubactionPaths = 2;
	info.subactionPaths = hands;
	XrAction action = XR_NULL_HANDLE;
	xrCreateAction(set, &info, &action);
	return action;
}

// Loaders are not required to export extension functions, so they are looked up at runtime.
template <typename Function>
Function
lookup(XrInstance instance, const char *name)
{
	PFN_xrVoidFunction function = nullptr;
	xrGetInstanceProcAddr(instance, name, &function);
	return reinterpret_cast<Function>(function);
}



} // namespace

bool
XrBackend::initialize(JavaVM *vm, jobject activity)
{
    const bool ok = initialize_impl(vm, activity);
    if (!ok) shutdown();
    return ok;
}

bool
XrBackend::initialize_impl(JavaVM *vm, jobject activity)
{
	if (instance_ != XR_NULL_HANDLE) {
		return true;
	}
	// The loader may only be initialized once per process.
	static bool loader_ready = false;
	XrResult result = XR_SUCCESS;
	if (!loader_ready) {
		XrLoaderInitInfoAndroidKHR loader{XR_TYPE_LOADER_INIT_INFO_ANDROID_KHR};
		loader.applicationVM = vm;
		loader.applicationContext = activity;
		auto initialize_loader = lookup<XrResult (*)(const XrLoaderInitInfoBaseHeaderKHR *)>(
		    XR_NULL_HANDLE, "xrInitializeLoaderKHR");
		if (initialize_loader == nullptr) {
			LOG("Compatibility-Layer loader has no xrInitializeLoaderKHR");
			return false;
		}
		result = initialize_loader(reinterpret_cast<const XrLoaderInitInfoBaseHeaderKHR *>(&loader));
		if (XR_FAILED(result)) {
			return FAIL(result, "xrInitializeLoaderKHR");
		}
		loader_ready = true;
	}

	uint32_t available_count = 0;
	result = xrEnumerateInstanceExtensionProperties(nullptr, 0, &available_count, nullptr);
	if (XR_FAILED(result) || available_count == 0 || available_count > 1024) return false;
	std::vector<XrExtensionProperties> available(available_count, {XR_TYPE_EXTENSION_PROPERTIES});
	result = xrEnumerateInstanceExtensionProperties(nullptr, available_count, &available_count, available.data());
	if (XR_FAILED(result) || available_count > available.size()) return false;
	available.resize(available_count);
	bool has_timespec = false, has_android = false, has_gles = false;
	for (const XrExtensionProperties &extension : available) {
		has_timespec |= std::strcmp(extension.extensionName, XR_KHR_CONVERT_TIMESPEC_TIME_EXTENSION_NAME) == 0;
        has_android |= std::strcmp(extension.extensionName, XR_KHR_ANDROID_CREATE_INSTANCE_EXTENSION_NAME) == 0;
        has_gles |= std::strcmp(extension.extensionName, XR_KHR_OPENGL_ES_ENABLE_EXTENSION_NAME) == 0;
	}

	if (!has_android || !has_gles) { LOG("Runtime missing required Android/GLES extensions: android=%d GLES=%d", has_android, has_gles); return false; }
	if (!has_timespec) { LOG("Runtime lacks clock conversion required by the VrApi adapter"); return false; }
	std::vector<const char *> extensions = {XR_KHR_ANDROID_CREATE_INSTANCE_EXTENSION_NAME,
	                                        XR_KHR_OPENGL_ES_ENABLE_EXTENSION_NAME};
	if (has_timespec) {
		extensions.push_back(XR_KHR_CONVERT_TIMESPEC_TIME_EXTENSION_NAME);
	}
	XrInstanceCreateInfoAndroidKHR android{XR_TYPE_INSTANCE_CREATE_INFO_ANDROID_KHR};
	android.applicationVM = vm;
	android.applicationActivity = activity;

	XrInstanceCreateInfo info{XR_TYPE_INSTANCE_CREATE_INFO};
	info.next = &android;
	std::strcpy(info.applicationInfo.applicationName, "Compatibility-Layer Gear VR adapter");
	std::strcpy(info.applicationInfo.engineName, "VrApi");
	info.applicationInfo.apiVersion = XR_MAKE_VERSION(1, 0, 0);
	info.enabledExtensionCount = static_cast<uint32_t>(extensions.size());
	info.enabledExtensionNames = extensions.data();
	result = xrCreateInstance(&info, &instance_);
	if (XR_FAILED(result)) {
		instance_ = XR_NULL_HANDLE;
		return FAIL(result, "xrCreateInstance");
	}
	if (has_timespec) {
		timespec_to_time_ = lookup<TimespecToTime>(instance_, "xrConvertTimespecTimeToTimeKHR");
		time_to_timespec_ = lookup<TimeToTimespec>(instance_, "xrConvertTimeToTimespecTimeKHR");
		if (!timespec_to_time_ || !time_to_timespec_) return false;
	}

	XrSystemGetInfo system{XR_TYPE_SYSTEM_GET_INFO};
	system.formFactor = XR_FORM_FACTOR_HEAD_MOUNTED_DISPLAY;
	result = xrGetSystem(instance_, &system, &system_);
	if (XR_FAILED(result)) {
		return FAIL(result, "xrGetSystem");
	}

	uint32_t count = 0;
	result = xrEnumerateViewConfigurationViews(instance_, system_, XR_VIEW_CONFIGURATION_TYPE_PRIMARY_STEREO, 0, &count, nullptr);
	if (XR_FAILED(result) || count != 2) return false;
	std::vector<XrViewConfigurationView> views(count, {XR_TYPE_VIEW_CONFIGURATION_VIEW});
	result = xrEnumerateViewConfigurationViews(instance_, system_, XR_VIEW_CONFIGURATION_TYPE_PRIMARY_STEREO, count, &count, views.data());
	if (XR_FAILED(result) || count != 2) return false;
	if (!views.empty()) {
		recommended_width_ = views[0].recommendedImageRectWidth;
		recommended_height_ = views[0].recommendedImageRectHeight;
	}

	// Compatibility-Layer requires this query before a GLES session can be created.
	XrGraphicsRequirementsOpenGLESKHR requirements{XR_TYPE_GRAPHICS_REQUIREMENTS_OPENGL_ES_KHR};
	auto graphics_requirements = lookup<XrResult (*)(XrInstance, XrSystemId, XrGraphicsRequirementsOpenGLESKHR *)>(
	    instance_, "xrGetOpenGLESGraphicsRequirementsKHR");
	if (graphics_requirements == nullptr) {
		LOG("Runtime does not support OpenGL ES");
		return false;
	}
	result = graphics_requirements(instance_, system_, &requirements);
	if (XR_FAILED(result)) return FAIL(result, "graphics requirements");

	hands_ = {path(instance_, "/user/hand/left"), path(instance_, "/user/hand/right")};
	LOG("Compatibility-Layer ready, eye buffer %ux%u", recommended_width_, recommended_height_);
	return create_actions();
}

void
XrBackend::shutdown()
{
	end_session();
	if (instance_ != XR_NULL_HANDLE) {
		xrDestroyInstance(instance_);
	}
	*this = XrBackend();
}

bool
XrBackend::create_actions()
{
	XrActionSetCreateInfo info{XR_TYPE_ACTION_SET_CREATE_INFO};
	std::strcpy(info.actionSetName, "gearvr");
	std::strcpy(info.localizedActionSetName, "Gear VR");
	XrResult result = xrCreateActionSet(instance_, &info, &action_set_);
	if (XR_FAILED(result)) {
		return FAIL(result, "xrCreateActionSet");
	}

	grip_pose_ = make_action(action_set_, XR_ACTION_TYPE_POSE_INPUT, "grip_pose", "Grip", hands_.data());
	aim_pose_ = make_action(action_set_, XR_ACTION_TYPE_POSE_INPUT, "aim_pose", "Aim", hands_.data());
	trigger_ = make_action(action_set_, XR_ACTION_TYPE_FLOAT_INPUT, "trigger", "Trigger", hands_.data());
	squeeze_ = make_action(action_set_, XR_ACTION_TYPE_FLOAT_INPUT, "squeeze", "Squeeze", hands_.data());
	primary_ = make_action(action_set_, XR_ACTION_TYPE_BOOLEAN_INPUT, "primary", "Primary", hands_.data());
	secondary_ = make_action(action_set_, XR_ACTION_TYPE_BOOLEAN_INPUT, "secondary", "Secondary", hands_.data());
	menu_ = make_action(action_set_, XR_ACTION_TYPE_BOOLEAN_INPUT, "menu", "Menu", hands_.data());
	thumbstick_ = make_action(action_set_, XR_ACTION_TYPE_VECTOR2F_INPUT, "thumbstick", "Thumbstick", hands_.data());

	haptic_ = make_action(action_set_, XR_ACTION_TYPE_VIBRATION_OUTPUT, "haptic", "Haptic", hands_.data());
	for (XrAction action : {haptic_, grip_pose_, aim_pose_, trigger_, squeeze_, primary_, secondary_, menu_, thumbstick_}) {
		if (action == XR_NULL_HANDLE) return false;
	}
	// Request a standard interaction profile; the runtime must actually supply inputs.
	const char *profile = "/interaction_profiles/oculus/touch_controller";
	std::vector<XrActionSuggestedBinding> bindings = {
	    {grip_pose_, path(instance_, "/user/hand/left/input/grip/pose")},
	    {grip_pose_, path(instance_, "/user/hand/right/input/grip/pose")},
	    {aim_pose_, path(instance_, "/user/hand/left/input/aim/pose")},
	    {aim_pose_, path(instance_, "/user/hand/right/input/aim/pose")},
	    {trigger_, path(instance_, "/user/hand/left/input/trigger/value")},
	    {trigger_, path(instance_, "/user/hand/right/input/trigger/value")},
	    {squeeze_, path(instance_, "/user/hand/left/input/squeeze/value")},
	    {squeeze_, path(instance_, "/user/hand/right/input/squeeze/value")},
	    {primary_, path(instance_, "/user/hand/left/input/x/click")},
	    {primary_, path(instance_, "/user/hand/right/input/a/click")},
	    {secondary_, path(instance_, "/user/hand/left/input/y/click")},
	    {secondary_, path(instance_, "/user/hand/right/input/b/click")},
	    {menu_, path(instance_, "/user/hand/left/input/menu/click")},
	    {thumbstick_, path(instance_, "/user/hand/left/input/thumbstick")},
	    {thumbstick_, path(instance_, "/user/hand/right/input/thumbstick")},
        {haptic_, path(instance_, "/user/hand/left/output/haptic")},
        {haptic_, path(instance_, "/user/hand/right/output/haptic")},
	};
	XrInteractionProfileSuggestedBinding suggested{XR_TYPE_INTERACTION_PROFILE_SUGGESTED_BINDING};
	suggested.interactionProfile = path(instance_, profile);
	suggested.countSuggestedBindings = static_cast<uint32_t>(bindings.size());
	suggested.suggestedBindings = bindings.data();
    const XrResult touch_result = xrSuggestInteractionProfileBindings(instance_, &suggested);
    // Also bind the portable profile for runtimes whose devices aren't Oculus Touch.
    std::vector<XrActionSuggestedBinding> simple;
    for (const char *hand : {"left", "right"}) {
        const std::string prefix = std::string("/user/hand/") + hand;
        simple.push_back({grip_pose_, path(instance_, (prefix + "/input/grip/pose").c_str())});
        simple.push_back({aim_pose_, path(instance_, (prefix + "/input/aim/pose").c_str())});
        simple.push_back({primary_, path(instance_, (prefix + "/input/select/click").c_str())});
        simple.push_back({trigger_, path(instance_, (prefix + "/input/select/click").c_str())});
        simple.push_back({menu_, path(instance_, (prefix + "/input/menu/click").c_str())});
        simple.push_back({haptic_, path(instance_, (prefix + "/output/haptic").c_str())});
    }
    suggested.interactionProfile = path(instance_, "/interaction_profiles/khr/simple_controller");
    suggested.countSuggestedBindings = static_cast<uint32_t>(simple.size());suggested.suggestedBindings = simple.data();
    const XrResult simple_result = xrSuggestInteractionProfileBindings(instance_, &suggested);
    if (XR_FAILED(touch_result)) LOG("Touch bindings rejected: %d", static_cast<int>(touch_result));
    if (XR_FAILED(simple_result)) LOG("Simple bindings rejected: %d", static_cast<int>(simple_result));
    if (XR_FAILED(touch_result) && XR_FAILED(simple_result)) return false;
	return true;
}

bool
XrBackend::begin_session(EGLDisplay display, EGLConfig config, EGLContext context)
{
	XrGraphicsBindingOpenGLESAndroidKHR binding{XR_TYPE_GRAPHICS_BINDING_OPENGL_ES_ANDROID_KHR};
	binding.display = display;
	binding.config = config;
	binding.context = context;
	XrSessionCreateInfo info{XR_TYPE_SESSION_CREATE_INFO};
	info.next = &binding;
	info.systemId = system_;
	XrResult result = xrCreateSession(instance_, &info, &session_);
	if (XR_FAILED(result)) {
		return FAIL(result, "xrCreateSession");
	}

	XrReferenceSpaceCreateInfo space{XR_TYPE_REFERENCE_SPACE_CREATE_INFO};
	local_origin_ = {{0, 0, 0, 1}, {0, 0, 0}};
	space.poseInReferenceSpace = local_origin_;
	space.referenceSpaceType = XR_REFERENCE_SPACE_TYPE_LOCAL;
	if (XR_FAILED(xrCreateReferenceSpace(session_, &space, &local_space_))) { end_session(); return false; }
	space.referenceSpaceType = XR_REFERENCE_SPACE_TYPE_VIEW;
	if (XR_FAILED(xrCreateReferenceSpace(session_, &space, &view_space_))) { end_session(); return false; }

	for (int hand = 0; hand < 2; hand++) {
		XrActionSpaceCreateInfo action_space{XR_TYPE_ACTION_SPACE_CREATE_INFO};
		action_space.poseInActionSpace.orientation.w = 1;
		action_space.subactionPath = hands_[hand];
		action_space.action = grip_pose_;
		if (XR_FAILED(xrCreateActionSpace(session_, &action_space, &grip_spaces_[hand]))) { end_session(); return false; }
		action_space.action = aim_pose_;
		if (XR_FAILED(xrCreateActionSpace(session_, &action_space, &aim_spaces_[hand]))) { end_session(); return false; }
	}

	XrSessionActionSetsAttachInfo attach{XR_TYPE_SESSION_ACTION_SETS_ATTACH_INFO};
	attach.countActionSets = 1;
	attach.actionSets = &action_set_;
	if (XR_FAILED(xrAttachSessionActionSets(session_, &attach))) { end_session(); return false; }

	uint32_t count = 0;
	result = xrEnumerateSwapchainFormats(session_, 0, &count, nullptr);
	if (XR_FAILED(result) || count == 0 || count > 4096) { end_session(); return false; }
	formats_.assign(count, 0);
	result = xrEnumerateSwapchainFormats(session_, count, &count, formats_.data());
	if (XR_FAILED(result) || count > formats_.size()) { end_session(); return false; }
	formats_.resize(count);
	return true;
}

void
XrBackend::end_session()
{
	if (session_ == XR_NULL_HANDLE) {
		return;
	}
	if (running_) {
		xrRequestExitSession(session_);
		// Let the runtime walk the session through STOPPING before it is destroyed.
		for (int attempt = 0; attempt < 100 && running_ && poll_events(); attempt++) {
		}
	}
	xrDestroySession(session_);
	session_ = XR_NULL_HANDLE;
	local_space_ = view_space_ = XR_NULL_HANDLE;
	grip_spaces_ = {};
	aim_spaces_ = {};
	state_ = XR_SESSION_STATE_UNKNOWN;
	running_ = false;
	frame_begun_ = false;
	controllers_ = {};
	formats_.clear();
}

bool
XrBackend::poll_events()
{
	XrEventDataBuffer event{XR_TYPE_EVENT_DATA_BUFFER};
	while (xrPollEvent(instance_, &event) == XR_SUCCESS) {
		if (event.type == XR_TYPE_EVENT_DATA_SESSION_STATE_CHANGED) {
			const auto *changed = reinterpret_cast<XrEventDataSessionStateChanged *>(&event);
			if (changed->session != session_) { event = {XR_TYPE_EVENT_DATA_BUFFER}; continue; }
			state_ = changed->state;
			if (state_ != XR_SESSION_STATE_FOCUSED) controllers_ = {};
			LOG("session state %d", static_cast<int>(state_));
			if (state_ == XR_SESSION_STATE_READY) {
				XrSessionBeginInfo begin{XR_TYPE_SESSION_BEGIN_INFO};
				begin.primaryViewConfigurationType = XR_VIEW_CONFIGURATION_TYPE_PRIMARY_STEREO;
				running_ = XR_SUCCEEDED(xrBeginSession(session_, &begin));
			} else if (state_ == XR_SESSION_STATE_STOPPING) {
				xrEndSession(session_);
				running_ = false;
				frame_begun_ = false;
			} else if (state_ == XR_SESSION_STATE_EXITING || state_ == XR_SESSION_STATE_LOSS_PENDING) {
				running_ = false;
				frame_begun_ = false;
				return false;
			}
		} else if (event.type == XR_TYPE_EVENT_DATA_INSTANCE_LOSS_PENDING) {
			running_ = frame_begun_ = false;
			controllers_ = {};
			return false;
		}
		event = {XR_TYPE_EVENT_DATA_BUFFER};
	}
	return true;
}

FrameTiming
XrBackend::wait_frame()
{
	FrameTiming timing;
	if (!running_) {
		return timing;
	}
	XrFrameWaitInfo wait{XR_TYPE_FRAME_WAIT_INFO};
	XrFrameState state{XR_TYPE_FRAME_STATE};
	if (XR_SUCCEEDED(xrWaitFrame(session_, &wait, &state))) {
		timing.predicted_display_time = state.predictedDisplayTime;
		timing.predicted_display_period = state.predictedDisplayPeriod;
		timing.should_render = state.shouldRender == XR_TRUE;
	}
	return timing;
}

bool
XrBackend::begin_frame()
{
    if (!running_) return false;
    if (frame_begun_) return true;
    XrFrameBeginInfo info{XR_TYPE_FRAME_BEGIN_INFO};
    const XrResult result = xrBeginFrame(session_, &info);
    // XR_FRAME_DISCARDED is a qualified success: the new frame is begun.
    frame_begun_ = XR_SUCCEEDED(result);
    if (!frame_begun_) LOG("xrBeginFrame failed: %d", static_cast<int>(result));
    return frame_begun_;
}

double
XrBackend::monotonic_seconds()
{
	struct timespec now{};
	clock_gettime(CLOCK_MONOTONIC, &now);
	return static_cast<double>(now.tv_sec) + static_cast<double>(now.tv_nsec) * 1e-9;
}

XrTime
XrBackend::to_xr_time(double seconds) const
{
	auto nanoseconds = static_cast<int64_t>(seconds * 1e9);
	if (timespec_to_time_ == nullptr) {
		return nanoseconds;
	}
	struct timespec value{};
	value.tv_sec = static_cast<time_t>(nanoseconds / 1000000000);
	value.tv_nsec = static_cast<long>(nanoseconds % 1000000000);
	XrTime time = 0;
	return XR_SUCCEEDED(timespec_to_time_(instance_, &value, &time)) ? time : 0;
}

double
XrBackend::to_seconds(XrTime time) const
{
	struct timespec value{};
	if (time_to_timespec_ == nullptr || XR_FAILED(time_to_timespec_(instance_, time, &value))) {
		return static_cast<double>(time) * 1e-9;
	}
	return static_cast<double>(value.tv_sec) + static_cast<double>(value.tv_nsec) * 1e-9;
}

bool
XrBackend::locate_eyes(XrTime time, Pose &head, std::array<Eye, 2> &eyes)
{
	head = locate(view_space_, time);
	XrViewLocateInfo info{XR_TYPE_VIEW_LOCATE_INFO};
	info.viewConfigurationType = XR_VIEW_CONFIGURATION_TYPE_PRIMARY_STEREO;
	info.displayTime = time;
	info.space = local_space_;
	XrViewState state{XR_TYPE_VIEW_STATE};
	std::array<XrView, 2> views{{{XR_TYPE_VIEW}, {XR_TYPE_VIEW}}};
	uint32_t count = 0;
	if (XR_FAILED(xrLocateViews(session_, &info, &state, 2, &count, views.data())) || count != 2) {
		return false;
	}
	bool valid = (state.viewStateFlags & XR_VIEW_STATE_ORIENTATION_VALID_BIT) != 0;
	for (int eye = 0; eye < 2; eye++) {
		eyes[eye].pose.orientation = views[eye].pose.orientation;
		eyes[eye].pose.position = views[eye].pose.position;
		eyes[eye].pose.valid = valid;
		eyes[eye].pose.orientation_tracked = valid && (state.viewStateFlags & XR_VIEW_STATE_ORIENTATION_TRACKED_BIT) != 0;
		eyes[eye].pose.position_valid = (state.viewStateFlags & XR_VIEW_STATE_POSITION_VALID_BIT) != 0;
		eyes[eye].pose.position_tracked = eyes[eye].pose.position_valid && (state.viewStateFlags & XR_VIEW_STATE_POSITION_TRACKED_BIT) != 0;
		eyes[eye].fov = views[eye].fov;
	}
    if (valid && !head.valid) {
        // Some runtimes supply view poses without a separate VIEW-space pose.
        // Derive the head from actual eye data; never claim sensor data that wasn't valid.
        const auto &a = views[0].pose.orientation; const auto &b = views[1].pose.orientation;
        const float sign = a.x*b.x+a.y*b.y+a.z*b.z+a.w*b.w < 0 ? -1.0f : 1.0f;
        XrQuaternionf q{a.x+sign*b.x,a.y+sign*b.y,a.z+sign*b.z,a.w+sign*b.w};
        const float length = std::sqrt(q.x*q.x+q.y*q.y+q.z*q.z+q.w*q.w);
        if (length > 1e-6f) {
            head.orientation = {q.x/length,q.y/length,q.z/length,q.w/length};head.valid = true;
            head.orientation_tracked = eyes[0].pose.orientation_tracked && eyes[1].pose.orientation_tracked;
            head.position_valid = eyes[0].pose.position_valid && eyes[1].pose.position_valid;
            head.position_tracked = eyes[0].pose.position_tracked && eyes[1].pose.position_tracked;
            if (head.position_valid) head.position = {(views[0].pose.position.x+views[1].pose.position.x)*0.5f,
                (views[0].pose.position.y+views[1].pose.position.y)*0.5f,(views[0].pose.position.z+views[1].pose.position.z)*0.5f};
        }
    }
    return valid;
}

bool
XrBackend::recenter()
{
    if (!has_session()) return false;
    const Pose pose = locate(view_space_, to_xr_time(monotonic_seconds()));
    if (!pose.valid) return false;
    const auto &q = pose.orientation;
    const float yaw = std::atan2(2*(q.x*q.z+q.w*q.y), 1-2*(q.x*q.x+q.y*q.y));
    const float sy = std::sin(yaw*0.5f), cy = std::cos(yaw*0.5f);
    const auto &a = local_origin_.orientation;
    XrReferenceSpaceCreateInfo info{XR_TYPE_REFERENCE_SPACE_CREATE_INFO};info.referenceSpaceType=XR_REFERENCE_SPACE_TYPE_LOCAL;
    info.poseInReferenceSpace.orientation={a.x*cy-a.z*sy,a.w*sy+a.y*cy,a.x*sy+a.z*cy,a.w*cy-a.y*sy};
    info.poseInReferenceSpace.position=local_origin_.position;
    if (pose.position_valid) {
        const XrVector3f v=pose.position;
        const XrVector3f t{2*(a.y*v.z-a.z*v.y),2*(a.z*v.x-a.x*v.z),2*(a.x*v.y-a.y*v.x)};
        info.poseInReferenceSpace.position.x+=v.x+a.w*t.x+(a.y*t.z-a.z*t.y);
        info.poseInReferenceSpace.position.y+=v.y+a.w*t.y+(a.z*t.x-a.x*t.z);
        info.poseInReferenceSpace.position.z+=v.z+a.w*t.z+(a.x*t.y-a.y*t.x);
    }
    XrSpace space=XR_NULL_HANDLE;
    if (XR_FAILED(xrCreateReferenceSpace(session_,&info,&space))) return false;
    xrDestroySpace(local_space_);local_space_=space;local_origin_=info.poseInReferenceSpace;
    return true;
}

bool
XrBackend::vibrate(int hand, float amplitude)
{
    if (hand<0 || hand>1 || !has_session() || state_!=XR_SESSION_STATE_FOCUSED || !std::isfinite(amplitude)) return false;
    XrHapticActionInfo info{XR_TYPE_HAPTIC_ACTION_INFO};info.action=haptic_;info.subactionPath=hands_[hand];
    if (amplitude<=0) return XR_SUCCEEDED(xrStopHapticFeedback(session_,&info));
    XrHapticVibration vibration{XR_TYPE_HAPTIC_VIBRATION};vibration.amplitude=std::clamp(amplitude,0.0f,1.0f);
    vibration.duration=50000000;vibration.frequency=XR_FREQUENCY_UNSPECIFIED;
    return XR_SUCCEEDED(xrApplyHapticFeedback(session_,&info,reinterpret_cast<const XrHapticBaseHeader*>(&vibration)));
}

Pose
XrBackend::locate(XrSpace space, XrTime time) const
{
	Pose pose;
	XrSpaceLocation location{XR_TYPE_SPACE_LOCATION};
	if (XR_SUCCEEDED(xrLocateSpace(space, local_space_, time, &location)) &&
	    (location.locationFlags & XR_SPACE_LOCATION_ORIENTATION_VALID_BIT) != 0) {
		pose.orientation = location.pose.orientation;
		pose.position = location.pose.position;
		pose.valid = true;
		pose.orientation_tracked = (location.locationFlags & XR_SPACE_LOCATION_ORIENTATION_TRACKED_BIT) != 0;
		pose.position_valid = (location.locationFlags & XR_SPACE_LOCATION_POSITION_VALID_BIT) != 0;
		pose.position_tracked = pose.position_valid && (location.locationFlags & XR_SPACE_LOCATION_POSITION_TRACKED_BIT) != 0;
	}
	return pose;
}

Swapchain *
XrBackend::create_swapchain(int32_t width, int32_t height, int64_t gl_format, uint32_t samples)
{
	XrSwapchainCreateInfo info{XR_TYPE_SWAPCHAIN_CREATE_INFO};
	info.usageFlags = XR_SWAPCHAIN_USAGE_COLOR_ATTACHMENT_BIT | XR_SWAPCHAIN_USAGE_SAMPLED_BIT;
	info.format = gl_format;
	info.sampleCount = samples == 0 ? 1 : samples;
	info.width = width;
	info.height = height;
	info.faceCount = 1;
	info.arraySize = 1;
	info.mipCount = 1;

	auto *swapchain = new Swapchain();
	swapchain->width = width;
	swapchain->height = height;
	swapchain->format = gl_format;
	XrResult result = xrCreateSwapchain(session_, &info, &swapchain->handle);
	if (XR_FAILED(result)) {
		LOG("xrCreateSwapchain %dx%d format 0x%llx failed: %d", width, height,
		    static_cast<long long>(gl_format), static_cast<int>(result));
		delete swapchain;
		return nullptr;
	}
	uint32_t count = 0;
	result = xrEnumerateSwapchainImages(swapchain->handle, 0, &count, nullptr);
	if (XR_FAILED(result) || count == 0 || count > 256) { destroy_swapchain(swapchain); return nullptr; }
	swapchain->images.assign(count, {XR_TYPE_SWAPCHAIN_IMAGE_OPENGL_ES_KHR});
	result = xrEnumerateSwapchainImages(swapchain->handle, count, &count,
	                           reinterpret_cast<XrSwapchainImageBaseHeader *>(swapchain->images.data()));
	if (XR_FAILED(result) || count == 0 || count > swapchain->images.size()) { destroy_swapchain(swapchain); return nullptr; }
	swapchain->images.resize(count);
	return swapchain;
}

void
XrBackend::destroy_swapchain(Swapchain *swapchain)
{
	if (swapchain == nullptr) {
		return;
	}
	xrDestroySwapchain(swapchain->handle);
	delete swapchain;
}

uint32_t
XrBackend::acquire_image(Swapchain *swapchain)
{
    if (!swapchain || swapchain->failed) return UINT32_MAX;
    if (!swapchain->acquired) {
        XrSwapchainImageAcquireInfo acquire{XR_TYPE_SWAPCHAIN_IMAGE_ACQUIRE_INFO};
        if (xrAcquireSwapchainImage(swapchain->handle, &acquire, &swapchain->acquired_index) != XR_SUCCESS) return UINT32_MAX;
        swapchain->acquired = true;
        if (swapchain->acquired_index >= swapchain->images.size()) { swapchain->failed = true; return UINT32_MAX; }
    }
    if (!swapchain->waited) {
        XrSwapchainImageWaitInfo wait{XR_TYPE_SWAPCHAIN_IMAGE_WAIT_INFO};
        wait.timeout = 100000000; // 100 ms; timeout permits retry of this same acquired image.
        const XrResult result = xrWaitSwapchainImage(swapchain->handle, &wait);
        if (result == XR_TIMEOUT_EXPIRED) return UINT32_MAX;
        if (result != XR_SUCCESS) { swapchain->failed = true; return UINT32_MAX; }
        swapchain->waited = true;
    }
    return swapchain->acquired_index;
}

void
XrBackend::release_image(Swapchain *swapchain)
{
    if (!swapchain || !swapchain->acquired || !swapchain->waited || swapchain->failed) return;
    XrSwapchainImageReleaseInfo release{XR_TYPE_SWAPCHAIN_IMAGE_RELEASE_INFO};
    if (xrReleaseSwapchainImage(swapchain->handle, &release) != XR_SUCCESS) { swapchain->failed = true; return; }
    swapchain->acquired = swapchain->waited = false;
}

void
XrBackend::end_frame(XrTime display_time, const std::vector<ProjectionLayer> &layers)
{
	if (!frame_begun_) {
		return;
	}
	frame_begun_ = false;

	// Views are stored up front so layer structures can point into a vector that no longer grows.
	std::vector<XrCompositionLayerProjectionView> views;
	std::vector<XrCompositionLayerProjection> projections;
	views.reserve(layers.size() * 2);
	projections.reserve(layers.size());
	for (const ProjectionLayer &source : layers) {
		if (source.eyes[0].swapchain == nullptr || source.eyes[1].swapchain == nullptr) {
			continue;
		}
		for (const EyeImage &eye : source.eyes) {
			XrCompositionLayerProjectionView view{XR_TYPE_COMPOSITION_LAYER_PROJECTION_VIEW};
			view.pose.orientation = eye.pose.orientation;
			view.pose.position = eye.pose.position;
			view.fov = eye.fov;
			view.subImage.swapchain = eye.swapchain->handle;
			view.subImage.imageRect = eye.rect;
			views.push_back(view);
		}
		XrCompositionLayerProjection layer{XR_TYPE_COMPOSITION_LAYER_PROJECTION};
		layer.space = local_space_;
		layer.layerFlags = source.alpha_blend ? XR_COMPOSITION_LAYER_BLEND_TEXTURE_SOURCE_ALPHA_BIT : 0;
		layer.viewCount = 2;
		layer.views = &views[views.size() - 2];
		projections.push_back(layer);
	}

	std::vector<const XrCompositionLayerBaseHeader *> headers;
	for (const XrCompositionLayerProjection &layer : projections) {
		headers.push_back(reinterpret_cast<const XrCompositionLayerBaseHeader *>(&layer));
	}

	XrFrameEndInfo info{XR_TYPE_FRAME_END_INFO};
	info.displayTime = display_time;
	info.environmentBlendMode = XR_ENVIRONMENT_BLEND_MODE_OPAQUE;
	// A frame without layers is still ended, just with nothing to show.
	info.layerCount = static_cast<uint32_t>(headers.size());
	info.layers = headers.empty() ? nullptr : headers.data();
	XrResult result = xrEndFrame(session_, &info);
	if (XR_FAILED(result)) {
		LOG("xrEndFrame failed: %d", static_cast<int>(result));
	}
}

void
XrBackend::sync_input(XrTime time)
{
    // Clear before every query: loss of focus, inactive actions and errors must
    // never retain an old trigger, joystick or hand pose.
    controllers_ = {};
    if (!running_ || state_ != XR_SESSION_STATE_FOCUSED) return;
    XrActiveActionSet active{action_set_, XR_NULL_PATH};
    XrActionsSyncInfo sync{XR_TYPE_ACTIONS_SYNC_INFO};
    sync.countActiveActionSets = 1;
    sync.activeActionSets = &active;
    if (xrSyncActions(session_, &sync) != XR_SUCCESS) return;

    for (int hand = 0; hand < 2; hand++) {
        Controller &controller = controllers_[hand];
        XrActionStateGetInfo get{XR_TYPE_ACTION_STATE_GET_INFO};
        get.subactionPath = hands_[hand];
        auto scalar = [&](XrAction action) {
            XrActionStateFloat value{XR_TYPE_ACTION_STATE_FLOAT};
            get.action = action;
            return xrGetActionStateFloat(session_, &get, &value) == XR_SUCCESS && value.isActive
                ? value.currentState : 0.0f;
        };
        auto button = [&](XrAction action) {
            XrActionStateBoolean value{XR_TYPE_ACTION_STATE_BOOLEAN};
            get.action = action;
            return xrGetActionStateBoolean(session_, &get, &value) == XR_SUCCESS &&
                   value.isActive && value.currentState == XR_TRUE;
        };
        auto pose = [&](XrAction action, XrSpace space) {
            XrActionStatePose value{XR_TYPE_ACTION_STATE_POSE};
            get.action = action;
            return xrGetActionStatePose(session_, &get, &value) == XR_SUCCESS && value.isActive
                ? locate(space, time) : Pose{};
        };
        controller.trigger = scalar(trigger_);
        controller.squeeze = scalar(squeeze_);
        controller.primary = button(primary_);
        controller.secondary = button(secondary_);
        controller.menu = button(menu_);
        XrActionStateVector2f stick{XR_TYPE_ACTION_STATE_VECTOR2F};
        get.action = thumbstick_;
        if (xrGetActionStateVector2f(session_, &get, &stick) == XR_SUCCESS && stick.isActive)
            controller.thumbstick = stick.currentState;
        controller.grip = pose(grip_pose_, grip_spaces_[hand]);
        controller.aim = pose(aim_pose_, aim_spaces_[hand]);
        controller.active = controller.grip.valid;
    }
}

} // namespace compatibility_layer
