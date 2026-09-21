#include <SDL2/SDL.h>

#include <orbis/Http.h>
#include <orbis/Ssl.h>
#include <orbis/Net.h>
#include <orbis/Sysmodule.h>
#include <orbis/CommonDialog.h>
#include <orbis/ImeDialog.h>
#include <orbis/UserService.h>

#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <cctype>
#include <cerrno>
#include <sys/stat.h>
#include <unistd.h>

#include "native_browser.h"

#define FRAME_WIDTH 1920
#define FRAME_HEIGHT 1080
#define HTTP_USER_AGENT "Xeno-PS4-Browser/1.2 (PLAYSTATION 4)"
#define NET_POOLSIZE (16 * 1024)
#define HTTP_CHUNK (64 * 1024)
#define MAX_HTML (2 * 1024 * 1024)
#define MAX_LINKS 80
#define MAX_HISTORY 16
#define VISIBLE_LINKS 10

static SDL_Window *g_window = nullptr;
static SDL_Renderer *g_renderer = nullptr;

static int g_netPool = 0;
static int g_sslCtx = 0;
static int g_httpCtx = 0;
static bool g_httpReady = false;

struct LinkItem {
    char url[1024];
    char label[160];
};

static LinkItem g_links[MAX_LINKS];
static volatile int g_linkCount = 0;
static int g_selected = 0;
static int g_top = 0;

static char g_currentUrl[1024] = "";
static char g_pendingUrl[1024] = "";
static char g_pageTitle[160] = "XENO PS4 BROWSER";
static char g_status[256] = "TRIANGLE: OPEN A WEBSITE";
static char g_lastFile[256] = "";

static char g_history[MAX_HISTORY][1024];
static int g_historyCount = 0;

static volatile int g_busy = 0; // 0 idle, 1 page, 2 download
static volatile unsigned long long g_done = 0;
static volatile unsigned long long g_total = 0;

static const char FONT_CHARS[] = " ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789:/._-%?=&+#()[]<>!,";
static const unsigned char FONT[][7] = {
    {0,0,0,0,0,0,0},
    {14,17,17,31,17,17,17}, {30,17,17,30,17,17,30},
    {14,17,16,16,16,17,14}, {30,17,17,17,17,17,30},
    {31,16,16,30,16,16,31}, {31,16,16,30,16,16,16},
    {14,17,16,23,17,17,14}, {17,17,17,31,17,17,17},
    {14,4,4,4,4,4,14}, {7,2,2,2,18,18,12},
    {17,18,20,24,20,18,17}, {16,16,16,16,16,16,31},
    {17,27,21,21,17,17,17}, {17,25,21,19,17,17,17},
    {14,17,17,17,17,17,14}, {30,17,17,30,16,16,16},
    {14,17,17,17,21,18,13}, {30,17,17,30,20,18,17},
    {15,16,16,14,1,1,30}, {31,4,4,4,4,4,4},
    {17,17,17,17,17,17,14}, {17,17,17,17,17,10,4},
    {17,17,17,21,21,21,10}, {17,17,10,4,10,17,17},
    {17,17,10,4,4,4,4}, {31,1,2,4,8,16,31},
    {14,17,19,21,25,17,14}, {4,12,4,4,4,4,14},
    {14,17,1,2,4,8,31}, {30,1,1,14,1,1,30},
    {2,6,10,18,31,2,2}, {31,16,16,30,1,1,30},
    {14,16,16,30,17,17,14}, {31,1,2,4,8,8,8},
    {14,17,17,14,17,17,14}, {14,17,17,15,1,1,14},
    {0,4,0,0,4,0,0}, {1,2,4,8,16,0,0},
    {0,0,0,0,0,4,0}, {0,0,0,0,0,0,31},
    {0,0,0,31,0,0,0}, {17,2,4,8,17,0,0},
    {14,17,1,2,4,0,4}, {0,0,31,0,31,0,0},
    {0,4,31,4,31,4,0}, {4,4,31,4,31,4,4},
    {0,0,4,0,0,4,0}, {4,8,16,16,16,8,4},
    {4,2,1,1,1,2,4}, {14,8,8,8,8,8,14},
    {14,2,2,2,2,2,14}, {8,4,2,1,2,4,8},
    {2,4,8,16,8,4,2}, {0,0,0,0,0,6,6},
    {0,0,0,0,0,0,4}, {4,4,4,4,4,0,4}
};

static int font_index(char c) {
    c = (char)std::toupper((unsigned char)c);
    const char *p = std::strchr(FONT_CHARS, c);
    return p ? (int)(p - FONT_CHARS) : 0;
}

static void draw_text(const char *text, int x, int y, int scale,
                      unsigned char r, unsigned char g, unsigned char b) {
    if (!text) return;
    SDL_SetRenderDrawColor(g_renderer, r, g, b, 255);
    int sx = x;
    for (const char *p = text; *p; ++p) {
        if (*p == '\n') { x = sx; y += 9 * scale; continue; }
        int idx = font_index(*p);
        for (int row = 0; row < 7; ++row) {
            unsigned char bits = FONT[idx][row];
            for (int col = 0; col < 5; ++col) {
                if (bits & (1 << (4 - col))) {
                    SDL_Rect px = {x + col * scale, y + row * scale, scale, scale};
                    SDL_RenderFillRect(g_renderer, &px);
                }
            }
        }
        x += 6 * scale;
    }
}

static void draw_truncated(const char *text, int x, int y, int scale, int maxChars,
                           unsigned char r, unsigned char g, unsigned char b) {
    char buf[256];
    if (!text) text = "";
    std::snprintf(buf, sizeof(buf), "%.*s", maxChars, text);
    if ((int)std::strlen(text) > maxChars && maxChars >= 3) {
        buf[maxChars - 3] = '.';
        buf[maxChars - 2] = '.';
        buf[maxChars - 1] = '.';
        buf[maxChars] = 0;
    }
    draw_text(buf, x, y, scale, r, g, b);
}

static void set_status(const char *s) {
    if (s) std::snprintf(g_status, sizeof(g_status), "%s", s);
}

static bool starts_with_ci(const char *s, const char *prefix) {
    if (!s || !prefix) return false;
    while (*prefix) {
        if (!*s) return false;
        if (std::tolower((unsigned char)*s) != std::tolower((unsigned char)*prefix)) return false;
        ++s; ++prefix;
    }
    return true;
}

static const char *find_ci(const char *hay, const char *needle) {
    if (!hay || !needle || !*needle) return hay;
    size_t n = std::strlen(needle);
    for (const char *p = hay; *p; ++p) {
        size_t i = 0;
        while (i < n && p[i] &&
               std::tolower((unsigned char)p[i]) == std::tolower((unsigned char)needle[i])) ++i;
        if (i == n) return p;
    }
    return nullptr;
}

static void trim(char *s) {
    if (!s) return;
    size_t n = std::strlen(s);
    while (n && std::isspace((unsigned char)s[n - 1])) s[--n] = 0;
    size_t start = 0;
    while (s[start] && std::isspace((unsigned char)s[start])) ++start;
    if (start) std::memmove(s, s + start, std::strlen(s + start) + 1);
}

static void decode_entities(char *s) {
    if (!s) return;
    struct Pair { const char *from; const char *to; };
    static const Pair pairs[] = {
        {"&amp;", "&"}, {"&quot;", "\""}, {"&#39;", "'"},
        {"&lt;", "<"}, {"&gt;", ">"}, {"&nbsp;", " "}
    };
    for (const auto &pair : pairs) {
        char *p = nullptr;
        while ((p = std::strstr(s, pair.from))) {
            size_t a = std::strlen(pair.from), b = std::strlen(pair.to);
            if (a != b) std::memmove(p + b, p + a, std::strlen(p + a) + 1);
            std::memcpy(p, pair.to, b);
        }
    }
}

static void strip_tags(const char *in, char *out, size_t cap) {
    bool tag = false;
    size_t j = 0;
    for (size_t i = 0; in && in[i] && j + 1 < cap; ++i) {
        char c = in[i];
        if (c == '<') { tag = true; continue; }
        if (c == '>') { tag = false; continue; }
        if (!tag) {
            if (std::isspace((unsigned char)c)) {
                if (j && out[j - 1] != ' ') out[j++] = ' ';
            } else {
                out[j++] = c;
            }
        }
    }
    out[j] = 0;
    trim(out);
    decode_entities(out);
}

static bool is_http_url(const char *url) {
    return starts_with_ci(url, "http://") || starts_with_ci(url, "https://");
}

static void get_origin(const char *url, char *out, size_t cap) {
    out[0] = 0;
    const char *scheme = std::strstr(url, "://");
    if (!scheme) return;
    const char *hostEnd = std::strchr(scheme + 3, '/');
    size_t n = hostEnd ? (size_t)(hostEnd - url) : std::strlen(url);
    if (n >= cap) n = cap - 1;
    std::memcpy(out, url, n);
    out[n] = 0;
}

static void resolve_url(const char *base, const char *href, char *out, size_t cap) {
    out[0] = 0;
    if (!href || !*href) return;

    char h[1024];
    std::snprintf(h, sizeof(h), "%s", href);
    trim(h);
    decode_entities(h);

    if (is_http_url(h)) {
        std::snprintf(out, cap, "%s", h);
        return;
    }
    if (starts_with_ci(h, "//")) {
        if (starts_with_ci(base, "https://")) std::snprintf(out, cap, "https:%s", h);
        else std::snprintf(out, cap, "http:%s", h);
        return;
    }
    if (h[0] == '#') {
        std::snprintf(out, cap, "%s", base);
        char *hash = std::strchr(out, '#');
        if (hash) *hash = 0;
        return;
    }
    if (starts_with_ci(h, "javascript:") || starts_with_ci(h, "mailto:") ||
        starts_with_ci(h, "tel:") || starts_with_ci(h, "data:")) return;

    if (h[0] == '/') {
        char origin[512];
        get_origin(base, origin, sizeof(origin));
        std::snprintf(out, cap, "%s%s", origin, h);
        return;
    }

    char dir[1024];
    std::snprintf(dir, sizeof(dir), "%s", base);
    char *q = std::strchr(dir, '?'); if (q) *q = 0;
    char *hash = std::strchr(dir, '#'); if (hash) *hash = 0;
    char *slash = std::strrchr(dir, '/');
    const char *scheme = std::strstr(dir, "://");
    if (slash && (!scheme || slash > scheme + 2)) *(slash + 1) = 0;
    else std::strncat(dir, "/", sizeof(dir) - std::strlen(dir) - 1);
    std::snprintf(out, cap, "%s%s", dir, h);
}

static bool has_download_extension(const char *url) {
    char tmp[1024];
    std::snprintf(tmp, sizeof(tmp), "%s", url ? url : "");
    char *q = std::strchr(tmp, '?'); if (q) *q = 0;
    char *h = std::strchr(tmp, '#'); if (h) *h = 0;
    const char *dot = std::strrchr(tmp, '.');
    if (!dot) return false;
    static const char *exts[] = {
        ".pkg", ".zip", ".rar", ".7z", ".bin", ".iso", ".elf",
        ".png", ".jpg", ".jpeg", ".mp4", ".mp3", ".json", ".txt"
    };
    for (const char *e : exts) {
        if (starts_with_ci(dot, e) && std::strlen(dot) == std::strlen(e)) return true;
    }
    return false;
}

static void utf8_to_u16(const char *s, uint16_t *out, size_t cap) {
    size_t oi = 0;
    const unsigned char *p = (const unsigned char *)s;
    while (*p && oi + 1 < cap) {
        unsigned char c = *p;
        uint32_t cp;
        if (c < 0x80) { cp = c; p += 1; }
        else if ((c >> 5) == 0x6 && p[1]) {
            cp = ((c & 0x1F) << 6) | (p[1] & 0x3F); p += 2;
        } else if ((c >> 4) == 0xE && p[1] && p[2]) {
            cp = ((c & 0x0F) << 12) | ((p[1] & 0x3F) << 6) | (p[2] & 0x3F); p += 3;
        } else { cp = '?'; p += 1; }
        if (cp > 0xFFFF) cp = '?';
        out[oi++] = (uint16_t)cp;
    }
    out[oi] = 0;
}

static void u16_to_utf8(const uint16_t *in, char *out, size_t cap) {
    size_t oi = 0;
    for (size_t i = 0; in[i] && oi + 4 < cap; ++i) {
        uint32_t cp = in[i];
        if (cp < 0x80) out[oi++] = (char)cp;
        else if (cp < 0x800) {
            out[oi++] = (char)(0xC0 | (cp >> 6));
            out[oi++] = (char)(0x80 | (cp & 0x3F));
        } else {
            out[oi++] = (char)(0xE0 | (cp >> 12));
            out[oi++] = (char)(0x80 | ((cp >> 6) & 0x3F));
            out[oi++] = (char)(0x80 | (cp & 0x3F));
        }
    }
    out[oi] = 0;
}

static bool prompt_url(char *out, size_t outCap) {
    static bool ready = false;
    static int32_t userId = 0;
    if (!ready) {
        sceSysmoduleLoadModule(ORBIS_SYSMODULE_IME_DIALOG);
        sceUserServiceInitialize(nullptr);
        if (sceUserServiceGetInitialUser(&userId) != 0) userId = 0;
        sceCommonDialogInitialize();
        ready = true;
    }

    const size_t MAX_TEXT = 1024;
    uint16_t textBuf[MAX_TEXT] = {0};
    uint16_t titleBuf[128] = {0};
    uint16_t placeBuf[128] = {0};
    utf8_to_u16(out, textBuf, MAX_TEXT);
    utf8_to_u16("SITE OU LINK DIRETO", titleBuf, 128);
    utf8_to_u16("https://exemplo.com", placeBuf, 128);

    OrbisImeDialogSetting p;
    std::memset(&p, 0, sizeof(p));
    p.userId = (uint32_t)userId;
    p.type = ORBIS_TYPE_TYPE_URL;
    p.supportedLanguages = 0;
    p.enterLabel = ORBIS_BUTTON_LABEL_GO;
    p.inputMethod = ORBIS__DEFAULT;
    p.maxTextLength = (uint32_t)(MAX_TEXT - 1);
    p.inputTextBuffer = (wchar_t *)textBuf;
    p.horizontalAlignment = ORBIS_H_CENTER;
    p.verticalAlignment = ORBIS_V_CENTER;
    p.placeholder = (const wchar_t *)placeBuf;
    p.title = (const wchar_t *)titleBuf;

    if (sceImeDialogInit(&p, nullptr) != 0) {
        set_status("FAILED TO OPEN PS4 KEYBOARD");
        return false;
    }

    OrbisDialogStatus st;
    while ((st = sceImeDialogGetStatus()) == ORBIS_DIALOG_STATUS_RUNNING) SDL_Delay(10);

    bool accepted = false;
    if (st == ORBIS_DIALOG_STATUS_STOPPED) {
        OrbisDialogResult res;
        std::memset(&res, 0, sizeof(res));
        sceImeDialogGetResult(&res);
        if (res.endstatus == ORBIS_DIALOG_OK) {
            u16_to_utf8(textBuf, out, outCap);
            accepted = true;
        }
    }
    sceImeDialogTerm();
    return accepted;
}

static bool http_init() {
    if (g_httpReady) return true;
    if (sceSysmoduleLoadModuleInternal(ORBIS_SYSMODULE_INTERNAL_NET) < 0) return false;
    if (sceSysmoduleLoadModuleInternal(ORBIS_SYSMODULE_INTERNAL_HTTP) < 0) return false;
    if (sceSysmoduleLoadModuleInternal(ORBIS_SYSMODULE_INTERNAL_SSL) < 0) return false;

    sceNetInit();
    int ret = sceNetPoolCreate("xeno-net", NET_POOLSIZE, 0);
    if (ret < 0) return false;
    g_netPool = ret;

    ret = sceSslInit(SSL_POOLSIZE);
    if (ret < 0) return false;
    g_sslCtx = ret;

    ret = sceHttpInit(g_netPool, g_sslCtx, LIBHTTP_POOLSIZE);
    if (ret < 0) return false;
    g_httpCtx = ret;
    g_httpReady = true;
    return true;
}

struct HttpResponse {
    int tpl;
    int conn;
    int req;
    int32_t status;
    char finalUrl[1024];
    char headers[8192];
};

static void close_response(HttpResponse *r) {
    if (!r) return;
    if (r->req > 0) sceHttpDeleteRequest(r->req);
    if (r->conn > 0) sceHttpDeleteConnection(r->conn);
    if (r->tpl > 0) sceHttpDeleteTemplate(r->tpl);
    r->req = r->conn = r->tpl = 0;
}

static bool header_value(const char *headers, const char *name, char *out, size_t cap) {
    out[0] = 0;
    if (!headers) return false;
    char needle[128];
    std::snprintf(needle, sizeof(needle), "%s:", name);
    const char *p = find_ci(headers, needle);
    if (!p) return false;
    p += std::strlen(needle);
    while (*p == ' ' || *p == '\t') ++p;
    size_t n = 0;
    while (p[n] && p[n] != '\r' && p[n] != '\n' && n + 1 < cap) ++n;
    std::memcpy(out, p, n);
    out[n] = 0;
    trim(out);
    return true;
}

static bool http_open_follow(const char *initialUrl, HttpResponse *out) {
    std::memset(out, 0, sizeof(*out));
    if (!http_init()) return false;

    char url[1024];
    std::snprintf(url, sizeof(url), "%s", initialUrl);

    for (int redirect = 0; redirect < 6; ++redirect) {
        HttpResponse r{};
        r.tpl = sceHttpCreateTemplate(g_httpCtx, HTTP_USER_AGENT, ORBIS_HTTP_VERSION_1_1, 1);
        if (r.tpl < 0) return false;
        sceHttpSetConnectTimeOut(r.tpl, 20 * 1000 * 1000);

        r.conn = sceHttpCreateConnectionWithURL(r.tpl, url, 1);
        if (r.conn < 0) { close_response(&r); return false; }

        r.req = sceHttpCreateRequestWithURL(r.conn, ORBIS_METHOD_GET, url, 0);
        if (r.req < 0) { close_response(&r); return false; }

        if (sceHttpSendRequest(r.req, nullptr, 0) < 0) {
            close_response(&r); return false;
        }
        if (sceHttpGetStatusCode(r.req, &r.status) < 0) {
            close_response(&r); return false;
        }

        char *raw = nullptr;
        size_t rawSize = 0;
        if (sceHttpGetAllResponseHeaders(r.req, &raw, &rawSize) >= 0 && raw && rawSize) {
            size_t n = rawSize < sizeof(r.headers) - 1 ? rawSize : sizeof(r.headers) - 1;
            std::memcpy(r.headers, raw, n);
            r.headers[n] = 0;
        }

        if (r.status >= 300 && r.status < 400) {
            char loc[1024];
            if (!header_value(r.headers, "Location", loc, sizeof(loc))) {
                close_response(&r);
                return false;
            }
            char next[1024];
            resolve_url(url, loc, next, sizeof(next));
            close_response(&r);
            if (!next[0]) return false;
            std::snprintf(url, sizeof(url), "%s", next);
            continue;
        }

        std::snprintf(r.finalUrl, sizeof(r.finalUrl), "%s", url);
        *out = r;
        return true;
    }
    return false;
}

static void make_filename(const char *url, const char *headers, char *name, size_t cap) {
    char disp[512];
    if (headers && header_value(headers, "Content-Disposition", disp, sizeof(disp))) {
        const char *p = find_ci(disp, "filename=");
        if (p) {
            p += 9;
            while (*p == ' ' || *p == '"' || *p == '\'') ++p;
            size_t n = 0;
            while (p[n] && p[n] != '"' && p[n] != '\'' && p[n] != ';' && n + 1 < cap) {
                unsigned char c = (unsigned char)p[n];
                name[n] = (std::isalnum(c) || c == '.' || c == '_' || c == '-') ? (char)c : '_';
                ++n;
            }
            name[n] = 0;
            if (n) return;
        }
    }

    const char *slash = std::strrchr(url, '/');
    const char *src = slash ? slash + 1 : url;
    if (!*src) src = "download.bin";
    size_t n = 0;
    while (*src && *src != '?' && *src != '#' && n + 1 < cap) {
        unsigned char c = (unsigned char)*src++;
        name[n++] = (std::isalnum(c) || c == '.' || c == '_' || c == '-') ? (char)c : '_';
    }
    if (!n) std::snprintf(name, cap, "download.bin");
    else name[n] = 0;
}

static bool looks_like_html(const char *headers) {
    char ct[256];
    if (!header_value(headers, "Content-Type", ct, sizeof(ct))) return true;
    return find_ci(ct, "text/html") || find_ci(ct, "application/xhtml") || find_ci(ct, "text/plain");
}

static void add_link(const char *url, const char *label) {
    int n = g_linkCount;
    if (n < 0 || n >= MAX_LINKS) return;
    for (int i = 0; i < n; ++i) {
        if (std::strcmp(g_links[i].url, url) == 0) return;
    }
    std::snprintf(g_links[n].url, sizeof(g_links[n].url), "%s", url);
    std::snprintf(g_links[n].label, sizeof(g_links[n].label), "%s", (label && *label) ? label : url);
    g_linkCount = n + 1;
}

static void parse_html(const char *html, const char *baseUrl) {
    g_linkCount = 0;
    g_selected = 0;
    g_top = 0;

    const char *t1 = find_ci(html, "<title");
    if (t1) {
        t1 = std::strchr(t1, '>');
        if (t1) {
            ++t1;
            const char *t2 = find_ci(t1, "</title>");
            if (t2) {
                char raw[256];
                size_t n = (size_t)(t2 - t1);
                if (n >= sizeof(raw)) n = sizeof(raw) - 1;
                std::memcpy(raw, t1, n); raw[n] = 0;
                strip_tags(raw, g_pageTitle, sizeof(g_pageTitle));
            }
        }
    }
    if (!g_pageTitle[0]) std::snprintf(g_pageTitle, sizeof(g_pageTitle), "XENO PS4 BROWSER");

    const char *p = html;
    while (g_linkCount < MAX_LINKS && (p = find_ci(p, "<a"))) {
        const char *tagEnd = std::strchr(p, '>');
        if (!tagEnd) break;

        const char *hrefPos = find_ci(p, "href");
        if (!hrefPos || hrefPos > tagEnd) { p = tagEnd + 1; continue; }
        hrefPos += 4;
        while (hrefPos < tagEnd && std::isspace((unsigned char)*hrefPos)) ++hrefPos;
        if (hrefPos >= tagEnd || *hrefPos != '=') { p = tagEnd + 1; continue; }
        ++hrefPos;
        while (hrefPos < tagEnd && std::isspace((unsigned char)*hrefPos)) ++hrefPos;

        char quote = 0;
        if (*hrefPos == '"' || *hrefPos == '\'') quote = *hrefPos++;
        const char *hrefEnd = hrefPos;
        if (quote) {
            while (hrefEnd < tagEnd && *hrefEnd != quote) ++hrefEnd;
        } else {
            while (hrefEnd < tagEnd && !std::isspace((unsigned char)*hrefEnd) && *hrefEnd != '>') ++hrefEnd;
        }

        char href[1024];
        size_t hn = (size_t)(hrefEnd - hrefPos);
        if (hn >= sizeof(href)) hn = sizeof(href) - 1;
        std::memcpy(href, hrefPos, hn); href[hn] = 0;

        const char *close = find_ci(tagEnd + 1, "</a>");
        char label[160] = "";
        if (close && close - (tagEnd + 1) < 2048) {
            char raw[1024];
            size_t ln = (size_t)(close - (tagEnd + 1));
            if (ln >= sizeof(raw)) ln = sizeof(raw) - 1;
            std::memcpy(raw, tagEnd + 1, ln); raw[ln] = 0;
            strip_tags(raw, label, sizeof(label));
        }

        char absolute[1024];
        resolve_url(baseUrl, href, absolute, sizeof(absolute));
        if (absolute[0] && is_http_url(absolute)) add_link(absolute, label);

        p = tagEnd + 1;
    }
}

static int download_url_sync(const char *url) {
    g_done = 0;
    g_total = 0;
    set_status("CONNECTING DOWNLOAD...");

    HttpResponse r{};
    if (!http_open_follow(url, &r)) {
        set_status("DOWNLOAD CONNECTION FAILED");
        return -1;
    }
    if (r.status < 200 || r.status >= 300) {
        char s[128];
        std::snprintf(s, sizeof(s), "HTTP %d - DOWNLOAD FAILED", (int)r.status);
        set_status(s);
        close_response(&r);
        return -1;
    }

    size_t len = 0;
    int lenType = 0;
    if (sceHttpGetResponseContentLength(r.req, &lenType, &len) >= 0 &&
        lenType == ORBIS_HTTP_CONTENTLEN_EXIST) g_total = (unsigned long long)len;

    mkdir("/data/pkg", 0777);
    char filename[160];
    make_filename(r.finalUrl, r.headers, filename, sizeof(filename));

    char finalPath[256], tempPath[272];
    std::snprintf(finalPath, sizeof(finalPath), "/data/pkg/%s", filename);
    std::snprintf(tempPath, sizeof(tempPath), "%s.part", finalPath);
    std::snprintf(g_lastFile, sizeof(g_lastFile), "%s", finalPath);

    FILE *fd = std::fopen(tempPath, "wb");
    if (!fd) {
        set_status("FAILED TO CREATE /DATA/PKG FILE");
        close_response(&r);
        return -1;
    }

    set_status("DOWNLOADING...");
    bool ok = false;
    unsigned char buffer[HTTP_CHUNK];
    while (true) {
        int got = sceHttpReadData(r.req, buffer, sizeof(buffer));
        if (got < 0) break;
        if (got == 0) { ok = true; break; }
        if (std::fwrite(buffer, 1, (size_t)got, fd) != (size_t)got) break;
        g_done += (unsigned long long)got;
    }
    std::fclose(fd);
    close_response(&r);

    if (!ok) {
        std::remove(tempPath);
        set_status("DOWNLOAD FAILED");
        return -1;
    }

    std::remove(finalPath);
    if (std::rename(tempPath, finalPath) != 0) {
        set_status("FAILED TO FINISH DOWNLOAD");
        return -1;
    }
    set_status("DOWNLOAD COMPLETE - SAVED IN /DATA/PKG");
    return 0;
}

static int download_thread(void *) {
    char url[1024];
    std::snprintf(url, sizeof(url), "%s", g_pendingUrl);
    download_url_sync(url);
    g_busy = 0;
    return 0;
}

static void start_download(const char *url) {
    if (g_busy || !url || !is_http_url(url)) return;
    std::snprintf(g_pendingUrl, sizeof(g_pendingUrl), "%s", url);
    g_busy = 2;
    SDL_Thread *t = SDL_CreateThread(download_thread, "xeno-download", nullptr);
    if (!t) {
        g_busy = 0;
        set_status("FAILED TO START DOWNLOAD THREAD");
        return;
    }
    SDL_DetachThread(t);
}

static int page_thread(void *) {
    char url[1024];
    std::snprintf(url, sizeof(url), "%s", g_pendingUrl);
    set_status("OPENING WEBSITE...");

    HttpResponse r{};
    if (!http_open_follow(url, &r)) {
        set_status("FAILED TO OPEN WEBSITE");
        g_busy = 0;
        return 0;
    }

    if (r.status < 200 || r.status >= 300) {
        char s[128];
        std::snprintf(s, sizeof(s), "HTTP %d - WEBSITE FAILED", (int)r.status);
        set_status(s);
        close_response(&r);
        g_busy = 0;
        return 0;
    }

    if (!looks_like_html(r.headers)) {
        close_response(&r);
        std::snprintf(g_pendingUrl, sizeof(g_pendingUrl), "%s", r.finalUrl);
        g_busy = 2;
        download_url_sync(g_pendingUrl);
        g_busy = 0;
        return 0;
    }

    char *html = (char *)std::malloc(MAX_HTML + 1);
    if (!html) {
        close_response(&r);
        set_status("NOT ENOUGH MEMORY FOR PAGE");
        g_busy = 0;
        return 0;
    }

    size_t used = 0;
    while (used < MAX_HTML) {
        int got = sceHttpReadData(r.req, html + used, (uint32_t)((MAX_HTML - used) > HTTP_CHUNK ? HTTP_CHUNK : (MAX_HTML - used)));
        if (got < 0) { std::free(html); close_response(&r); set_status("PAGE READ FAILED"); g_busy = 0; return 0; }
        if (got == 0) break;
        used += (size_t)got;
    }
    html[used] = 0;

    std::snprintf(g_currentUrl, sizeof(g_currentUrl), "%s", r.finalUrl);
    close_response(&r);

    std::snprintf(g_pageTitle, sizeof(g_pageTitle), "XENO PS4 BROWSER");
    parse_html(html, g_currentUrl);
    std::free(html);

    char s[128];
    std::snprintf(s, sizeof(s), "PAGE LOADED - %d LINKS FOUND", (int)g_linkCount);
    set_status(s);
    g_busy = 0;
    return 0;
}

static void start_page(const char *url, bool pushHistory) {
    if (g_busy || !url || !*url) return;
    char fixed[1024];
    if (!std::strstr(url, "://")) std::snprintf(fixed, sizeof(fixed), "https://%s", url);
    else std::snprintf(fixed, sizeof(fixed), "%s", url);

    if (has_download_extension(fixed)) {
        start_download(fixed);
        return;
    }

    if (pushHistory && g_currentUrl[0] && std::strcmp(g_currentUrl, fixed) != 0) {
        if (g_historyCount >= MAX_HISTORY) {
            for (int i = 1; i < MAX_HISTORY; ++i)
                std::snprintf(g_history[i - 1], sizeof(g_history[i - 1]), "%s", g_history[i]);
            g_historyCount = MAX_HISTORY - 1;
        }
        std::snprintf(g_history[g_historyCount++], sizeof(g_history[0]), "%s", g_currentUrl);
    }

    std::snprintf(g_pendingUrl, sizeof(g_pendingUrl), "%s", fixed);
    g_busy = 1;
    SDL_Thread *t = SDL_CreateThread(page_thread, "xeno-page", nullptr);
    if (!t) {
        g_busy = 0;
        set_status("FAILED TO START PAGE THREAD");
        return;
    }
    SDL_DetachThread(t);
}

static void move_selection(int delta) {
    if (g_busy || g_linkCount <= 0) return;
    int n = g_selected + delta;
    if (n < 0) n = g_linkCount - 1;
    if (n >= g_linkCount) n = 0;
    g_selected = n;

    if (g_selected < g_top) g_top = g_selected;
    if (g_selected >= g_top + VISIBLE_LINKS) g_top = g_selected - VISIBLE_LINKS + 1;
}

static void open_selected() {
    if (g_busy || g_linkCount <= 0 || g_selected < 0 || g_selected >= g_linkCount) return;
    const char *url = g_links[g_selected].url;
    if (has_download_extension(url)) start_download(url);
    else start_page(url, true);
}

static void go_back() {
    if (g_busy || g_historyCount <= 0) return;
    char url[1024];
    std::snprintf(url, sizeof(url), "%s", g_history[--g_historyCount]);
    g_currentUrl[0] = 0;
    start_page(url, false);
}

static void render_ui() {
    SDL_SetRenderDrawColor(g_renderer, 12, 16, 25, 255);
    SDL_RenderClear(g_renderer);

    SDL_Rect top = {0, 0, FRAME_WIDTH, 135};
    SDL_SetRenderDrawColor(g_renderer, 25, 93, 190, 255);
    SDL_RenderFillRect(g_renderer, &top);
    draw_text("XENO PS4 BROWSER 1.2", 60, 38, 7, 255, 255, 255);

    draw_truncated(g_pageTitle, 70, 170, 5, 52, 135, 200, 255);
    draw_truncated(g_currentUrl[0] ? g_currentUrl : "NO WEBSITE OPEN", 70, 225, 3, 95, 185, 195, 215);

    SDL_Rect statusBox = {55, 275, 1810, 72};
    SDL_SetRenderDrawColor(g_renderer, 26, 31, 45, 255);
    SDL_RenderFillRect(g_renderer, &statusBox);
    draw_truncated(g_status, 78, 298, 3, 92, 240, 243, 250);

    if (g_busy == 2) {
        SDL_Rect bg = {70, 380, 1780, 55};
        SDL_SetRenderDrawColor(g_renderer, 42, 47, 61, 255);
        SDL_RenderFillRect(g_renderer, &bg);

        int width = 0;
        if (g_total > 0) {
            double pct = (double)g_done / (double)g_total;
            if (pct > 1.0) pct = 1.0;
            width = (int)(1780.0 * pct);
        } else {
            width = (int)((SDL_GetTicks() / 7) % 1780);
        }
        SDL_Rect fill = {70, 380, width, 55};
        SDL_SetRenderDrawColor(g_renderer, 60, 160, 255, 255);
        SDL_RenderFillRect(g_renderer, &fill);

        char info[160];
        if (g_total > 0) {
            unsigned long long pct = (g_done * 100ULL) / g_total;
            std::snprintf(info, sizeof(info), "%llu%%  %llu / %llu BYTES",
                          pct, (unsigned long long)g_done, (unsigned long long)g_total);
        } else {
            std::snprintf(info, sizeof(info), "%llu BYTES", (unsigned long long)g_done);
        }
        draw_text(info, 75, 460, 3, 215, 225, 245);
        draw_truncated(g_lastFile, 75, 515, 3, 90, 160, 190, 220);
    } else {
        int y = 380;
        if (g_linkCount <= 0 && !g_busy) {
            draw_text("OPEN A WEBSITE WITH TRIANGLE", 90, 430, 5, 180, 195, 220);
            draw_text("THEN CHOOSE A LINK WITH THE D-PAD AND PRESS X", 90, 505, 4, 150, 170, 200);
        } else if (g_busy == 1) {
            draw_text("LOADING PAGE...", 90, 430, 5, 180, 205, 240);
        } else {
            for (int row = 0; row < VISIBLE_LINKS; ++row) {
                int idx = g_top + row;
                if (idx >= g_linkCount) break;
                SDL_Rect item = {65, y - 10, 1790, 54};
                if (idx == g_selected) {
                    SDL_SetRenderDrawColor(g_renderer, 38, 105, 200, 255);
                    SDL_RenderFillRect(g_renderer, &item);
                }
                char label[180];
                const char *kind = has_download_extension(g_links[idx].url) ? "[FILE] " : "[LINK] ";
                std::snprintf(label, sizeof(label), "%s%s", kind, g_links[idx].label);
                draw_truncated(label, 85, y, 3, 88,
                               idx == g_selected ? 255 : 215,
                               idx == g_selected ? 255 : 225,
                               idx == g_selected ? 255 : 235);
                y += 60;
            }
        }
    }

    SDL_Rect footer = {0, 935, FRAME_WIDTH, 145};
    SDL_SetRenderDrawColor(g_renderer, 19, 23, 34, 255);
    SDL_RenderFillRect(g_renderer, &footer);
    draw_text("DPAD SELECT   X FALLBACK LINK   TRIANGLE VISUAL BROWSER   SQUARE REFRESH   CIRCLE BACK",
              58, 978, 3, 225, 230, 240);
    draw_text("FILES SAVE TO /DATA/PKG", 58, 1028, 3, 125, 180, 235);

    SDL_RenderPresent(g_renderer);
    SDL_UpdateWindowSurface(g_window);
}

int main(int, char **) {
    setvbuf(stdout, nullptr, _IONBF, 0);

    if (SDL_Init(SDL_INIT_VIDEO | SDL_INIT_JOYSTICK | SDL_INIT_TIMER) != 0) return 1;

    g_window = SDL_CreateWindow("Xeno PS4 Browser",
                                SDL_WINDOWPOS_UNDEFINED, SDL_WINDOWPOS_UNDEFINED,
                                FRAME_WIDTH, FRAME_HEIGHT, 0);
    if (!g_window) return 2;

    SDL_Surface *surface = SDL_GetWindowSurface(g_window);
    g_renderer = SDL_CreateSoftwareRenderer(surface);
    if (!g_renderer) return 3;

    SDL_Joystick *pad = nullptr;
    if (SDL_NumJoysticks() > 0) pad = SDL_JoystickOpen(0);

    bool running = true;
    Uint32 lastAction = 0;

    while (running) {
        SDL_Event ev;
        while (SDL_PollEvent(&ev)) {
            if (ev.type == SDL_QUIT) {
                if (!g_busy) running = false;
            } else if (ev.type == SDL_JOYHATMOTION && ev.jhat.hat == 0) {
                Uint32 now = SDL_GetTicks();
                if (now - lastAction < 120) continue;
                if (ev.jhat.value & SDL_HAT_UP) { move_selection(-1); lastAction = now; }
                else if (ev.jhat.value & SDL_HAT_DOWN) { move_selection(1); lastAction = now; }
            } else if (ev.type == SDL_JOYBUTTONDOWN) {
                Uint32 now = SDL_GetTicks();
                if (now - lastAction < 150) continue;
                lastAction = now;

                int b = ev.jbutton.button;
                if (b == 0 && !g_busy) { // X
                    if (g_linkCount > 0) open_selected();
                    else {
                        char u[1024] = "";
                        if (prompt_url(u, sizeof(u)) && u[0]) start_page(u, true);
                    }
                } else if (b == 1 && !g_busy) { // Circle
                    if (g_historyCount > 0) go_back();
                    else if (!g_currentUrl[0]) running = false;
                    else set_status("NO PREVIOUS PAGE - TRIANGLE OPENS A NEW URL");
                } else if (b == 2 && !g_busy) { // Square
                    if (g_currentUrl[0]) start_page(g_currentUrl, false);
                } else if (b == 3 && !g_busy) { // Triangle: visual native browser
                    char u[1024];
                    std::snprintf(u, sizeof(u), "%s", g_currentUrl);
                    if (prompt_url(u, sizeof(u)) && u[0]) {
                        char fixed[1024];
                        if (!std::strstr(u, "://")) std::snprintf(fixed, sizeof(fixed), "https://%s", u);
                        else std::snprintf(fixed, sizeof(fixed), "%s", u);

                        std::snprintf(g_currentUrl, sizeof(g_currentUrl), "%s", fixed);

                        if (has_download_extension(fixed)) {
                            start_download(fixed);
                        } else {
                            char captured[4096] = {0};
                            set_status("OPENING PS4 VISUAL BROWSER...");
                            int br = xenoOpenNativeBrowser(fixed, captured, sizeof(captured));
                            if (br == XENO_BROWSER_DOWNLOAD && captured[0]) {
                                std::snprintf(g_currentUrl, sizeof(g_currentUrl), "%s", captured);
                                set_status("FILE DETECTED - STARTING DOWNLOAD...");
                                start_download(captured);
                            } else if (br == XENO_BROWSER_ERROR) {
                                set_status("VISUAL BROWSER FAILED - USING FALLBACK");
                                start_page(fixed, true);
                            } else {
                                set_status("VISUAL BROWSER CLOSED");
                            }
                        }
                    }
                } else if ((b == 10 || b == 12) && !g_busy) { // fallback dpad up
                    move_selection(-1);
                } else if ((b == 11 || b == 13) && !g_busy) { // fallback dpad down
                    move_selection(1);
                }
            }
        }

        render_ui();
        SDL_Delay(16);
    }

    if (g_httpReady) {
        sceHttpTerm(g_httpCtx);
        sceSslTerm();
        sceNetPoolDestroy(g_netPool);
    }

    if (pad) SDL_JoystickClose(pad);
    SDL_DestroyRenderer(g_renderer);
    SDL_DestroyWindow(g_window);
    SDL_Quit();
    return 0;
}
