// NativeUtils.dll — Windows WebView2 (Edge Chromium) bridge для OTLD Helper.
//
// §TZ-DESKTOP-NATIVE-2026-05 — реализация интерфейса WinWebViewNative.kt
// (см. desktop/src/main/kotlin/.../sheets/nativeweb/WinWebViewNative.kt).
//
// Зеркало Mac libNativeUtils.dylib (WKWebView). Компилируется CI windows-latest
// runner'ом через CMake + MSVC, кладётся в desktop/src/main/resources/win32-x86-64/.
// JNA автоматически грузит DLL через Native.load("NativeUtils").

#define WIN32_LEAN_AND_MEAN
#define UNICODE
#define _UNICODE
#include <windows.h>
#include <Shlwapi.h>
#include <objbase.h>
#include <ShlObj.h>

#include <atomic>
#include <cstring>
#include <cstdio>
#include <memory>
#include <mutex>
#include <string>
#include <unordered_map>
#include <vector>

#include <wrl.h>
#include "WebView2.h"

using namespace Microsoft::WRL;

// ────────────────────────────────────────────────────────────────────
// Diagnostic file logging (0.8.2 — root cause 0.8.1 regression)
// Лог пишется в %LOCALAPPDATA%\.otldhelper\webview2\debug.log
// ────────────────────────────────────────────────────────────────────

static std::mutex g_logMutex;

static std::wstring debugLogPath() {
    PWSTR localAppData = nullptr;
    std::wstring base;
    if (SUCCEEDED(SHGetKnownFolderPath(FOLDERID_LocalAppData, 0, nullptr, &localAppData)) && localAppData) {
        base = localAppData;
        CoTaskMemFree(localAppData);
    } else {
        wchar_t userProfile[MAX_PATH];
        DWORD n = GetEnvironmentVariableW(L"USERPROFILE", userProfile, MAX_PATH);
        if (n > 0 && n < MAX_PATH) base = userProfile;
        else base = L"C:\\";
    }
    base += L"\\.otldhelper\\webview2";
    SHCreateDirectoryExW(nullptr, base.c_str(), nullptr);
    base += L"\\debug.log";
    return base;
}

static void dlog(const char* fmt, ...) {
    std::lock_guard<std::mutex> lk(g_logMutex);
    static std::wstring path = debugLogPath();
    FILE* f = nullptr;
    if (_wfopen_s(&f, path.c_str(), L"a") != 0 || !f) return;
    SYSTEMTIME st;
    GetLocalTime(&st);
    fprintf(f, "[%02d:%02d:%02d.%03d] ",
        st.wHour, st.wMinute, st.wSecond, st.wMilliseconds);
    va_list args;
    va_start(args, fmt);
    vfprintf(f, fmt, args);
    va_end(args);
    fputc('\n', f);
    fclose(f);
}

// ────────────────────────────────────────────────────────────────────
// Registry
// ────────────────────────────────────────────────────────────────────

typedef int (*JavaNavCallback)(int64_t webViewId, const char* url);

struct WebViewInstance {
    ComPtr<ICoreWebView2Controller> controller;
    ComPtr<ICoreWebView2> webview;
    HWND parentHwnd = nullptr;
    bool visible = false;
    JavaNavCallback navCallback = nullptr;
    int64_t id = 0;
    EventRegistrationToken navStartingToken = {};
    EventRegistrationToken navCompletedToken = {};
    EventRegistrationToken domContentLoadedToken = {};
    EventRegistrationToken historyChangedToken = {};
    EventRegistrationToken webMessageToken = {};
    std::atomic<bool> isLoading{false};
    std::mutex webMessagesMutex;
    std::vector<std::string> webMessages;
};

static std::atomic<int64_t> g_nextWebViewId{1};
static std::unordered_map<int64_t, std::shared_ptr<WebViewInstance>> g_webviews;
static std::mutex g_webviewsMutex;
static std::wstring g_userDataFolder;
static std::mutex g_userDataFolderMutex;
// §TZ-DESKTOP-NATIVE-2026-05 0.8.4 — убрали global flag g_comInitialized.
// COM apartment-specific: каждый thread должен init свой COM. Раньше первый
// thread прошёл CoInitializeEx, второй видел flag=true и пропускал → у
// второго thread'а COM не init → CO_E_NOTINITIALIZED при WebView2 calls.
// CoInitializeEx idempotent per thread (S_FALSE если уже init'но).

static std::shared_ptr<WebViewInstance> findInstance(int64_t id) {
    std::lock_guard<std::mutex> lk(g_webviewsMutex);
    auto it = g_webviews.find(id);
    return (it == g_webviews.end()) ? nullptr : it->second;
}

// ────────────────────────────────────────────────────────────────────
// String helpers (UTF-8 ↔ UTF-16)
// ────────────────────────────────────────────────────────────────────

static std::wstring utf8ToWide(const char* s) {
    if (!s) return L"";
    int len = MultiByteToWideChar(CP_UTF8, 0, s, -1, nullptr, 0);
    if (len <= 0) return L"";
    std::wstring out(len - 1, L'\0');
    MultiByteToWideChar(CP_UTF8, 0, s, -1, out.data(), len);
    return out;
}

static std::string wideToUtf8(LPCWSTR ws) {
    if (!ws) return std::string();
    int len = WideCharToMultiByte(CP_UTF8, 0, ws, -1, nullptr, 0, nullptr, nullptr);
    if (len <= 0) return std::string();
    std::string out(len - 1, '\0');
    WideCharToMultiByte(CP_UTF8, 0, ws, -1, out.data(), len, nullptr, nullptr);
    return out;
}

// Caller (Java) frees through freeString().
static char* allocCStringUtf8FromWide(LPCWSTR ws) {
    if (!ws) return nullptr;
    std::string utf8 = wideToUtf8(ws);
    char* buf = (char*)CoTaskMemAlloc(utf8.size() + 1);
    if (!buf) return nullptr;
    memcpy(buf, utf8.c_str(), utf8.size() + 1);
    return buf;
}

// ────────────────────────────────────────────────────────────────────
// COM init (per-process, idempotent)
// ────────────────────────────────────────────────────────────────────

static void ensureComInitialized() {
    // STA — WebView2 требует STA. CoInitializeEx idempotent per thread:
    // second call returns S_FALSE (already init), не error. Вызываем на
    // каждый thread который дёргает WebView2 SDK.
    HRESULT hr = CoInitializeEx(nullptr, COINIT_APARTMENTTHREADED);
    dlog("CoInitializeEx on thread %lu returned 0x%08x",
        GetCurrentThreadId(), (unsigned)hr);
}

// ────────────────────────────────────────────────────────────────────
// Message-only HWND (используется как initial parent для WebView2 controller'а
// до тех пор пока не вызовут attachWebViewToWindow с реальным parent'ом)
// ────────────────────────────────────────────────────────────────────

static const wchar_t* kNativeUtilsClassName = L"OTLDHelperNativeUtilsHostWnd";
static std::atomic<bool> g_classRegistered{false};

static LRESULT CALLBACK hostWndProc(HWND h, UINT m, WPARAM w, LPARAM l) {
    return DefWindowProcW(h, m, w, l);
}

static void ensureWindowClassRegistered() {
    bool expected = false;
    if (!g_classRegistered.compare_exchange_strong(expected, true)) return;
    WNDCLASSEXW wc = {};
    wc.cbSize = sizeof(wc);
    wc.lpfnWndProc = hostWndProc;
    wc.hInstance = GetModuleHandleW(nullptr);
    wc.lpszClassName = kNativeUtilsClassName;
    RegisterClassExW(&wc);
}

static HWND createInitialParentHwnd() {
    ensureWindowClassRegistered();
    // WS_POPUP без WS_VISIBLE — невидимое top-level окно, валидное как initial
    // parent для WebView2 controller. После attachWebViewToWindow оно становится
    // ненужным (controller ре-парентится через SetParent), но мы не уничтожаем
    // его до destroyWebView чтобы избежать gотерянных HWND связей.
    HWND h = CreateWindowExW(
        0, kNativeUtilsClassName, L"OTLDNativeHost", WS_POPUP,
        0, 0, 1, 1,
        nullptr, nullptr, GetModuleHandleW(nullptr), nullptr
    );
    return h;
}

// ────────────────────────────────────────────────────────────────────
// Sync waiter — pump message loop until event signalled or timeout
// ────────────────────────────────────────────────────────────────────

static bool waitWithMessagePump(HANDLE evt, DWORD timeoutMs) {
    DWORD deadline = GetTickCount() + timeoutMs;
    while (true) {
        DWORD now = GetTickCount();
        if (now >= deadline) return false;
        DWORD remaining = deadline - now;
        DWORD waitResult = MsgWaitForMultipleObjects(
            1, &evt, FALSE, remaining > 100 ? 100 : remaining, QS_ALLINPUT);
        if (waitResult == WAIT_OBJECT_0) return true;
        if (waitResult == WAIT_OBJECT_0 + 1) {
            MSG msg;
            while (PeekMessageW(&msg, nullptr, 0, 0, PM_REMOVE)) {
                TranslateMessage(&msg);
                DispatchMessageW(&msg);
            }
        }
        // WAIT_TIMEOUT — продолжаем
    }
}

// ────────────────────────────────────────────────────────────────────
// Exports — Runtime detection
// ────────────────────────────────────────────────────────────────────

// §TZ-DESKTOP-NATIVE-2026-05 0.8.9 — pump Win32 messages for WebView2 events.
// WebView2 NavigationStarting/Completed events deliver через DispatchMessage
// в STA thread где controller был создан. WebView2Worker thread не pump'ит
// messages → events stuck в очереди → handlers не fire → юзер видит blank.
//
// Caller (Kotlin) дёргает pumpMessages каждые 16ms на webView2Dispatcher
// чтобы Edge IPC events delivered. PeekMessage не блокирует если очередь
// пуста.
extern "C" __declspec(dllexport) void pumpMessages() {
    MSG msg;
    int count = 0;
    while (PeekMessageW(&msg, nullptr, 0, 0, PM_REMOVE)) {
        TranslateMessage(&msg);
        DispatchMessageW(&msg);
        if (++count > 50) break;  // safety против infinite loop
    }
}

extern "C" __declspec(dllexport) BOOL isWebView2RuntimeAvailable() {
    LPWSTR version = nullptr;
    HRESULT hr = GetAvailableCoreWebView2BrowserVersionString(nullptr, &version);
    if (SUCCEEDED(hr) && version) {
        CoTaskMemFree(version);
        return TRUE;
    }
    return FALSE;
}

extern "C" __declspec(dllexport) char* getWebView2RuntimeVersion() {
    LPWSTR version = nullptr;
    HRESULT hr = GetAvailableCoreWebView2BrowserVersionString(nullptr, &version);
    if (FAILED(hr) || !version) return nullptr;
    char* result = allocCStringUtf8FromWide(version);
    CoTaskMemFree(version);
    return result;
}

// ────────────────────────────────────────────────────────────────────
// Exports — Settings (UserDataFolder must be set BEFORE createWebView)
// ────────────────────────────────────────────────────────────────────

extern "C" __declspec(dllexport) void setUserDataFolder(const char* path) {
    std::lock_guard<std::mutex> lk(g_userDataFolderMutex);
    g_userDataFolder = utf8ToWide(path);
}

// ────────────────────────────────────────────────────────────────────
// Exports — Lifecycle
// ────────────────────────────────────────────────────────────────────

extern "C" __declspec(dllexport) int64_t createWebViewWithSettings(BOOL javaScriptEnabled, BOOL allowsFileAccess) {
    dlog("createWebViewWithSettings(js=%d, fileAccess=%d) called",
        javaScriptEnabled, allowsFileAccess);

    // Explicit Runtime check first — корректный сигнал о реальной причине fail.
    LPWSTR runtimeVer = nullptr;
    HRESULT hrVer = GetAvailableCoreWebView2BrowserVersionString(nullptr, &runtimeVer);
    if (FAILED(hrVer) || !runtimeVer) {
        dlog("WebView2 Runtime missing (hr=0x%08x)", (unsigned)hrVer);
        return 0;
    }
    {
        std::string verUtf8 = wideToUtf8(runtimeVer);
        dlog("WebView2 Runtime version: %s", verUtf8.c_str());
        CoTaskMemFree(runtimeVer);
    }

    ensureComInitialized();
    dlog("COM initialized");

    HWND initialParent = createInitialParentHwnd();
    if (!initialParent) {
        dlog("createInitialParentHwnd FAILED (lastError=%lu)", GetLastError());
        return 0;
    }
    dlog("initialParent HWND=%p created", (void*)initialParent);

    HANDLE evt = CreateEventW(nullptr, TRUE, FALSE, nullptr);
    if (!evt) {
        DestroyWindow(initialParent);
        return 0;
    }

    ComPtr<ICoreWebView2Controller> resultController;
    HRESULT envCreateHr = E_FAIL;

    std::wstring userDataFolderCopy;
    {
        std::lock_guard<std::mutex> lk(g_userDataFolderMutex);
        userDataFolderCopy = g_userDataFolder;
    }
    LPCWSTR userDataFolderPtr = userDataFolderCopy.empty() ? nullptr : userDataFolderCopy.c_str();
    {
        std::string udfUtf8 = wideToUtf8(userDataFolderPtr ? userDataFolderPtr : L"<null>");
        dlog("userDataFolder=%s", udfUtf8.c_str());
    }

    HRESULT controllerHr = E_FAIL;
    HRESULT hr = CreateCoreWebView2EnvironmentWithOptions(
        nullptr, userDataFolderPtr, nullptr,
        Callback<ICoreWebView2CreateCoreWebView2EnvironmentCompletedHandler>(
            [&](HRESULT envResult, ICoreWebView2Environment* env) -> HRESULT {
                envCreateHr = envResult;
                if (FAILED(envResult) || !env) {
                    SetEvent(evt);
                    return S_OK;
                }
                env->CreateCoreWebView2Controller(
                    initialParent,
                    Callback<ICoreWebView2CreateCoreWebView2ControllerCompletedHandler>(
                        [&](HRESULT ctrlResult, ICoreWebView2Controller* ctrl) -> HRESULT {
                            controllerHr = ctrlResult;
                            if (SUCCEEDED(ctrlResult) && ctrl) {
                                resultController = ctrl;
                            }
                            SetEvent(evt);
                            return S_OK;
                        }).Get()
                );
                return S_OK;
            }).Get()
    );

    if (FAILED(hr)) {
        dlog("CreateCoreWebView2EnvironmentWithOptions FAILED hr=0x%08x", (unsigned)hr);
        CloseHandle(evt);
        DestroyWindow(initialParent);
        return 0;
    }

    // Sync wait — макс 10с (медленный диск, antivirus, первый запуск Edge).
    bool ok = waitWithMessagePump(evt, 10000);
    CloseHandle(evt);
    dlog("waitWithMessagePump finished ok=%d, envHr=0x%08x, ctrlHr=0x%08x, ctrl=%p",
        ok, (unsigned)envCreateHr, (unsigned)controllerHr, (void*)resultController.Get());

    if (!ok || !resultController) {
        dlog("createWebView FAILED — wait timeout or no controller");
        DestroyWindow(initialParent);
        return 0;
    }

    auto inst = std::make_shared<WebViewInstance>();
    inst->controller = resultController;
    inst->parentHwnd = initialParent;
    resultController->get_CoreWebView2(&inst->webview);

    // Apply settings.
    if (inst->webview) {
        ComPtr<ICoreWebView2Settings> settings;
        inst->webview->get_Settings(&settings);
        if (settings) {
            settings->put_IsScriptEnabled(javaScriptEnabled ? TRUE : FALSE);
            settings->put_AreDefaultContextMenusEnabled(TRUE);
            settings->put_IsZoomControlEnabled(FALSE);
            settings->put_IsStatusBarEnabled(FALSE);
            // §TZ-DESKTOP-NATIVE-2026-05 — DevTools отключены в production.
            // Включить через env var на Java стороне в будущем.
            settings->put_AreDevToolsEnabled(FALSE);
        }
    }

    // §TZ-DESKTOP-NATIVE-2026-05 fix #1 — global NavigationStarting/Completed
    // handlers для tracking isLoading. Также forward URL в Java navCallback
    // если он зарегистрирован через setNavigationCallback. Раньше подписывались
    // только когда navCallback был задан; теперь всегда — needed for reveal poll.
    int64_t id = g_nextWebViewId.fetch_add(1);
    inst->id = id;

    if (inst->webview) {
        std::weak_ptr<WebViewInstance> weakInst = inst;

        // §TZ-DESKTOP-NATIVE-2026-05 0.8.29 — DOMContentLoaded для precise timing
        // когда DOM готов для CSS injection. ICoreWebView2_2 introduced это.
        ComPtr<ICoreWebView2_2> webview2;
        if (SUCCEEDED(inst->webview.As(&webview2)) && webview2) {
            webview2->add_DOMContentLoaded(
                Callback<ICoreWebView2DOMContentLoadedEventHandler>(
                    [weakInst](ICoreWebView2*, ICoreWebView2DOMContentLoadedEventArgs*) -> HRESULT {
                        auto strongInst = weakInst.lock();
                        if (strongInst) {
                            dlog("DOMContentLoaded(id=%lld)", (long long)strongInst->id);
                        }
                        return S_OK;
                    }).Get(),
                &inst->domContentLoadedToken
            );
        }

        // HistoryChanged — fires при push/pop state и hash changes (включая
        // sheet switches через JS click bridge).
        inst->webview->add_HistoryChanged(
            Callback<ICoreWebView2HistoryChangedEventHandler>(
                [weakInst](ICoreWebView2* sender, IUnknown*) -> HRESULT {
                    auto strongInst = weakInst.lock();
                    if (strongInst && sender) {
                        LPWSTR uri = nullptr;
                        sender->get_Source(&uri);
                        if (uri) {
                            std::string utf8 = wideToUtf8(uri);
                            dlog("HistoryChanged(id=%lld, url=%s)",
                                (long long)strongInst->id, utf8.c_str());
                            CoTaskMemFree(uri);
                        }
                    }
                    return S_OK;
                }).Get(),
            &inst->historyChangedToken
        );

        // WebMessageReceived — JS может слать сигналы через
        // window.chrome.webview.postMessage("маркер"). Foundation для
        // signal-based reveal — JS подтверждает "маска применена".
        inst->webview->add_WebMessageReceived(
            Callback<ICoreWebView2WebMessageReceivedEventHandler>(
                [weakInst](ICoreWebView2*, ICoreWebView2WebMessageReceivedEventArgs* args) -> HRESULT {
                    auto strongInst = weakInst.lock();
                    if (!strongInst || !args) return S_OK;
                    LPWSTR msg = nullptr;
                    args->TryGetWebMessageAsString(&msg);
                    if (msg) {
                        std::string utf8 = wideToUtf8(msg);
                        dlog("WebMessage(id=%lld, msg=%s)",
                            (long long)strongInst->id, utf8.c_str());
                        {
                            std::lock_guard<std::mutex> lk(strongInst->webMessagesMutex);
                            strongInst->webMessages.push_back(utf8);
                            if (strongInst->webMessages.size() > 64) {
                                strongInst->webMessages.erase(strongInst->webMessages.begin());
                            }
                        }
                        CoTaskMemFree(msg);
                    }
                    return S_OK;
                }).Get(),
            &inst->webMessageToken
        );

        HRESULT hrNs = inst->webview->add_NavigationStarting(
            Callback<ICoreWebView2NavigationStartingEventHandler>(
                [weakInst](ICoreWebView2*, ICoreWebView2NavigationStartingEventArgs* args) -> HRESULT {
                    auto strongInst = weakInst.lock();
                    dlog("[event] NavigationStarting fired (inst alive=%d)", strongInst ? 1 : 0);
                    if (!strongInst || !args) return S_OK;
                    strongInst->isLoading = true;
                    LPWSTR uri = nullptr;
                    args->get_Uri(&uri);
                    if (uri) {
                        std::string utf8 = wideToUtf8(uri);
                        dlog("NavigationStarting(id=%lld, url=%s)",
                            (long long)strongInst->id, utf8.c_str());
                        if (strongInst->navCallback) {
                            int cancel = strongInst->navCallback(strongInst->id, utf8.c_str());
                            if (cancel) args->put_Cancel(TRUE);
                        }
                        CoTaskMemFree(uri);
                    }
                    return S_OK;
                }).Get(),
            &inst->navStartingToken
        );
        HRESULT hrNc = inst->webview->add_NavigationCompleted(
            Callback<ICoreWebView2NavigationCompletedEventHandler>(
                [weakInst](ICoreWebView2*, ICoreWebView2NavigationCompletedEventArgs* args) -> HRESULT {
                    auto strongInst = weakInst.lock();
                    dlog("[event] NavigationCompleted fired (inst alive=%d)", strongInst ? 1 : 0);
                    if (!strongInst) return S_OK;
                    strongInst->isLoading = false;
                    BOOL success = FALSE;
                    if (args) args->get_IsSuccess(&success);
                    dlog("NavigationCompleted(id=%lld, success=%d)",
                        (long long)strongInst->id, success);
                    return S_OK;
                }).Get(),
            &inst->navCompletedToken
        );
        dlog("Event handlers registered: NavStarting=0x%08x (token=%lld), NavCompleted=0x%08x (token=%lld)",
            (unsigned)hrNs, (long long)inst->navStartingToken.value,
            (unsigned)hrNc, (long long)inst->navCompletedToken.value);
    }

    // Initially hidden until Java side calls setWebViewVisible(true).
    resultController->put_IsVisible(FALSE);
    inst->visible = false;

    {
        std::lock_guard<std::mutex> lk(g_webviewsMutex);
        g_webviews[id] = inst;
    }
    return id;
}

extern "C" __declspec(dllexport) int64_t createWebView() {
    return createWebViewWithSettings(TRUE, TRUE);
}

extern "C" __declspec(dllexport) void destroyAllWebViews() {
    dlog("destroyAllWebViews called");
    std::vector<std::shared_ptr<WebViewInstance>> instances;
    {
        std::lock_guard<std::mutex> lk(g_webviewsMutex);
        for (auto& kv : g_webviews) instances.push_back(kv.second);
        g_webviews.clear();
    }
    for (auto& inst : instances) {
        if (inst->webview) {
            if (inst->navStartingToken.value != 0) {
                inst->webview->remove_NavigationStarting(inst->navStartingToken);
            }
            if (inst->navCompletedToken.value != 0) {
                inst->webview->remove_NavigationCompleted(inst->navCompletedToken);
            }
            if (inst->historyChangedToken.value != 0) {
                inst->webview->remove_HistoryChanged(inst->historyChangedToken);
            }
            if (inst->webMessageToken.value != 0) {
                inst->webview->remove_WebMessageReceived(inst->webMessageToken);
            }
            ComPtr<ICoreWebView2_2> webview2;
            if (inst->domContentLoadedToken.value != 0
                && SUCCEEDED(inst->webview.As(&webview2)) && webview2) {
                webview2->remove_DOMContentLoaded(inst->domContentLoadedToken);
            }
        }
        if (inst->controller) {
            inst->controller->Close();
        }
        if (inst->parentHwnd) {
            DestroyWindow(inst->parentHwnd);
        }
    }
    dlog("destroyAllWebViews: %zu instances cleaned", instances.size());
}

extern "C" __declspec(dllexport) void destroyWebView(int64_t id) {
    std::shared_ptr<WebViewInstance> inst;
    {
        std::lock_guard<std::mutex> lk(g_webviewsMutex);
        auto it = g_webviews.find(id);
        if (it == g_webviews.end()) return;
        inst = it->second;
        g_webviews.erase(it);
    }
    if (inst->webview) {
        if (inst->navStartingToken.value != 0) {
            inst->webview->remove_NavigationStarting(inst->navStartingToken);
        }
        if (inst->navCompletedToken.value != 0) {
            inst->webview->remove_NavigationCompleted(inst->navCompletedToken);
        }
        if (inst->historyChangedToken.value != 0) {
            inst->webview->remove_HistoryChanged(inst->historyChangedToken);
        }
        if (inst->webMessageToken.value != 0) {
            inst->webview->remove_WebMessageReceived(inst->webMessageToken);
        }
        ComPtr<ICoreWebView2_2> webview2;
        if (inst->domContentLoadedToken.value != 0
            && SUCCEEDED(inst->webview.As(&webview2)) && webview2) {
            webview2->remove_DOMContentLoaded(inst->domContentLoadedToken);
        }
    }
    if (inst->controller) {
        inst->controller->Close();
    }
    if (inst->parentHwnd) {
        DestroyWindow(inst->parentHwnd);
    }
}

// ────────────────────────────────────────────────────────────────────
// Exports — Embedding / framing
// ────────────────────────────────────────────────────────────────────

extern "C" __declspec(dllexport) void attachWebViewToWindow(int64_t id, HWND parentHwnd) {
    ensureComInitialized();
    dlog("attachWebViewToWindow(id=%lld, hwnd=%p) thread=%lu",
        (long long)id, (void*)parentHwnd, GetCurrentThreadId());
    auto inst = findInstance(id);
    if (!inst || !inst->controller || !parentHwnd) {
        dlog("attachWebViewToWindow: bad args (inst=%p, ctrl=%p, hwnd=%p)",
            (void*)inst.get(),
            (void*)(inst ? inst->controller.Get() : nullptr),
            (void*)parentHwnd);
        return;
    }
    HRESULT hr1 = inst->controller->put_ParentWindow(parentHwnd);
    HRESULT hr2 = inst->controller->NotifyParentWindowPositionChanged();
    dlog("attachWebViewToWindow: put_ParentWindow=0x%08x, NotifyParent=0x%08x",
        (unsigned)hr1, (unsigned)hr2);
}

extern "C" __declspec(dllexport) void setWebViewFrame(int64_t id, int x, int y, int width, int height) {
    ensureComInitialized();
    dlog("setWebViewFrame(id=%lld, x=%d, y=%d, w=%d, h=%d) thread=%lu",
        (long long)id, x, y, width, height, GetCurrentThreadId());
    auto inst = findInstance(id);
    if (!inst || !inst->controller) {
        dlog("setWebViewFrame: bad inst/controller");
        return;
    }
    RECT r;
    r.left = x;
    r.top = y;
    r.right = x + width;
    r.bottom = y + height;
    HRESULT hr1 = inst->controller->put_Bounds(r);
    HRESULT hr2 = inst->controller->NotifyParentWindowPositionChanged();
    dlog("setWebViewFrame: put_Bounds=0x%08x, NotifyParent=0x%08x", (unsigned)hr1, (unsigned)hr2);
}

// Найти WebView2 child HWND через class name lookup.
// WebView2 создаёт child Chrome_WidgetWin_* class window inside parent.
struct FindChildContext {
    HWND result = nullptr;
};

static BOOL CALLBACK findWebView2ChildProc(HWND hwnd, LPARAM lParam) {
    wchar_t className[256] = {};
    if (GetClassNameW(hwnd, className, 256) > 0) {
        if (wcsstr(className, L"Chrome_WidgetWin") || wcsstr(className, L"Intermediate")) {
            ((FindChildContext*)lParam)->result = hwnd;
            return FALSE; // stop
        }
    }
    return TRUE;
}

extern "C" __declspec(dllexport) void forceWebViewDisplay(int64_t id) {
    ensureComInitialized();
    auto inst = findInstance(id);
    if (!inst || !inst->controller) return;
    HWND parent = nullptr;
    inst->controller->get_ParentWindow(&parent);
    if (!parent) return;

    // §TZ-DESKTOP-NATIVE-2026-05 0.8.8 — z-order fix.
    // Compose Desktop рисует через Skia в parent HWND, и canvas overdraws
    // native child HWND if z-order не enforce. Находим WebView2 child HWND
    // и форсим его HWND_TOP в parent's z-order.
    FindChildContext ctx;
    EnumChildWindows(parent, findWebView2ChildProc, (LPARAM)&ctx);
    if (ctx.result) {
        // §TZ-DESKTOP-NATIVE-2026-05 0.8.11 — БЕЗ SWP_SHOWWINDOW.
        // forceWebViewDisplay вызывается из polling каждые 180ms — если
        // SWP_SHOWWINDOW, webview принудительно показывается даже когда
        // controller->put_IsVisible(FALSE) → cat splash перекрывается.
        // Только z-order, не visibility.
        SetWindowPos(ctx.result, HWND_TOP, 0, 0, 0, 0,
            SWP_NOMOVE | SWP_NOSIZE | SWP_NOACTIVATE);
    }
    InvalidateRect(parent, nullptr, FALSE);
    UpdateWindow(parent);
}

// ────────────────────────────────────────────────────────────────────
// Exports — Navigation
// ────────────────────────────────────────────────────────────────────

extern "C" __declspec(dllexport) BOOL loadURL(int64_t id, const char* urlString) {
    ensureComInitialized();
    dlog("loadURL(id=%lld, url=%s) thread=%lu",
        (long long)id, urlString ? urlString : "<null>", GetCurrentThreadId());
    auto inst = findInstance(id);
    if (!inst || !inst->webview || !urlString) {
        dlog("loadURL: bad args (inst=%p, webview=%p)",
            (void*)inst.get(), (void*)(inst ? inst->webview.Get() : nullptr));
        return FALSE;
    }
    std::wstring wurl = utf8ToWide(urlString);
    inst->isLoading = true;
    HRESULT hr = inst->webview->Navigate(wurl.c_str());
    dlog("loadURL: Navigate returned 0x%08x", (unsigned)hr);
    if (FAILED(hr)) {
        inst->isLoading = false;
    }
    return SUCCEEDED(hr) ? TRUE : FALSE;
}

extern "C" __declspec(dllexport) void loadHTMLString(int64_t id, const char* html, const char* /*baseUrl*/) {
    auto inst = findInstance(id);
    if (!inst || !inst->webview || !html) return;
    std::wstring whtml = utf8ToWide(html);
    inst->webview->NavigateToString(whtml.c_str());
}

extern "C" __declspec(dllexport) void webViewGoBack(int64_t id) {
    auto inst = findInstance(id);
    if (inst && inst->webview) inst->webview->GoBack();
}
extern "C" __declspec(dllexport) void webViewGoForward(int64_t id) {
    auto inst = findInstance(id);
    if (inst && inst->webview) inst->webview->GoForward();
}
extern "C" __declspec(dllexport) void webViewReload(int64_t id) {
    auto inst = findInstance(id);
    if (inst && inst->webview) inst->webview->Reload();
}
extern "C" __declspec(dllexport) void webViewStopLoading(int64_t id) {
    auto inst = findInstance(id);
    if (inst && inst->webview) inst->webview->Stop();
}
extern "C" __declspec(dllexport) BOOL webViewCanGoBack(int64_t id) {
    auto inst = findInstance(id);
    if (!inst || !inst->webview) return FALSE;
    BOOL canGo = FALSE;
    inst->webview->get_CanGoBack(&canGo);
    return canGo;
}
extern "C" __declspec(dllexport) BOOL webViewCanGoForward(int64_t id) {
    auto inst = findInstance(id);
    if (!inst || !inst->webview) return FALSE;
    BOOL canGo = FALSE;
    inst->webview->get_CanGoForward(&canGo);
    return canGo;
}
extern "C" __declspec(dllexport) BOOL webViewIsLoading(int64_t id) {
    // Не нужен COM init — это просто read атомарного флага.
    auto inst = findInstance(id);
    if (!inst) return FALSE;
    return inst->isLoading.load() ? TRUE : FALSE;
}
extern "C" __declspec(dllexport) double webViewGetProgress(int64_t /*id*/) {
    return 1.0;
}

extern "C" __declspec(dllexport) void setNavigationCallback(int64_t id, JavaNavCallback callback) {
    // §TZ-DESKTOP-NATIVE-2026-05 fix #1 — global NavigationStarting handler
    // подписан в createWebViewWithSettings; здесь просто меняем поле
    // navCallback которое handler читает через weak_ptr. Не дублируем подписку.
    auto inst = findInstance(id);
    if (!inst) return;
    inst->navCallback = callback;
}

// ────────────────────────────────────────────────────────────────────
// Exports — Scripting
// ────────────────────────────────────────────────────────────────────

extern "C" __declspec(dllexport) void evaluateJavaScript(int64_t id, const char* jsCode) {
    ensureComInitialized();
    auto inst = findInstance(id);
    if (!inst || !inst->webview || !jsCode) {
        dlog("evaluateJavaScript: bad args (inst=%p, webview=%p, js=%p)",
            (void*)inst.get(),
            (void*)(inst ? inst->webview.Get() : nullptr),
            (const void*)jsCode);
        return;
    }
    size_t len = strlen(jsCode);
    std::wstring wjs = utf8ToWide(jsCode);
    HRESULT hr = inst->webview->ExecuteScript(wjs.c_str(), nullptr);
    dlog("evaluateJavaScript(id=%lld, jsLen=%zu) thread=%lu hr=0x%08x",
        (long long)id, len, GetCurrentThreadId(), (unsigned)hr);
}

extern "C" __declspec(dllexport) char* webViewPopWebMessage(int64_t id) {
    auto inst = findInstance(id);
    if (!inst) return nullptr;
    std::string msg;
    {
        std::lock_guard<std::mutex> lk(inst->webMessagesMutex);
        if (inst->webMessages.empty()) return nullptr;
        msg = inst->webMessages.front();
        inst->webMessages.erase(inst->webMessages.begin());
    }
    char* buf = (char*)CoTaskMemAlloc(msg.size() + 1);
    if (!buf) return nullptr;
    memcpy(buf, msg.c_str(), msg.size() + 1);
    return buf;
}

// ────────────────────────────────────────────────────────────────────
// Exports — Visibility
// ────────────────────────────────────────────────────────────────────

extern "C" __declspec(dllexport) void setWebViewVisible(int64_t id, BOOL visible) {
    ensureComInitialized();
    auto inst = findInstance(id);
    if (!inst || !inst->controller) return;
    HRESULT hr = inst->controller->put_IsVisible(visible ? TRUE : FALSE);
    dlog("setWebViewVisible(id=%lld, visible=%d) thread=%lu hr=0x%08x",
        (long long)id, visible, GetCurrentThreadId(), (unsigned)hr);
    inst->visible = (visible != FALSE);
    // §TZ-DESKTOP-NATIVE-2026-05 0.8.11 — explicit z-order:
    //   visible=TRUE  → HWND_TOP + SWP_SHOWWINDOW (поверх Compose splash)
    //   visible=FALSE → HWND_BOTTOM + SWP_HIDEWINDOW (под Compose splash, скрыт)
    HWND parent = nullptr;
    inst->controller->get_ParentWindow(&parent);
    if (parent) {
        FindChildContext ctx;
        EnumChildWindows(parent, findWebView2ChildProc, (LPARAM)&ctx);
        if (ctx.result) {
            if (visible) {
                SetWindowPos(ctx.result, HWND_TOP, 0, 0, 0, 0,
                    SWP_NOMOVE | SWP_NOSIZE | SWP_NOACTIVATE | SWP_SHOWWINDOW);
            } else {
                SetWindowPos(ctx.result, HWND_BOTTOM, 0, 0, 0, 0,
                    SWP_NOMOVE | SWP_NOSIZE | SWP_NOACTIVATE | SWP_HIDEWINDOW);
            }
        }
    }
}

extern "C" __declspec(dllexport) void setWebViewAlpha(int64_t id, double alpha) {
    // WebView2 не поддерживает alpha напрямую. Mac symmetry: <0.5 → hide, else show.
    auto inst = findInstance(id);
    if (!inst || !inst->controller) return;
    BOOL show = alpha >= 0.5 ? TRUE : FALSE;
    inst->controller->put_IsVisible(show);
}

extern "C" __declspec(dllexport) void bringWebViewToFront(int64_t /*id*/) {
    // No-op для WebView2 — он рисуется как child HWND, z-order контролируется
    // parent'ом через стандартный SetWindowPos. Mac symmetry — WKWebView NSView
    // re-ordering. Если потребуется — SetWindowPos(controllerHwnd, HWND_TOP, ...).
}

extern "C" __declspec(dllexport) void sendWebViewToBack(int64_t /*id*/) {
    // No-op (см. bringWebViewToFront).
}

extern "C" __declspec(dllexport) void setBrowserAcceleratorKeysEnabled(int64_t id, BOOL enabled) {
    auto inst = findInstance(id);
    if (!inst || !inst->webview) return;
    ComPtr<ICoreWebView2Settings> settings;
    inst->webview->get_Settings(&settings);
    if (!settings) return;
    ComPtr<ICoreWebView2Settings3> settings3;
    if (SUCCEEDED(settings.As(&settings3)) && settings3) {
        settings3->put_AreBrowserAcceleratorKeysEnabled(enabled ? TRUE : FALSE);
    }
}

extern "C" __declspec(dllexport) void setCustomUserAgent(int64_t id, const char* userAgent) {
    auto inst = findInstance(id);
    if (!inst || !inst->webview || !userAgent) return;
    ComPtr<ICoreWebView2Settings> settings;
    inst->webview->get_Settings(&settings);
    if (!settings) return;
    ComPtr<ICoreWebView2Settings2> settings2;
    if (SUCCEEDED(settings.As(&settings2)) && settings2) {
        std::wstring wua = utf8ToWide(userAgent);
        settings2->put_UserAgent(wua.c_str());
    }
}

// ────────────────────────────────────────────────────────────────────
// Exports — State queries
// ────────────────────────────────────────────────────────────────────

extern "C" __declspec(dllexport) char* webViewGetCurrentURL(int64_t id) {
    ensureComInitialized();
    auto inst = findInstance(id);
    if (!inst || !inst->webview) return nullptr;
    LPWSTR uri = nullptr;
    HRESULT hr = inst->webview->get_Source(&uri);
    if (FAILED(hr) || !uri) {
        // Лог только если ошибка (чтобы не спамить).
        if (FAILED(hr)) dlog("webViewGetCurrentURL: get_Source FAILED 0x%08x", (unsigned)hr);
        return nullptr;
    }
    char* result = allocCStringUtf8FromWide(uri);
    CoTaskMemFree(uri);
    return result;
}

extern "C" __declspec(dllexport) char* webViewGetTitle(int64_t id) {
    auto inst = findInstance(id);
    if (!inst || !inst->webview) return nullptr;
    LPWSTR title = nullptr;
    if (FAILED(inst->webview->get_DocumentTitle(&title)) || !title) return nullptr;
    char* result = allocCStringUtf8FromWide(title);
    CoTaskMemFree(title);
    return result;
}

extern "C" __declspec(dllexport) void freeString(char* s) {
    if (s) CoTaskMemFree(s);
}

extern "C" __declspec(dllexport) double getWindowContentHeight(HWND hwnd) {
    if (!hwnd) return 0.0;
    RECT r;
    if (!GetClientRect(hwnd, &r)) return 0.0;
    return (double)(r.bottom - r.top);
}

// ────────────────────────────────────────────────────────────────────
// DllMain
// ────────────────────────────────────────────────────────────────────

BOOL APIENTRY DllMain(HMODULE /*hModule*/, DWORD reason, LPVOID /*reserved*/) {
    switch (reason) {
        case DLL_PROCESS_ATTACH:
            // COM init происходит лениво при первом createWebView
            // (нельзя в DllMain — restricted context).
            dlog("=== NativeUtils.dll loaded (DLL_PROCESS_ATTACH) ===");
            break;
        case DLL_PROCESS_DETACH:
            dlog("=== NativeUtils.dll unloaded ===");
            break;
    }
    return TRUE;
}
