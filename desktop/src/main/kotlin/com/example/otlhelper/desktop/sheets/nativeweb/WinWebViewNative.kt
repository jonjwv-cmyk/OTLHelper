package com.example.otlhelper.desktop.sheets.nativeweb

import com.sun.jna.Callback
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer

/**
 * §TZ-DESKTOP-NATIVE-2026-05 — Windows WebView2 bridge через JNA.
 *
 * Структурно зеркалит [MacWebViewNative]. Реализация — собственный
 * C++ wrapper (`NativeUtils.dll`) поверх Microsoft WebView2 SDK + COM.
 *
 * # Lifecycle
 *
 * 1. [createWebViewWithSettings] — синхронно создаёт WebView2 controller.
 *    На Win это блокирует thread на ~50-300ms пока WebView2 init проходит
 *    (CoreWebView2Environment + Controller create через async COM API,
 *    C++ внутри pump'ит message loop до завершения).
 * 2. [attachWebViewToWindow] — `SetParent(child_hwnd, parent_hwnd)`. Parent
 *    HWND берётся из `ComposeWindow.windowHandle`.
 * 3. [setWebViewFrame] — `controller->put_Bounds(RECT)`. Координаты в
 *    client area parent HWND, top-left=(0,0), Y растёт вниз (НЕ flipped
 *    как на Mac NSView).
 * 4. [destroyWebView] — `controller->Close()`, освобождение COM refs.
 *
 * # Cookies / Login persistence
 *
 * UserDataFolder задаётся через [setUserDataFolder] **до** [createWebView].
 * Дефолт — `%LOCALAPPDATA%\.otldhelper\webview2\`. Cookies сохраняются между
 * запусками автоматически — Edge WebView2 хранит весь Chromium profile там.
 *
 * # Keyboard
 *
 * WebView2 перехватывает Ctrl+V/C/X/A/Z до AWT, поэтому AWT KeyEventDispatcher
 * НЕ работает (в отличие от Mac NSView через SwingPanel). Решение —
 * [setBrowserAcceleratorKeysEnabled]: при `false` WebView2 не трогает Win+key
 * shortcuts (Ctrl+T новая вкладка и т.п.), но Ctrl+V/C/X/A в input полях
 * остаётся работать через нативное Edge поведение. Внутри ячеек Sheets —
 * Google JS handler перехватывает paste из clipboard сам.
 *
 * # WebView2 Runtime
 *
 * Должен быть установлен на Win 10/11. Обычно приходит через Windows Update +
 * Edge. Если нет — `winget install Microsoft.EdgeWebView2`. Detection через
 * [isWebView2RuntimeAvailable]: вернёт false если Runtime не найден; вызывающий
 * показывает placeholder с инструкцией.
 */
internal interface WinNavigationCallback : Callback {
    fun invoke(webViewId: Long, url: String): Boolean
}

internal interface WinWebViewNative : Library {
    // ── Runtime detect ─────────────────────────────────────────────
    /** Вернёт true если WebView2 Runtime установлен. Проверка реестра. */
    fun isWebView2RuntimeAvailable(): Boolean

    /** Cleanup всех webview controllers + Edge subprocesses. Вызывать на app
     *  Quit чтобы ничего не оставалось в диспетчере задач. */
    fun destroyAllWebViews()

    /** Process Win32 messages для thread на котором вызывается. WebView2 events
     *  доставляются через DispatchMessage. WebView2Worker thread должен дёргать
     *  это часто (раз в ~16ms) чтобы NavigationStarting/Completed handlers
     *  срабатывали. */
    fun pumpMessages()

    /** Версия установленного Runtime'а как C string (free через [freeString]).
     *  null если Runtime не найден. */
    fun getWebView2RuntimeVersion(): Pointer?

    // ── Lifecycle ───────────────────────────────────────────────────
    /** Создать WebView2 controller. UserDataFolder = дефолт (см. setUserDataFolder
     *  если нужно перекрыть). Возвращает webViewId; 0 = ошибка. Блокирует thread
     *  на ~50-300ms пока WebView2 async init завершается (C++ pump'ит messages). */
    fun createWebView(): Long

    /** Аналог [createWebView] с явными settings flags. */
    fun createWebViewWithSettings(javaScriptEnabled: Boolean, allowsFileAccess: Boolean): Long

    /** Закрыть controller, освободить COM refs. id становится невалидным. */
    fun destroyWebView(webViewId: Long)

    // ── Embedding ───────────────────────────────────────────────────
    /** SetParent child HWND под parent HWND (ComposeWindow.windowHandle).
     *  После этого WebView2 рисуется внутри Compose окна. */
    fun attachWebViewToWindow(webViewId: Long, hwndPtr: Pointer)

    /** Координаты в client area parent HWND. Y растёт вниз (Win convention).
     *  parentHeight параметр игнорируется на Win, оставлен для symmetry с Mac. */
    fun setWebViewFrame(webViewId: Long, x: Int, y: Int, width: Int, height: Int)

    /** Force redraw — `InvalidateRect(NULL, TRUE)` + `UpdateWindow`. После
     *  setVisible / setFrame некоторые юзеры видели stale buffer без этого. */
    fun forceWebViewDisplay(webViewId: Long)

    // ── Settings ────────────────────────────────────────────────────
    /** Путь к UserDataFolder для cookies/cache/profile. Должно быть absolute path,
     *  директория создаётся автоматически. Вызывать ДО [createWebView] для эффекта;
     *  на уже созданном controller — no-op. */
    fun setUserDataFolder(path: String)

    /** ICoreWebView2Settings::AreBrowserAcceleratorKeysEnabled. False — WebView2
     *  не перехватывает Ctrl+T, Ctrl+W и т.п., но Ctrl+V/C/X/A в полях остаётся. */
    fun setBrowserAcceleratorKeysEnabled(webViewId: Long, enabled: Boolean)

    fun setCustomUserAgent(webViewId: Long, userAgent: String)

    // ── Navigation ──────────────────────────────────────────────────
    fun loadURL(webViewId: Long, urlString: String): Boolean
    fun loadHTMLString(webViewId: Long, htmlString: String, baseURLString: String?)
    fun webViewGoBack(webViewId: Long)
    fun webViewGoForward(webViewId: Long)
    fun webViewReload(webViewId: Long)
    fun webViewStopLoading(webViewId: Long)
    fun webViewCanGoBack(webViewId: Long): Boolean
    fun webViewCanGoForward(webViewId: Long): Boolean
    fun webViewIsLoading(webViewId: Long): Boolean
    fun webViewGetProgress(webViewId: Long): Double

    /** NavigationStarting handler. Возврат true → cancel navigation (PreventDefault). */
    fun setNavigationCallback(webViewId: Long, callback: WinNavigationCallback?)

    // ── Scripting ───────────────────────────────────────────────────
    fun evaluateJavaScript(webViewId: Long, jsCode: String)
    fun webViewPopWebMessage(webViewId: Long): Pointer?

    /**
     * §0.11.13 — Pre-injection startup script.
     *
     * Регистрирует JS-скрипт который будет выполняться **ДО любого
     * скрипта страницы** на каждой новой Navigate. Эффективен ТОЛЬКО для
     * NEXT navigations — если webview уже загрузил страницу, она не
     * получит этот скрипт (используй [evaluateJavaScript] для текущей).
     *
     * Под капотом — `ICoreWebView2::AddScriptToExecuteOnDocumentCreated`.
     *
     * Возвращает 0 (S_OK) при успехе, не-0 HRESULT при ошибке.
     *
     * Use case: применение CSS-маски Sheets ДО первого render — таблица
     * никогда не показывает свой chrome, нет race с body parsing.
     */
    fun webViewAddStartupScript(webViewId: Long, jsCode: String): Int

    // ── Visibility ──────────────────────────────────────────────────
    fun setWebViewVisible(webViewId: Long, visible: Boolean)
    /** Win HWND не имеет alpha, но мы можем использовать `SetLayeredWindowAttributes`.
     *  Для PoC реализуем как show/hide порог: alpha < 0.5 → hide, else → show. */
    fun setWebViewAlpha(webViewId: Long, alpha: Double)
    fun bringWebViewToFront(webViewId: Long)
    fun sendWebViewToBack(webViewId: Long)

    // ── State queries ───────────────────────────────────────────────
    /** Возвращает C string (free через [freeString]). Может вернуть null если
     *  controller ещё не загрузил первую страницу. */
    fun webViewGetCurrentURL(webViewId: Long): Pointer?
    fun webViewGetTitle(webViewId: Long): Pointer?

    /** Освободить string возвращённый [webViewGetCurrentURL]/[webViewGetTitle]. */
    fun freeString(str: Pointer?)

    /** Mac symmetry: высота client area parent HWND. Используется только для
     *  координатных вычислений на Mac (flipped Y). На Win не нужно — но
     *  оставляем для совместимости вызовов из shared code. Возвращает простую
     *  GetClientRect height. */
    fun getWindowContentHeight(hwndPtr: Pointer): Double

    companion object {
        val isWindows: Boolean by lazy {
            System.getProperty("os.name")?.lowercase()?.contains("win") == true
        }

        /**
         * JNA автоматически резолвит `NativeUtils` → `NativeUtils.dll` на Win
         * (классpath `win32-x86-64/NativeUtils.dll` как resource).
         */
        val instance: WinWebViewNative by lazy {
            Native.load("NativeUtils", WinWebViewNative::class.java)
        }
    }
}

internal object WinWebViewStrings {
    fun currentUrl(webViewId: Long): String? {
        val ptr = WinWebViewNative.instance.webViewGetCurrentURL(webViewId) ?: return null
        return try {
            ptr.getString(0, "UTF-8")
        } finally {
            WinWebViewNative.instance.freeString(ptr)
        }
    }

    fun title(webViewId: Long): String? {
        val ptr = WinWebViewNative.instance.webViewGetTitle(webViewId) ?: return null
        return try {
            ptr.getString(0, "UTF-8")
        } finally {
            WinWebViewNative.instance.freeString(ptr)
        }
    }

    fun runtimeVersion(): String? {
        val ptr = WinWebViewNative.instance.getWebView2RuntimeVersion() ?: return null
        return try {
            ptr.getString(0, "UTF-8")
        } finally {
            WinWebViewNative.instance.freeString(ptr)
        }
    }

    fun popWebMessage(webViewId: Long): String? {
        val ptr = WinWebViewNative.instance.webViewPopWebMessage(webViewId) ?: return null
        return try {
            ptr.getString(0, "UTF-8")
        } finally {
            WinWebViewNative.instance.freeString(ptr)
        }
    }
}
