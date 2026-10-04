#define XR_USE_PLATFORM_ANDROID
#define XR_USE_GRAPHICS_API_OPENGL_ES
#define XR_USE_GRAPHICS_API_VULKAN
#include <jni.h>
#include <EGL/egl.h>
#include <vulkan/vulkan.h>
#include <openxr/openxr.h>
#include <openxr/openxr_platform.h>
#include <sstream>
#include <vector>
#include <cstring>
#include <mutex>

struct DebugMessages {
    std::mutex mutex;
    std::string text;
};
static XrBool32 XRAPI_PTR captureDebug(XrDebugUtilsMessageSeverityFlagsEXT,
    XrDebugUtilsMessageTypeFlagsEXT,const XrDebugUtilsMessengerCallbackDataEXT *data,void *user)
{
    auto *log=static_cast<DebugMessages*>(user);
    if(!log||!data||!data->message)return XR_FALSE;
    std::lock_guard<std::mutex> guard(log->mutex);
    if(log->text.size()<16000) {
        std::string line=std::string(data->functionName?data->functionName:"")+": "+data->message+"\n";
        log->text.append(line,0,16000-log->text.size());
    }
    return XR_FALSE;
}
static const char *resultName(XrResult result) {
    switch(result) {
        case XR_SUCCESS:return "XR_SUCCESS";
        case XR_ERROR_RUNTIME_UNAVAILABLE:return "XR_ERROR_RUNTIME_UNAVAILABLE";
        case XR_ERROR_RUNTIME_FAILURE:return "XR_ERROR_RUNTIME_FAILURE";
        case XR_ERROR_INITIALIZATION_FAILED:return "XR_ERROR_INITIALIZATION_FAILED";
        case XR_ERROR_EXTENSION_NOT_PRESENT:return "XR_ERROR_EXTENSION_NOT_PRESENT";
        case XR_ERROR_VALIDATION_FAILURE:return "XR_ERROR_VALIDATION_FAILURE";
        case XR_ERROR_API_VERSION_UNSUPPORTED:return "XR_ERROR_API_VERSION_UNSUPPORTED";
        default:return "outro resultado OpenXR";
    }
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_nexa_xr_RuntimeProbeActivity_probe(JNIEnv *env,jclass,jobject activity)
{
    std::ostringstream report;
    auto finish=[&]{return env->NewStringUTF(report.str().c_str());};
    auto status=[&](const char *stage,XrResult result){report<<stage<<": "<<static_cast<int>(result)<<" ("<<resultName(result)<<")\n";return XR_SUCCEEDED(result);};
    PFN_xrVoidFunction function=nullptr;
    XrResult result=xrGetInstanceProcAddr(XR_NULL_HANDLE,"xrInitializeLoaderKHR",&function);
    if(!status("Localizar inicialização Android",result)||!function)return finish();
    JavaVM *vm=nullptr;env->GetJavaVM(&vm);
    XrLoaderInitInfoAndroidKHR loader{XR_TYPE_LOADER_INIT_INFO_ANDROID_KHR};loader.applicationVM=vm;loader.applicationContext=activity;
    result=reinterpret_cast<PFN_xrInitializeLoaderKHR>(function)(reinterpret_cast<const XrLoaderInitInfoBaseHeaderKHR*>(&loader));
    if(!status("Inicializar loader",result))return finish();
    uint32_t count=0;result=xrEnumerateInstanceExtensionProperties(nullptr,0,&count,nullptr);
    if(!status("Descobrir extensões do runtime",result)||count==0||count>1024)return finish();
    std::vector<XrExtensionProperties> properties(count,{XR_TYPE_EXTENSION_PROPERTIES});
    result=xrEnumerateInstanceExtensionProperties(nullptr,count,&count,properties.data());
    if(!status("Ler extensões",result)||count>properties.size())return finish();properties.resize(count);
    auto has=[&](const char *name){for(const auto &p:properties)if(std::strcmp(p.extensionName,name)==0)return true;return false;};
    const char *android=XR_KHR_ANDROID_CREATE_INSTANCE_EXTENSION_NAME,*gles=XR_KHR_OPENGL_ES_ENABLE_EXTENSION_NAME;
    const char *vk=XR_KHR_VULKAN_ENABLE_EXTENSION_NAME,*vk2=XR_KHR_VULKAN_ENABLE2_EXTENSION_NAME;
    report<<"OpenGL ES no runtime: "<<(has(gles)?"disponível":"ausente")<<"\nVulkan no runtime: "<<(has(vk)||has(vk2)?"disponível":"ausente")<<"\n";
    if(!has(android)){report<<"Runtime sem extensão Android necessária.\n";return finish();}
    std::vector<const char*> enabled{android};for(const char *name:{gles,vk,vk2})if(has(name))enabled.push_back(name);
    DebugMessages debugMessages;
    XrDebugUtilsMessengerCreateInfoEXT debug{XR_TYPE_DEBUG_UTILS_MESSENGER_CREATE_INFO_EXT};
    debug.messageSeverities=XR_DEBUG_UTILS_MESSAGE_SEVERITY_ERROR_BIT_EXT|XR_DEBUG_UTILS_MESSAGE_SEVERITY_WARNING_BIT_EXT;
    debug.messageTypes=XR_DEBUG_UTILS_MESSAGE_TYPE_GENERAL_BIT_EXT|XR_DEBUG_UTILS_MESSAGE_TYPE_VALIDATION_BIT_EXT;
    debug.userCallback=captureDebug;debug.userData=&debugMessages;
    if(has(XR_EXT_DEBUG_UTILS_EXTENSION_NAME))enabled.push_back(XR_EXT_DEBUG_UTILS_EXTENSION_NAME);
    report<<"Extensões habilitadas:";for(const char *name:enabled)report<<" "<<name;report<<"\n";
    XrInstanceCreateInfoAndroidKHR androidInfo{XR_TYPE_INSTANCE_CREATE_INFO_ANDROID_KHR};androidInfo.applicationVM=vm;androidInfo.applicationActivity=activity;
    if(has(XR_EXT_DEBUG_UTILS_EXTENSION_NAME))androidInfo.next=&debug;
    XrInstanceCreateInfo create{XR_TYPE_INSTANCE_CREATE_INFO};create.next=&androidInfo;std::strcpy(create.applicationInfo.applicationName,"NEXA runtime check");create.applicationInfo.apiVersion=XR_MAKE_VERSION(1,0,0);create.enabledExtensionCount=static_cast<uint32_t>(enabled.size());create.enabledExtensionNames=enabled.data();
    XrInstance instance=XR_NULL_HANDLE;result=xrCreateInstance(&create,&instance);
    if(!status("Criar instância OpenXR",result)){
        std::lock_guard<std::mutex> guard(debugMessages.mutex);
        report<<"Mensagens da criação OpenXR:\n"<<(debugMessages.text.empty()?"Nenhuma mensagem disponibilizada pelo runtime.\n":debugMessages.text);
        report<<"O teste parou antes de obter dispositivo, sessão ou poses.\n";return finish();
    }
    XrInstanceProperties instanceInfo{XR_TYPE_INSTANCE_PROPERTIES};result=xrGetInstanceProperties(instance,&instanceInfo);
    if(status("Identificar runtime",result))report<<"Runtime: "<<instanceInfo.runtimeName<<"\n";
    XrSystemGetInfo systemInfo{XR_TYPE_SYSTEM_GET_INFO};systemInfo.formFactor=XR_FORM_FACTOR_HEAD_MOUNTED_DISPLAY;
    XrSystemId system=XR_NULL_SYSTEM_ID;result=xrGetSystem(instance,&systemInfo,&system);
    if(status("Obter dispositivo VR",result)) {
        XrSystemProperties systemProperties{XR_TYPE_SYSTEM_PROPERTIES};result=xrGetSystemProperties(instance,system,&systemProperties);
        if(status("Ler dispositivo",result))report<<"Dispositivo: "<<systemProperties.systemName<<"\nRastreamento anunciado: orientação="<<systemProperties.trackingProperties.orientationTracking<<", posição="<<systemProperties.trackingProperties.positionTracking<<"\n";
        if(has(gles)) {
            function=nullptr;result=xrGetInstanceProcAddr(instance,"xrGetOpenGLESGraphicsRequirementsKHR",&function);
            if(status("Localizar requisitos GLES",result)&&function){XrGraphicsRequirementsOpenGLESKHR requirements{XR_TYPE_GRAPHICS_REQUIREMENTS_OPENGL_ES_KHR};status("Consultar requisitos GLES",reinterpret_cast<PFN_xrGetOpenGLESGraphicsRequirementsKHR>(function)(instance,system,&requirements));}
        }
        if(has(vk2)||has(vk)) {
            const char *name=has(vk2)?"xrGetVulkanGraphicsRequirements2KHR":"xrGetVulkanGraphicsRequirementsKHR";
            function=nullptr;result=xrGetInstanceProcAddr(instance,name,&function);
            if(status("Localizar requisitos Vulkan",result)&&function){XrGraphicsRequirementsVulkanKHR requirements{XR_TYPE_GRAPHICS_REQUIREMENTS_VULKAN_KHR};status("Consultar requisitos Vulkan",reinterpret_cast<PFN_xrGetVulkanGraphicsRequirementsKHR>(function)(instance,system,&requirements));}
        }
    }
    xrDestroyInstance(instance);
    {std::lock_guard<std::mutex> guard(debugMessages.mutex);if(!debugMessages.text.empty())report<<"Mensagens OpenXR:\n"<<debugMessages.text;}
    report<<"\nEsta verificação não mede poses, controles, renderização ou gameplay.\nExtensões anunciadas:\n";
    for(const auto &p:properties)report<<p.extensionName<<"\n";
    return finish();
}
