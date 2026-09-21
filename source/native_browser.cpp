#include "native_browser.h"

#include <orbis/CommonDialog.h>
#include <orbis/Sysmodule.h>
#include <orbis/UserService.h>
#include <orbis/_types/common_dialog.h>

#include <SDL2/SDL.h>

#include <cstdint>
#include <cstddef>
#include <cstring>

// OpenOrbis v0.5.4 exposes the WebBrowserDialog symbols, but its public header
// still has untyped prototypes. These layouts match the PS4 WebBrowserDialog ABI
// used by PS4 homebrew headers and the common dialog base layout.
enum XenoWebBrowserDialogMode : int32_t {
    XENO_WEB_BROWSER_DIALOG_MODE_DEFAULT = 1,
    XENO_WEB_BROWSER_DIALOG_MODE_CUSTOM = 2
};

enum XenoWebBrowserCallbackParamType : int32_t {
    XENO_WEB_BROWSER_CALLBACK_URL = 0,
    XENO_WEB_BROWSER_CALLBACK_REGEXP = 1
};

struct XenoWebBrowserDialogCallbackInitParam {
    size_t size;
    XenoWebBrowserCallbackParamType type;
    int32_t padding;
    const char *data;
    uint8_t reserved[32];
};

struct XenoWebBrowserDialogImeParam {
    size_t size;
    uint32_t option;
    uint8_t reserved[256];
    int32_t padding;
};

struct XenoWebBrowserDialogWebViewParam {
    size_t size;
    uint32_t option;
    uint8_t reserved[256];
    int32_t padding;
};

struct XenoWebBrowserDialogParam {
    OrbisCommonDialogBaseParam baseParam;
    size_t size;
    XenoWebBrowserDialogMode mode;
    int32_t userId;
    const char *url;
    XenoWebBrowserDialogCallbackInitParam *callbackInitParam;
    uint16_t width;
    uint16_t height;
    uint16_t positionX;
    uint16_t positionY;
    uint32_t parts;
    uint16_t headerWidth;
    uint16_t headerPositionX;
    uint16_t headerPositionY;
    int16_t padding0;
    uint32_t control;
    XenoWebBrowserDialogImeParam *imeParam;
    XenoWebBrowserDialogWebViewParam *webviewParam;
    uint32_t animation;
    uint8_t reserved[202];
    int16_t padding1;
};

struct XenoWebBrowserDialogCallbackResultParam {
    size_t size;
    XenoWebBrowserCallbackParamType type;
    int32_t padding;
    const char *data;
    char *buffer;
    size_t bufferSize;
    uint8_t reserved[32];
};

struct XenoWebBrowserDialogResult {
    int32_t result;
    int32_t padding;
    XenoWebBrowserDialogCallbackResultParam *callbackResultParam;
    uint8_t reserved[240];
};

static_assert(sizeof(OrbisCommonDialogBaseParam) == 48, "Unexpected common dialog ABI");
static_assert(sizeof(XenoWebBrowserDialogCallbackInitParam) == 56, "Unexpected callback ABI");
static_assert(sizeof(XenoWebBrowserDialogParam) == 328, "Unexpected browser param ABI");
static_assert(sizeof(XenoWebBrowserDialogResult) == 256, "Unexpected browser result ABI");

extern "C" {
int32_t sceWebBrowserDialogInitialize(void);
int32_t sceWebBrowserDialogTerminate(void);
int32_t sceWebBrowserDialogOpen(const XenoWebBrowserDialogParam *param);
int32_t sceWebBrowserDialogUpdateStatus(void);
int32_t sceWebBrowserDialogGetStatus(void);
int32_t sceWebBrowserDialogGetResult(XenoWebBrowserDialogResult *result);
int32_t sceWebBrowserDialogClose(void);
}

static void initBrowserParam(XenoWebBrowserDialogParam *param) {
    std::memset(param, 0, sizeof(*param));
    param->baseParam.size = sizeof(OrbisCommonDialogBaseParam);
    param->baseParam.magic = (uint32_t)(
        ORBIS_COMMON_DIALOG_MAGIC_NUMBER + (uintptr_t)&param->baseParam
    );
    param->size = sizeof(*param);
}

static bool isHttpUrl(const char *s) {
    if (!s) return false;
    return std::strncmp(s, "http://", 7) == 0 || std::strncmp(s, "https://", 8) == 0;
}

int xenoOpenNativeBrowser(const char *url, char *outUrl, size_t outCap) {
    if (!url || !*url || !outUrl || outCap < 2) return XENO_BROWSER_ERROR;
    outUrl[0] = 0;

    // Shared dialog init can report "already initialized"; the browser can still
    // be used in that state, so do not fail only because this call is repeated.
    sceCommonDialogInitialize();

    int32_t mod = sceSysmoduleLoadModule(ORBIS_SYSMODULE_WEB_BROWSER_DIALOG);
    if (mod < 0) return XENO_BROWSER_ERROR;

    if (sceWebBrowserDialogInitialize() < 0) {
        sceSysmoduleUnloadModule(ORBIS_SYSMODULE_WEB_BROWSER_DIALOG);
        return XENO_BROWSER_ERROR;
    }

    int32_t userId = 0;
    sceUserServiceInitialize(nullptr);
    if (sceUserServiceGetInitialUser(&userId) < 0) {
        sceWebBrowserDialogTerminate();
        sceSysmoduleUnloadModule(ORBIS_SYSMODULE_WEB_BROWSER_DIALOG);
        return XENO_BROWSER_ERROR;
    }

    // Keep the regex conservative. The PS4 callback regex implementation is
    // old, so avoid lookarounds and other modern-only constructs.
    static const char DOWNLOAD_RE[] =
        ".*[.]([pP][kK][gG]|[zZ][iI][pP]|[rR][aA][rR]|7[zZ])([?].*)?$";

    XenoWebBrowserDialogCallbackInitParam cbInit{};
    cbInit.size = sizeof(cbInit);
    cbInit.type = XENO_WEB_BROWSER_CALLBACK_REGEXP;
    cbInit.data = DOWNLOAD_RE;

    XenoWebBrowserDialogParam param;
    initBrowserParam(&param);
    param.mode = XENO_WEB_BROWSER_DIALOG_MODE_DEFAULT;
    param.userId = userId;
    param.url = url;
    param.callbackInitParam = &cbInit;

    int32_t openRet = sceWebBrowserDialogOpen(&param);
    if (openRet < 0) {
        sceWebBrowserDialogTerminate();
        sceSysmoduleUnloadModule(ORBIS_SYSMODULE_WEB_BROWSER_DIALOG);
        return XENO_BROWSER_ERROR;
    }

    int32_t status = ORBIS_COMMON_DIALOG_STATUS_RUNNING;
    while (status == ORBIS_COMMON_DIALOG_STATUS_RUNNING) {
        status = sceWebBrowserDialogUpdateStatus();
        SDL_Delay(16);
    }

    int finalResult = XENO_BROWSER_CLOSED;
    if (status == ORBIS_COMMON_DIALOG_STATUS_FINISHED) {
        char longUrl[4096] = {0};

        XenoWebBrowserDialogCallbackResultParam cbResult{};
        cbResult.size = sizeof(cbResult);
        cbResult.buffer = longUrl;
        cbResult.bufferSize = sizeof(longUrl);

        XenoWebBrowserDialogResult result{};
        result.callbackResultParam = &cbResult;

        int32_t getRet = sceWebBrowserDialogGetResult(&result);
        if (getRet >= 0) {
            const char *captured = nullptr;
            if (cbResult.data && isHttpUrl(cbResult.data)) captured = cbResult.data;
            else if (longUrl[0] && isHttpUrl(longUrl)) captured = longUrl;

            if (captured) {
                std::snprintf(outUrl, outCap, "%s", captured);
                finalResult = XENO_BROWSER_DOWNLOAD;
            }
        }
    }

    sceWebBrowserDialogClose();
    sceWebBrowserDialogTerminate();
    sceSysmoduleUnloadModule(ORBIS_SYSMODULE_WEB_BROWSER_DIALOG);
    return finalResult;
}
