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

extern "C" JNIEXPORT jstring JNICALL
Java_com_nexa_xr_RuntimeProbeActivity_probe(JNIEnv *env,jclass,jobject activity)
{
    std::ostringstream report;
    auto finish=[&]{return env->NewStringUTF(report.str().c_str());};
    auto status=[&](const char *stage,XrResult result){report<<stage<<": "<<static_cast<int>(result)<<"\n";return XR_SUCCEEDED(result);};
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
    XrInstanceCreateInfoAndroidKHR androidInfo{XR_TYPE_INSTANCE_CREATE_INFO_ANDROID_KHR};androidInfo.applicationVM=vm;androidInfo.applicationActivity=activity;
    XrInstanceCreateInfo create{XR_TYPE_INSTANCE_CREATE_INFO};create.next=&androidInfo;std::strcpy(create.applicationInfo.applicationName,"NEXA runtime check");create.applicationInfo.apiVersion=XR_MAKE_VERSION(1,0,0);create.enabledExtensionCount=static_cast<uint32_t>(enabled.size());create.enabledExtensionNames=enabled.data();
    XrInstance instance=XR_NULL_HANDLE;result=xrCreateInstance(&create,&instance);
    if(!status("Criar instância OpenXR",result))return finish();
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
    report<<"\nEsta verificação não mede poses, controles, renderização ou gameplay.\nExtensões anunciadas:\n";
    for(const auto &p:properties)report<<p.extensionName<<"\n";
    return finish();
}
