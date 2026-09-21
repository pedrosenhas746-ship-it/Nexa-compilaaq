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
#include <ctime>
#include <sys/stat.h>
#include <unistd.h>

#define FRAME_WIDTH 1920
#define FRAME_HEIGHT 1080
#define HTTP_USER_AGENT "Xeno-PS4-Browser/1.0 (PLAYSTATION 4)"
#define NET_POOLSIZE (16 * 1024)
#define HTTP_CHUNK (64 * 1024)

static SDL_Window *g_window = nullptr;
static SDL_Renderer *g_renderer = nullptr;

static int g_netPool = 0;
static int g_sslCtx = 0;
static int g_httpCtx = 0;
static bool g_httpReady = false;

static char g_url[1024] = "";
static char g_lastFile[256] = "";
static char g_status[256] = "READY - PRESS X TO ENTER A URL";
static volatile int g_downloading = 0;
static volatile int g_result = 0;       // 0 idle, 1 success, -1 failed
static volatile unsigned long long g_done = 0;
static volatile unsigned long long g_total = 0;

static const char FONT_CHARS[] = " ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789:/._-%?=&+#()[]";
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
    {14,2,2,2,2,2,14}
};

static int font_index(char c) {
    c = (char)std::toupper((unsigned char)c);
    const char *p = std::strchr(FONT_CHARS, c);
    return p ? (int)(p - FONT_CHARS) : 0;
}

static void draw_text(const char *text, int x, int y, int scale, unsigned char r, unsigned char g, unsigned char b) {
    if (!text) return;
    SDL_SetRenderDrawColor(g_renderer, r, g, b, 255);
    int startX = x;
    for (const char *p = text; *p; ++p) {
        if (*p == '\n') {
            x = startX;
            y += 9 * scale;
            continue;
        }
        int idx = font_index(*p);
        for (int row = 0; row < 7; ++row) {
            unsigned char bits = FONT[idx][row];
            for (int col = 0; col < 5; ++col) {
                if (bits & (1 << (4 - col))) {
                    SDL_Rect px = { x + col * scale, y + row * scale, scale, scale };
                    SDL_RenderFillRect(g_renderer, &px);
                }
            }
        }
        x += 6 * scale;
    }
}

static void draw_wrapped(const char *text, int x, int y, int scale, int maxChars,
                         unsigned char r, unsigned char g, unsigned char b) {
    if (!text) return;
    char line[128];
    int pos = 0;
    int yy = y;
    for (const char *p = text;; ++p) {
        char c = *p;
        if (c == '\0' || c == '\n' || pos >= maxChars) {
            line[pos] = '\0';
            draw_text(line, x, yy, scale, r, g, b);
            yy += 9 * scale;
            pos = 0;
            if (c == '\0') break;
            if (c == '\n') continue;
        }
        line[pos++] = c;
    }
}

static bool starts_with(const char *s, const char *prefix) {
    return s && prefix && std::strncmp(s, prefix, std::strlen(prefix)) == 0;
}

static void set_status(const char *s) {
    if (!s) return;
    std::snprintf(g_status, sizeof(g_status), "%s", s);
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
    out[oi] = '\0';
}

static bool prompt_url(char *out, size_t outCap) {
    static bool ready = false;
    static int32_t userId = 0;
    if (!ready) {
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
    utf8_to_u16("URL HTTP OU HTTPS", titleBuf, 128);
    utf8_to_u16("https://servidor/arquivo.pkg", placeBuf, 128);

    OrbisImeDialogSetting p;
    std::memset(&p, 0, sizeof(p));
    p.userId = (uint32_t)userId;
    p.type = ORBIS_TYPE_DEFAULT;
    p.supportedLanguages = 0;
    p.enterLabel = ORBIS_BUTTON_LABEL_DEFAULT;
    p.inputMethod = ORBIS__DEFAULT;
    p.filter = nullptr;
    p.option = 0;
    p.maxTextLength = (uint32_t)(MAX_TEXT - 1);
    p.inputTextBuffer = (wchar_t *)textBuf;
    p.posx = 0.0f;
    p.posy = 0.0f;
    p.horizontalAlignment = ORBIS_H_CENTER;
    p.verticalAlignment = ORBIS_V_CENTER;
    p.placeholder = (const wchar_t *)placeBuf;
    p.title = (const wchar_t *)titleBuf;

    if (sceImeDialogInit(&p, nullptr) != 0) {
        set_status("ERRO AO ABRIR TECLADO DO PS4");
        return false;
    }

    struct timespec ts = {0, 10 * 1000 * 1000};
    OrbisDialogStatus st;
    while ((st = sceImeDialogGetStatus()) == ORBIS_DIALOG_STATUS_RUNNING)
        nanosleep(&ts, nullptr);

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

static void make_filename(const char *url, char *name, size_t cap) {
    const char *slash = std::strrchr(url, '/');
    const char *src = slash ? slash + 1 : url;
    if (!*src) src = "download.bin";

    size_t n = 0;
    while (*src && *src != '?' && *src != '#' && n + 1 < cap) {
        unsigned char c = (unsigned char)*src++;
        if (std::isalnum(c) || c == '.' || c == '_' || c == '-')
            name[n++] = (char)c;
        else
            name[n++] = '_';
    }
    if (n == 0) {
        std::snprintf(name, cap, "download.bin");
        return;
    }
    name[n] = '\0';
}

static int download_thread(void *) {
    g_result = 0;
    g_done = 0;
    g_total = 0;

    if (!starts_with(g_url, "http://") && !starts_with(g_url, "https://")) {
        set_status("URL INVALIDA - USE HTTP:// OU HTTPS://");
        g_result = -1;
        g_downloading = 0;
        return 0;
    }

    if (!http_init()) {
        set_status("ERRO AO INICIAR REDE DO PS4");
        g_result = -1;
        g_downloading = 0;
        return 0;
    }

    mkdir("/data/pkg", 0777);

    char filename[160];
    make_filename(g_url, filename, sizeof(filename));

    char finalPath[256];
    char tempPath[272];
    std::snprintf(finalPath, sizeof(finalPath), "/data/pkg/%s", filename);
    std::snprintf(tempPath, sizeof(tempPath), "%s.part", finalPath);
    std::snprintf(g_lastFile, sizeof(g_lastFile), "%s", finalPath);

    int tpl = 0, conn = 0, req = 0;
    FILE *fd = nullptr;
    bool ok = false;

    set_status("CONECTANDO...");
    tpl = sceHttpCreateTemplate(g_httpCtx, HTTP_USER_AGENT, ORBIS_HTTP_VERSION_1_1, 1);
    if (tpl < 0) goto done;

    sceHttpSetConnectTimeOut(tpl, 20 * 1000 * 1000);
    conn = sceHttpCreateConnectionWithURL(tpl, g_url, 1);
    if (conn < 0) goto done;

    req = sceHttpCreateRequestWithURL(conn, ORBIS_METHOD_GET, g_url, 0);
    if (req < 0) goto done;

    set_status("ENVIANDO REQUISICAO...");
    if (sceHttpSendRequest(req, nullptr, 0) < 0) goto done;

    int32_t statusCode = 0;
    if (sceHttpGetStatusCode(req, &statusCode) < 0) goto done;
    if (statusCode < 200 || statusCode >= 300) {
        std::snprintf(g_status, sizeof(g_status), "HTTP %d - DOWNLOAD RECUSADO", (int)statusCode);
        goto done;
    }

    {
        int lenType = 0;
        uint64_t len = 0;
        if (sceHttpGetResponseContentLength(req, &lenType, &len) >= 0 &&
            lenType == ORBIS_HTTP_CONTENTLEN_EXIST) {
            g_total = (unsigned long long)len;
        }
    }

    fd = std::fopen(tempPath, "wb");
    if (!fd) {
        set_status("NAO FOI POSSIVEL CRIAR /DATA/PKG");
        goto done;
    }

    set_status("BAIXANDO...");
    {
        unsigned char buffer[HTTP_CHUNK];
        while (true) {
            int got = sceHttpReadData(req, buffer, sizeof(buffer));
            if (got < 0) goto done;
            if (got == 0) {
                ok = true;
                break;
            }
            size_t written = std::fwrite(buffer, 1, (size_t)got, fd);
            if (written != (size_t)got) {
                set_status("ERRO AO GRAVAR NO HD DO PS4");
                goto done;
            }
            g_done += (unsigned long long)got;
        }
    }

done:
    if (fd) {
        std::fclose(fd);
        fd = nullptr;
    }

    if (req > 0) sceHttpDeleteRequest(req);
    if (conn > 0) sceHttpDeleteConnection(conn);
    if (tpl > 0) sceHttpDeleteTemplate(tpl);

    if (ok) {
        std::remove(finalPath);
        if (std::rename(tempPath, finalPath) == 0) {
            set_status("DOWNLOAD CONCLUIDO EM /DATA/PKG");
            g_result = 1;
        } else {
            set_status("DOWNLOAD OK, MAS FALHOU AO RENOMEAR .PART");
            g_result = -1;
        }
    } else {
        std::remove(tempPath);
        if (g_status[0] == '\0' || std::strcmp(g_status, "BAIXANDO...") == 0 ||
            std::strcmp(g_status, "CONECTANDO...") == 0 ||
            std::strcmp(g_status, "ENVIANDO REQUISICAO...") == 0) {
            set_status("DOWNLOAD FALHOU");
        }
        g_result = -1;
    }

    g_downloading = 0;
    return 0;
}

static void start_download() {
    if (g_downloading) return;
    if (!g_url[0]) {
        set_status("DIGITE UMA URL PRIMEIRO");
        return;
    }
    g_downloading = 1;
    SDL_Thread *t = SDL_CreateThread(download_thread, "xeno-download", nullptr);
    if (!t) {
        g_downloading = 0;
        set_status("ERRO AO CRIAR THREAD DE DOWNLOAD");
        return;
    }
    SDL_DetachThread(t);
}

static void render_ui() {
    SDL_SetRenderDrawColor(g_renderer, 14, 18, 28, 255);
    SDL_RenderClear(g_renderer);

    SDL_Rect top = {0, 0, FRAME_WIDTH, 150};
    SDL_SetRenderDrawColor(g_renderer, 27, 90, 180, 255);
    SDL_RenderFillRect(g_renderer, &top);
    draw_text("XENO PS4 BROWSER", 70, 45, 8, 255, 255, 255);

    draw_text("URL", 90, 215, 5, 120, 190, 255);
    SDL_Rect urlBox = {80, 270, 1760, 190};
    SDL_SetRenderDrawColor(g_renderer, 27, 33, 48, 255);
    SDL_RenderFillRect(g_renderer, &urlBox);
    draw_wrapped(g_url[0] ? g_url : "PRESSIONE X PARA DIGITAR UMA URL", 110, 305, 4, 66, 230, 235, 245);

    draw_text("STATUS", 90, 520, 5, 120, 190, 255);
    draw_wrapped(g_status, 90, 580, 4, 72, 255, 255, 255);

    if (g_downloading) {
        SDL_Rect barBg = {90, 700, 1740, 54};
        SDL_SetRenderDrawColor(g_renderer, 40, 46, 60, 255);
        SDL_RenderFillRect(g_renderer, &barBg);

        int width = 0;
        if (g_total > 0) {
            double pct = (double)g_done / (double)g_total;
            if (pct > 1.0) pct = 1.0;
            width = (int)(1740.0 * pct);
        } else {
            width = (int)((SDL_GetTicks() / 8) % 1740);
        }
        SDL_Rect bar = {90, 700, width, 54};
        SDL_SetRenderDrawColor(g_renderer, 58, 155, 255, 255);
        SDL_RenderFillRect(g_renderer, &bar);

        char info[128];
        if (g_total > 0) {
            unsigned long long pct = (g_done * 100ULL) / g_total;
            std::snprintf(info, sizeof(info), "%llu%%  %llu / %llu BYTES",
                          pct, (unsigned long long)g_done, (unsigned long long)g_total);
        } else {
            std::snprintf(info, sizeof(info), "%llu BYTES", (unsigned long long)g_done);
        }
        draw_text(info, 90, 785, 4, 200, 220, 245);
    } else if (g_lastFile[0]) {
        draw_text("ULTIMO ARQUIVO", 90, 700, 4, 120, 190, 255);
        draw_wrapped(g_lastFile, 90, 755, 4, 72, 220, 230, 240);
    }

    SDL_Rect footer = {0, 940, FRAME_WIDTH, 140};
    SDL_SetRenderDrawColor(g_renderer, 20, 24, 36, 255);
    SDL_RenderFillRect(g_renderer, &footer);
    draw_text("X DIGITAR E BAIXAR   QUADRADO EDITAR URL   CIRCULO SAIR", 90, 990, 4, 220, 225, 235);

    SDL_RenderPresent(g_renderer);
    SDL_UpdateWindowSurface(g_window);
}

int main(int, char **) {
    setvbuf(stdout, nullptr, _IONBF, 0);

    if (SDL_Init(SDL_INIT_VIDEO | SDL_INIT_JOYSTICK | SDL_INIT_TIMER) != 0)
        return 1;

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
                if (!g_downloading) running = false;
            } else if (ev.type == SDL_JOYBUTTONDOWN) {
                Uint32 now = SDL_GetTicks();
                if (now - lastAction < 180) continue;
                lastAction = now;

                int b = ev.jbutton.button;
                if (b == 0 && !g_downloading) { // X
                    if (prompt_url(g_url, sizeof(g_url))) {
                        if (g_url[0]) start_download();
                    }
                } else if (b == 2 && !g_downloading) { // Square
                    if (prompt_url(g_url, sizeof(g_url)) && g_url[0])
                        set_status("URL PRONTA - PRESSIONE X PARA BAIXAR");
                } else if (b == 1 && !g_downloading) { // Circle
                    running = false;
                }
            }
        }

        render_ui();
        SDL_Delay(16);
    }

    if (g_httpReady) {
        sceHttpTerm(g_httpCtx);
        sceSslTerm(g_sslCtx);
        sceNetPoolDestroy(g_netPool);
    }

    if (pad) SDL_JoystickClose(pad);
    SDL_DestroyRenderer(g_renderer);
    SDL_DestroyWindow(g_window);
    SDL_Quit();
    return 0;
}
