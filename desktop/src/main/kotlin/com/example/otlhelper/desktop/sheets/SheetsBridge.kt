package com.example.otlhelper.desktop.sheets

import java.util.concurrent.CopyOnWriteArrayList

/**
 * §TZ-DESKTOP 0.4.x — bridge между Compose UI и встроенным Sheets-браузером.
 *
 * **Use cases:**
 * 1. **CSS-инжект** при открытии overlay'ев (blur Sheets, toggle menus visible).
 *    Lightweight Compose-overlay'и невидимы под heavyweight Chromium NSView,
 *    поэтому изменяем DOM Sheets изнутри через CSS filter / dataset attribute.
 * 2. **Click-outside dismiss** для DialogWindow'ов: WindowFocusListener
 *    ненадёжен при `alwaysOnTop=true` или когда Chromium NSView съедает
 *    клик. SheetsBrowserHost ставит MouseListener на canvas → dismissAll() →
 *    зарегистрированные диалоги закрываются.
 *
 * **Singleton оправдан** — только один Sheets-браузер на app. Compose
 * CompositionLocal был бы излишним.
 */
object SheetsViewBridge {
    @Volatile var browser: SheetsBrowserController? = null

    /**
     * §TZ-0.10.9 — external splash trigger. App.kt после QR re-login
     * выставляет true → SheetsWorkspace observe'ит → показывает котик
     * поверх browser до того как тот закончит reload + наша CSS-маска
     * проинжектится. После пары секунд App.kt возвращает false.
     *
     * Используется только при явном external reload (login flow). Внутренние
     * file-switch / refresh — продолжают использовать workspace-local
     * `isReloading` flag.
     */
    val externalSplashOverlay: kotlinx.coroutines.flow.MutableStateFlow<Boolean> =
        kotlinx.coroutines.flow.MutableStateFlow(false)

    /** Список «закрой меня» callback'ов от открытых dialog'ов / fullscreen-viewer'ов.
     *  CopyOnWriteArrayList — thread-safe для CEF I/O thread + Compose main. */
    private val dismissCallbacks = CopyOnWriteArrayList<() -> Unit>()

    fun registerDismiss(callback: () -> Unit) {
        dismissCallbacks.add(callback)
    }

    fun unregisterDismiss(callback: () -> Unit) {
        dismissCallbacks.remove(callback)
    }

    /** Вызывается из MouseListener на Sheets canvas — закрывает все
     *  открытые overlay'и. */
    fun dismissAll() {
        // Snapshot чтобы избежать ConcurrentModification если callback
        // вызывает unregisterDismiss во время итерации.
        val snapshot = dismissCallbacks.toList()
        snapshot.forEach { runCatching { it() } }
    }

    /** Визуальный blur таблицы под модальными окнами приложения. */
    fun setBlur(blurred: Boolean) {
        val b = browser ?: return
        val js = if (blurred) {
            """
                (function() {
                    if (!document.body) return;
                    document.body.style.transition = 'filter 180ms ease-out';
                    document.body.style.filter = 'blur(8px) brightness(0.72)';
                    document.body.style.pointerEvents = 'none';
                    document.documentElement.style.pointerEvents = 'none';
                    document.body.setAttribute('data-otld-modal-blocked', 'true');
                })();
            """.trimIndent()
        } else {
            """
                (function() {
                    if (!document.body) return;
                    document.body.style.filter = '';
                    document.body.style.transition = 'filter 180ms ease-out';
                    document.body.style.pointerEvents = '';
                    document.documentElement.style.pointerEvents = '';
                    document.body.removeAttribute('data-otld-modal-blocked');
                })();
            """.trimIndent()
        }
        runCatching { b.evaluateJavaScript(js) }
    }

    fun openNativeGoogleMenu(menu: GoogleSheetsNativeMenu) {
        val b = browser ?: return
        val labels = menu.labels.joinToString(",") { jsString(it) }
        val ids = menu.elementIds.joinToString(",") { jsString(it) }
        val js = """
            (function() {
                try {
                    var labels = [$labels];
                    var ids = [$ids];
                    var candidates = [];
                    ids.forEach(function(id) {
                        var el = document.getElementById(id);
                        if (el) candidates.push(el);
                    });
                    document.querySelectorAll(
                        '#docs-menubar [role="menuitem"], #docs-menubars [role="menuitem"], ' +
                        '#docs-menubar .menu-button, #docs-menubars .menu-button, ' +
                        '#docs-menubar .docs-menu-button, #docs-menubars .docs-menu-button'
                    ).forEach(function(el) {
                        var text = (el.textContent || el.getAttribute('aria-label') || '').trim();
                        if (labels.indexOf(text) >= 0) candidates.push(el);
                    });
                    var target = candidates.find(function(el) {
                        var style = window.getComputedStyle(el);
                        return style.display !== 'none' && style.visibility !== 'hidden';
                    }) || candidates[0];
                    if (!target) {
                        console.warn('[OTLD] Google menu target not found: ${menu.title}');
                        return;
                    }
                    target.scrollIntoView({ block: 'nearest', inline: 'nearest' });
                    ['mousedown', 'mouseup', 'click'].forEach(function(type) {
                        target.dispatchEvent(new MouseEvent(type, {
                            bubbles: true,
                            cancelable: true,
                            view: window
                        }));
                    });
                } catch (e) {
                    console.error('[OTLD] Google menu open failed', e);
                }
            })();
        """.trimIndent()
        runCatching { b.evaluateJavaScript(js) }
    }

    fun switchNativeSheetTab(tab: SheetTab) {
        val b = browser ?: return
        val labels = listOf(tab.originalName, tab.label)
            .distinct()
            .joinToString(",") { jsString(it) }
        // §TZ-DESKTOP-NATIVE-2026-05 0.8.38 — REVERT к 0.8.4 simple text-match
        // плюс минимальный Win fallback. На Mac 0.8.4 эта логика работала
        // ИДЕАЛЬНО (юзер: «ранее на маке указанные листы открывались»).
        // Версии 0.8.25-0.8.37 добавляли data-id priority и popup expose
        // которые могли ломать что-то для Sheets event handler'а.
        //
        // Стратегия:
        //  1. Text-match в .docs-sheet-tab (0.8.4 path) — основной и надёжный
        //  2. 4 attempts × 180ms — даём Sheets bootstrap'у время
        //  3. Если не нашли — Win-only popup expose как failsafe
        //  4. postResult для Java loadUrl fallback (Win-only)
        val js = """
            (function() {
                try {
                    var targetGid = String(${tab.gid});
                    var labels = [$labels];
                    function normalize(value) {
                        // \u00a7TZ-DESKTOP-NATIVE-2026-05 0.8.56 \u2014 Unicode NFC + strip VS-16.
                        // Win Edge WebView2 \u0438 Mac WKWebView \u0432\u043e\u0437\u0432\u0440\u0430\u0449\u0430\u044e\u0442 textContent
                        // \u0441 \u0440\u0430\u0437\u043d\u043e\u0439 Unicode normalization \u0434\u043b\u044f non-ASCII \u0441\u0438\u043c\u0432\u043e\u043b\u043e\u0432:
                        //   \u2022 Cyrillic '\u0421\u042d\u0414' / '\u041e\u0422\u0427\u0415\u0422' \u043c\u043e\u0433\u0443\u0442 \u0431\u044b\u0442\u044c NFC vs NFD
                        //   \u2022 Emoji '\ud83d\ude9a' Edge \u0434\u043e\u0431\u0430\u0432\u043b\u044f\u0435\u0442 U+FE0F (variation selector
                        //     \u0434\u043b\u044f emoji presentation), WKWebView \u043d\u0435\u0442
                        //   \u2022 '\ud83d\udccaschedule' (emoji + ASCII) hit \u043e\u0431\u0430 issue
                        // \u0411\u0435\u0437 normalization exact === fail \u0434\u043b\u044f \u0412\u0421\u0415\u0425 non-ASCII rawName,
                        // findTarget \u0432\u043e\u0437\u0432\u0440\u0430\u0449\u0430\u0435\u0442 null \u2192 popup approach \u2192 :missing \u2192
                        // Java loadUrl fallback (cat splash). 100% \u043a\u043e\u0440\u0440\u0435\u043b\u044f\u0446\u0438\u044f \u0432
                        // native log: \u0432\u0441\u0435 :missing \u0431\u044b\u043b\u0438 \u043d\u0430 non-ASCII sheets.
                        // .normalize('NFC') \u0438\u0434\u0435\u043c\u043f\u043e\u0442\u0435\u043d\u0442\u043d\u0430 \u0434\u043b\u044f NFC \u0443\u0436\u0435-normalized
                        // \u0441\u0442\u0440\u043e\u043a, \u0431\u0435\u0437\u043e\u043f\u0430\u0441\u043d\u0430 \u043d\u0430 Mac.
                        return String(value || '')
                            .normalize('NFC')
                            .replace(/\ufe0f/g, '')
                            .replace(/\u00a0/g, ' ')
                            .replace(/^\s*0+/, '')
                            .replace(/\s+/g, ' ')
                            .trim();
                    }
                    function activeMatches() {
                        var active = document.querySelector('.docs-sheet-active-tab');
                        if (!active) return false;
                        var text = normalize(
                            (active.querySelector('.docs-sheet-tab-name') || active).textContent
                        );
                        return labels.some(function(label) { return normalize(label) === text; });
                    }
                    if (location.href.indexOf('gid=' + targetGid) >= 0 && activeMatches()) {
                        return;
                    }
                    function getElementText(el) {
                        // §TZ-DESKTOP-NATIVE-2026-05 0.8.57 — exhaustive text extraction
                        // для emoji/Cyrillic tabs где textContent может быть пустым
                        // (Sheets рендерит emoji через img.alt или font glyph).
                        // Проверяем все возможные источники: name child, aria-label,
                        // title, data-tooltip, alt атрибуты вложенных img.
                        var candidates = [];
                        try {
                            var nameEl = el.querySelector('.docs-sheet-tab-name');
                            if (nameEl) candidates.push(nameEl.textContent);
                            candidates.push(el.textContent);
                            candidates.push(el.getAttribute('aria-label'));
                            candidates.push(el.getAttribute('title'));
                            candidates.push(el.getAttribute('data-tooltip'));
                            candidates.push(el.getAttribute('data-tooltip-text'));
                            // emoji в alt атрибутах img/span
                            var imgs = el.querySelectorAll('img[alt], [data-name]');
                            for (var i = 0; i < imgs.length; i++) {
                                candidates.push(imgs[i].getAttribute('alt'));
                                candidates.push(imgs[i].getAttribute('data-name'));
                            }
                        } catch (_) {}
                        return candidates;
                    }
                    function elementMatchesLabels(el) {
                        var candidates = getElementText(el);
                        for (var i = 0; i < candidates.length; i++) {
                            var c = normalize(candidates[i] || '');
                            if (!c) continue;
                            for (var j = 0; j < labels.length; j++) {
                                var l = normalize(labels[j]);
                                if (!l) continue;
                                if (c === l) return true;
                                // Substring match — Sheets может добавить prefix/suffix
                                // (color marker, status badge, " • Hidden", etc.)
                                if (c.indexOf(l) >= 0 || l.indexOf(c) >= 0) return true;
                            }
                        }
                        return false;
                    }
                    function findTarget() {
                        // §TZ-DESKTOP-NATIVE-2026-05 0.8.57 — exhaustive search.
                        // Старая логика искала только textContent на .docs-sheet-tab-name
                        // → fail для emoji-only (💩, 🚚) и Cyrillic (СЭД, ОТЧЕТ) tabs где
                        // Sheets рендерит через img.alt или font glyph (textContent="").
                        // Теперь elementMatchesLabels проверяет 8+ возможных источников.
                        var allByGid = Array.prototype.slice.call(
                            document.querySelectorAll('[data-id="' + targetGid + '"]')
                        );
                        var verified = allByGid.find(function(el) {
                            return el.querySelector('.docs-sheet-tab-name') ||
                                   el.getAttribute('role') === 'tab' ||
                                   el.classList.contains('docs-sheet-tab');
                        });
                        if (verified) return verified;
                        var tabs = Array.prototype.slice.call(document.querySelectorAll(
                            '.docs-sheet-tab, [role="tab"], [aria-controls^="docs-sheet"]'
                        ));
                        var byMatch = tabs.find(elementMatchesLabels);
                        if (byMatch) return byMatch;
                        if (allByGid.length > 0) return allByGid[0];
                        return null;
                    }
                    function dumpDomDiagnostic() {
                        // §TZ-DESKTOP-NATIVE-2026-05 0.8.57 — diagnostic dump для
                        // tabs которые findTarget не находит. Postит JSON с
                        // attributes всех visible tabs → юзер пришлёт лог →
                        // знаем точно что Sheets рендерит для emoji/Cyrillic.
                        try {
                            var tabs = Array.prototype.slice.call(document.querySelectorAll(
                                '.docs-sheet-tab, [role="tab"]'
                            )).slice(0, 20);
                            var dump = tabs.map(function(el) {
                                var nameEl = el.querySelector('.docs-sheet-tab-name');
                                return {
                                    di: el.getAttribute('data-id'),
                                    al: el.getAttribute('aria-label'),
                                    ti: el.getAttribute('title'),
                                    dt: el.getAttribute('data-tooltip'),
                                    txt: ((nameEl ? nameEl.textContent : el.textContent) || '').substring(0, 30),
                                    cls: (el.className || '').substring(0, 80),
                                };
                            });
                            var json = JSON.stringify(dump);
                            if (window.chrome && window.chrome.webview) {
                                // Slice чтобы не overflow native buffer
                                window.chrome.webview.postMessage(
                                    'OTLD:DOM_DUMP:' + targetGid + ':' + json.substring(0, 1800)
                                );
                            }
                        } catch (_) {}
                    }
                    function scrollIntoTabBar(target) {
                        // §TZ-DESKTOP-NATIVE-2026-05 0.8.40 — если таб в DOM
                        // но прокручен вне viewport (узкое окно Win → часть
                        // listов scrolled в horizontal overflow), вызываем
                        // scrollIntoView. Это заставляет Sheets вернуть таб
                        // в видимую область → потом click работает.
                        try {
                            if (target && typeof target.scrollIntoView === 'function') {
                                target.scrollIntoView({ block: 'nearest', inline: 'nearest' });
                            }
                        } catch (_) {}
                    }
                    function clickTarget(target) {
                        // 0.8.4-style: dispatchEvent only. target.click() в 0.8.22+
                        // мог не работать с Sheets handler если element hidden.
                        //
                        // §TZ-DESKTOP-NATIVE-2026-05 0.8.55 — добавлены pointer
                        // events. Modern Sheets handler в Edge WebView2 может
                        // listen на pointer events приоритетно (Mac WKWebView
                        // менее selective). Pointer events dispatch'ат BEFORE
                        // mouse events в native browsers — мы эмулируем тот же
                        // порядок. Если handler не listen на pointer — игнорирует.
                        var rect = null;
                        try { rect = target.getBoundingClientRect(); } catch (_) {}
                        var cx = rect ? Math.round(rect.left + rect.width / 2) : 0;
                        var cy = rect ? Math.round(rect.top + rect.height / 2) : 0;
                        function dispatch(type) {
                            try {
                                var isPointer = type.indexOf('pointer') === 0;
                                var Cls = isPointer && window.PointerEvent ? PointerEvent : MouseEvent;
                                var init = {
                                    bubbles: true, cancelable: true, view: window,
                                    clientX: cx, clientY: cy, button: 0, buttons: isPointer && type !== 'pointerup' ? 1 : 0,
                                };
                                if (isPointer) {
                                    init.pointerType = 'mouse';
                                    init.pointerId = 1;
                                    init.isPrimary = true;
                                }
                                target.dispatchEvent(new Cls(type, init));
                            } catch (_) {}
                        }
                        dispatch('pointerover');
                        dispatch('mouseover');
                        dispatch('pointerdown');
                        dispatch('mousedown');
                        dispatch('pointerup');
                        dispatch('mouseup');
                        dispatch('click');
                    }
                    function postResult(status) {
                        try {
                            if (window.chrome && window.chrome.webview) {
                                window.chrome.webview.postMessage('OTLD:SHEET_SWITCH:' + targetGid + ':' + status);
                            }
                        } catch (e) {}
                    }
                    function findSheetListButton() {
                        return document.querySelector(
                            '#docs-sheet-list-popup-button, #docs-sheet-bar-list-button, ' +
                            '.docs-sheet-list-popup-button, .docs-sheet-list-button, .docs-sheet-button-list, ' +
                            '[aria-label="Все листы"], [aria-label="All sheets"]'
                        );
                    }
                    function normalizeNoEmoji(value) {
                        // §TZ-DESKTOP-NATIVE-2026-05 0.8.55 — strip emoji для
                        // robust text match. Emoji 🚚 на Edge может рендериться
                        // как surrogate pair vs. single codepoint vs. presentation
                        // selector — Mac WKWebView normalizes, Win Edge keeps raw.
                        // Без strip text comparison fail'ит для проблемных листов
                        // с emoji в названии.
                        return normalize(value).replace(/[\u{1F300}-\u{1FAFF}\u{2600}-\u{27BF}\u{FE0F}\u{200D}]/gu, '').trim();
                    }
                    function pickPopupItem() {
                        // §TZ-DESKTOP-NATIVE-2026-05 0.8.57 — exhaustive match
                        // через elementMatchesLabels (тот же что для main DOM).
                        var byDataId = document.querySelector(
                            '.goog-menuitem[data-id="' + targetGid + '"], ' +
                            '[role="menuitem"][data-id="' + targetGid + '"], ' +
                            '.docs-sheet-menu-item[data-id="' + targetGid + '"], ' +
                            '.docs-sheet-list-item[data-id="' + targetGid + '"], ' +
                            '[data-id="' + targetGid + '"][role]'
                        );
                        if (byDataId) return byDataId;
                        var menuItems = Array.prototype.slice.call(document.querySelectorAll(
                            '.goog-menuitem, [role="menuitem"], .docs-sheet-menu-item, ' +
                            '.docs-sheet-list-item, .docs-action-menu-item'
                        ));
                        return menuItems.find(elementMatchesLabels);
                    }
                    function pollPopupItem(attemptsLeft, callback) {
                        // §TZ-DESKTOP-NATIVE-2026-05 0.8.55 — polling вместо
                        // fixed setTimeout(280ms). Win Edge может рендерить
                        // popup items медленнее (особенно при первом open
                        // или при scroll'е через много sheets). 100ms × 12 =
                        // 1200ms maximum wait. Exit early как только item appears.
                        var item = pickPopupItem();
                        if (item) { callback(item); return; }
                        if (attemptsLeft <= 0) { callback(null); return; }
                        setTimeout(function() {
                            pollPopupItem(attemptsLeft - 1, callback);
                        }, 100);
                    }
                    function tryAllSheetsPopup() {
                        // Win-only fallback для overflow tabs — наша CSS маска
                        // прячет popup button (display:none на bottom tab-bar).
                        // Временно overrideем через injected style чтобы Sheets
                        // handler позиционировал menu по правильному rect.
                        var override = document.createElement('style');
                        override.id = 'otld-popup-expose';
                        override.textContent =
                            '.docs-sheet-tab-bar-container,' +
                            '.docs-sheet-tab-bar,' +
                            '#docs-sheet-bar,' +
                            '#docs-sheet-list-popup-button,' +
                            '#docs-sheet-bar-list-button,' +
                            '.docs-sheet-list-popup-button,' +
                            '.docs-sheet-list-button,' +
                            '.docs-sheet-button-list,' +
                            '[aria-label="Все листы"],' +
                            '[aria-label="All sheets"]' +
                            '{display:block!important;visibility:visible!important;' +
                            'opacity:0!important;pointer-events:auto!important;' +
                            'height:28px!important;width:auto!important;}';
                        (document.head || document.documentElement).appendChild(override);
                        requestAnimationFrame(function() {
                            requestAnimationFrame(function() {
                                var button = findSheetListButton();
                                if (!button) {
                                    override.remove();
                                    postResult('missing');
                                    return;
                                }
                                clickTarget(button);
                                // §TZ-DESKTOP-NATIVE-2026-05 0.8.55 — polling
                                // вместо fixed 280ms. Win Edge popup expansion
                                // занимает variable time.
                                pollPopupItem(12, function(item) {
                                    try {
                                        if (!item) {
                                            postResult('missing');
                                            try {
                                                document.dispatchEvent(new KeyboardEvent('keydown', {key:'Escape',keyCode:27,which:27,bubbles:true}));
                                            } catch (_) {}
                                            return;
                                        }
                                        clickTarget(item);
                                        // §TZ-DESKTOP-NATIVE-2026-05 0.8.55 —
                                        // verify navigation. Если за 600ms URL
                                        // не сменился на target gid, click был
                                        // ignored handler'ом → :missing → Java
                                        // loadUrl fallback (cat splash).
                                        var verifyDeadline = Date.now() + 600;
                                        function verifyNav() {
                                            if (location.href.indexOf('gid=' + targetGid) >= 0) {
                                                postResult('popup');
                                                return;
                                            }
                                            if (Date.now() > verifyDeadline) {
                                                postResult('missing');
                                                return;
                                            }
                                            setTimeout(verifyNav, 80);
                                        }
                                        verifyNav();
                                    } catch (e) {
                                        postResult('missing');
                                    } finally {
                                        setTimeout(function() { override.remove(); }, 100);
                                    }
                                });
                            });
                        });
                    }
                    function attempt(remaining) {
                        var target = findTarget();
                        if (target) {
                            // 0.8.40: scrollIntoView перед click чтобы таб был
                            // в viewport (Sheets handler может игнорировать
                            // клик по элементу за пределами scroll viewport).
                            scrollIntoTabBar(target);
                            // Дать 1 frame для scroll settle.
                            requestAnimationFrame(function() {
                                clickTarget(target);
                                postResult('direct');
                            });
                            return;
                        }
                        if (remaining <= 0) {
                            console.warn('[OTLD] tab not found via text match → popup fallback');
                            // §TZ-DESKTOP-NATIVE-2026-05 0.8.57 — diagnostic dump
                            // когда findTarget полностью fail. JSON со attributes
                            // visible tabs → юзер пришлёт лог → знаем что Sheets
                            // ставит для проблемных листов.
                            dumpDomDiagnostic();
                            tryAllSheetsPopup();
                            return;
                        }
                        setTimeout(function() { attempt(remaining - 1); }, 180);
                    }
                    // §TZ-DESKTOP-NATIVE-2026-05 0.8.40 — 8 attempts × 180ms = 1440ms.
                    // Win Edge bootstrap'ит Sheets медленнее WK; tabs могут
                    // лениво рендериться. Даём больше времени.
                    attempt(8);
                } catch (e) {
                    console.error('[OTLD] native sheet tab switch failed', e);
                }
            })();
        """.trimIndent()
        runCatching { b.evaluateJavaScript(js) }
    }

    private fun jsString(text: String): String {
        // §TZ-DESKTOP-NATIVE-2026-05 0.8.58 — escape ВСЕ non-ASCII как \uXXXX.
        //
        // Раньше передавали raw UTF-16 surrogate pairs (emoji 💩 → 💩
        // как 2 char'а в string). Path Kotlin String → JNA wide char → Edge
        // ICoreWebView2::ExecuteScript LPCWSTR — должен сохранять surrogate
        // pairs, но 0.8.57 DOM dump показал что text matching fail для всех
        // non-ASCII tabs (💩, 🚚, 📊schedule, СЭД, ОТЧЕТ) при том что
        // textContent в DOM dump содержит правильные строки. Гипотеза: где-то
        // в JNA→Edge marshalling surrogate pairs corrupted (особенно на
        // некоторых JVM/JNA versions Win может decompose surrogate в WTF-16).
        //
        // Fix: convert all non-ASCII (>= 0x80) chars to \uXXXX JS escape
        // sequences. JS engine parses \uXXXX as exact codepoint → guaranteed
        // identical bytes regardless of marshalling path. Mac тоже выиграет
        // (если у него тот же latent issue).
        val sb = StringBuilder("\"")
        for (ch in text) {
            when {
                ch == '\\' -> sb.append("\\\\")
                ch == '"' -> sb.append("\\\"")
                ch == '\n' -> sb.append("\\n")
                ch == '\r' -> { /* skip */ }
                ch.code < 0x20 -> sb.append("\\u%04x".format(ch.code))
                ch.code > 0x7E -> sb.append("\\u%04x".format(ch.code))
                else -> sb.append(ch)
            }
        }
        sb.append("\"")
        return sb.toString()
    }
}

enum class GoogleSheetsNativeMenu(
    val title: String,
    val labels: List<String>,
    val elementIds: List<String>,
) {
    EDIT(
        title = "Правка",
        labels = listOf("Правка", "Edit"),
        elementIds = listOf("docs-edit-menu"),
    ),
    INSERT(
        title = "Вставка",
        labels = listOf("Вставка", "Insert"),
        elementIds = listOf("docs-insert-menu"),
    ),
    DATA(
        title = "Данные",
        labels = listOf("Данные", "Data"),
        elementIds = listOf("docs-data-menu"),
    ),
}
