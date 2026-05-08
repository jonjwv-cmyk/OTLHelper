package com.example.otlhelper.desktop.sheets.nativeweb

import com.sun.jna.Callback
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer

internal interface MacNavigationCallback : Callback {
    fun invoke(webViewId: Long, url: String): Boolean
}

internal interface MacWebViewNative : Library {
    fun createWebView(): Long
    fun createWebViewWithSettings(javaScriptEnabled: Boolean, allowsFileAccess: Boolean): Long
    fun destroyWebView(webViewId: Long)
    fun setNavigationCallback(webViewId: Long, callback: MacNavigationCallback?)
    fun attachWebViewToWindow(webViewId: Long, nsWindowPtr: Pointer)
    fun setWebViewFrameFlipped(
        webViewId: Long,
        x: Double,
        y: Double,
        width: Double,
        height: Double,
        parentHeight: Double,
    )
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
    fun evaluateJavaScript(webViewId: Long, jsCode: String)
    fun setWebViewVisible(webViewId: Long, visible: Boolean)
    fun setWebViewAlpha(webViewId: Long, alpha: Double)
    fun bringWebViewToFront(webViewId: Long)
    fun sendWebViewToBack(webViewId: Long)
    fun setCustomUserAgent(webViewId: Long, userAgent: String)
    fun webViewGetCurrentURL(webViewId: Long): Pointer?
    fun webViewGetTitle(webViewId: Long): Pointer?
    fun freeString(str: Pointer?)
    fun getWindowContentHeight(nsWindowPtr: Pointer): Double
    fun forceWebViewDisplay(webViewId: Long)

    companion object {
        val isMacOS: Boolean by lazy {
            System.getProperty("os.name")?.lowercase()?.contains("mac") == true
        }

        val instance: MacWebViewNative by lazy {
            Native.load("NativeUtils", MacWebViewNative::class.java)
        }
    }
}

internal object MacWebViewStrings {
    fun currentUrl(webViewId: Long): String? {
        val ptr = MacWebViewNative.instance.webViewGetCurrentURL(webViewId) ?: return null
        return try {
            ptr.getString(0)
        } finally {
            MacWebViewNative.instance.freeString(ptr)
        }
    }

    fun title(webViewId: Long): String? {
        val ptr = MacWebViewNative.instance.webViewGetTitle(webViewId) ?: return null
        return try {
            ptr.getString(0)
        } finally {
            MacWebViewNative.instance.freeString(ptr)
        }
    }
}

