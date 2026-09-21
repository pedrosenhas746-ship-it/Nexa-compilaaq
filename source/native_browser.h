#pragma once
#include <cstddef>

enum XenoNativeBrowserResult {
    XENO_BROWSER_ERROR = -1,
    XENO_BROWSER_CLOSED = 0,
    XENO_BROWSER_DOWNLOAD = 1
};

// Opens the PS4 system browser. If a navigation matches a downloadable PKG URL,
// the system browser returns the URL through its callback and this function
// writes it to outUrl.
int xenoOpenNativeBrowser(const char *url, char *outUrl, size_t outCap);
