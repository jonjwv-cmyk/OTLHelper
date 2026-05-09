package com.example.otlhelper.desktop.sheets

/**
 * §TZ-DESKTOP 0.4.x — CSS/JS-маска Google Sheets.
 *
 * Цель: скрыть весь chrome Google Sheets (заголовок файла, share, аватар,
 * иконку Drive, нижние табы), оставив только редактирование (меню
 * Файл/Правка/Вставка/Данные, toolbar, formula bar, сетку). Свою nav-плашку
 * рендерим Compose-слоем сверху (см. `SheetsTopBar` / `SheetsTabStrip`).
 *
 * **Селекторы хрупкие.** Google A/B-тестирует UI и переименовывает классы
 * раз в 6-12 месяцев. Если что-то перестало прятаться:
 *   1. Включить DevTools: в [SheetsRuntime] `KCEF.init { builder { args =
 *      args + "--remote-debugging-port=9222" } }`. Открыть `chrome://inspect`
 *      из любого Chrome/Arc/Edge.
 *   2. В DevTools найти проблемный элемент → правый клик → Copy → Copy
 *      selector (или прочитать класс/id).
 *   3. Дописать селектор сюда. Желательно по `id` (стабильнее) или по
 *      `aria-label` (стабильнее чем класс). Класс — последний вариант.
 *
 * Инжект через `CefLoadHandler.onLoadEnd` после каждой загрузки страницы.
 *
 * **Стратегия hide:** широкие шаблоны + конкретные ID-fallback'и. Каждый
 * блок прокомментирован — не удалять без понимания зачем.
 */
object SheetsCss {

    /** CSS правила. Применяются через `<style>` тег вставляемый в `<head>`. */
    val CSS: String = """
        /* ──────────────────────────────────────────────────────
         * 1) Зелёная title-bar плашка целиком
         *    (имя файла, ⭐, cloud-status, share, аватар,
         *     история, 💬, 🎥 Meet)
         *
         *    Контейнер #docs-titlebar-container стабильный с 2018+,
         *    но если Google его переименует — есть backup .docs-chrome
         *    и .docs-titlebar-padding (родительский wrapper).
         * ────────────────────────────────────────────────────── */
        #docs-titlebar-container,
        .docs-titlebar-padding,
        .docs-chrome > div:first-child {
            display: none !important;
            height: 0 !important;
            min-height: 0 !important;
            max-height: 0 !important;
            overflow: hidden !important;
            visibility: hidden !important;
        }

        /* Google Workspace top-bar (`gb_*`). Эти классы Google
         * меняет часто, но `#gb` — стабильный root container. */
        #gb,
        #gbw,
        .gb_z, .gb_zd, .gb_dd, .gb_Sa {
            display: none !important;
        }

        /* §0.10.25 — hide bottom-right "collapse sheet tabs" arrow.
           Юзер видит свои tabs через наш TabStrip, нативный navigator
           Google не нужен. Селекторы: разные версии Sheets dom. */
        .docs-sheet-toggle-tabs-button,
        .docs-sheet-bar-toggle,
        .docs-sheet-bar-zoom-bar,
        .docs-sheet-tabs-overflow-toggle,
        button[aria-label*="Скрыть листы"],
        button[aria-label*="Hide sheet"],
        button[aria-label*="Show sheet"],
        button[aria-label*="Показать листы"] {
            display: none !important;
        }

        /* ──────────────────────────────────────────────────────
         * 2) Большая иконка Google Sheets в верхнем-левом углу
         *    (ведёт на Drive — отдельная страница со списком файлов
         *     юзера). Может находиться внутри #docs-titlebar-container
         *     (тогда уже скрыто rule 1) или отдельно как
         *     .docs-back-icon-link / .docs-titlebar-back-button.
         * ────────────────────────────────────────────────────── */
        .docs-titlebar-back-button-container,
        .docs-back-icon-link,
        .docs-sheet-icon-container,
        a.docs-titlebar-icon,
        a[aria-label*="Google Таблиц"],
        a[aria-label*="Google Sheets"],
        a[href$="/spreadsheets/u/0/"],
        a[href="https://docs.google.com/spreadsheets/u/0/"] {
            display: none !important;
        }

        /* ──────────────────────────────────────────────────────
         * 3) Меню — оставить ТОЛЬКО Правка / Вставка / Данные
         *
         *    Whitelist через JS (HIDE_MENUS_JS ниже) — CSS не умеет
         *    matching по textContent, нужен JS. Все остальные пункты
         *    меню (Файл/Вид/Формат/Инструменты/Расширения/Справка/
         *    custom-меню с эмодзи) — спрятаны JS'ом по тексту.
         *
         *    Также backup CSS-rules по ID/aria-label на случай если
         *    JS не сработает (race с Sheets bootstrap'ом).
         * ────────────────────────────────────────────────────── */
        #docs-file-menu,
        #docs-view-menu,
        #docs-format-menu,
        #docs-tools-menu,
        #docs-extensions-menu,
        #docs-help-menu,
        [id^="docs-custom-menu"],
        [id^="docs-extension-"],
        [role="menuitem"][aria-label="Файл"],
        [role="menuitem"][aria-label="Вид"],
        [role="menuitem"][aria-label="Формат"],
        [role="menuitem"][aria-label="Инструменты"],
        [role="menuitem"][aria-label="Расширения"],
        [role="menuitem"][aria-label="Справка"],
        [role="menuitem"][aria-label="File"],
        [role="menuitem"][aria-label="View"],
        [role="menuitem"][aria-label="Format"],
        [role="menuitem"][aria-label="Tools"],
        [role="menuitem"][aria-label="Extensions"],
        [role="menuitem"][aria-label="Help"] {
            display: none !important;
        }

        /* Toolbar collapse arrow / hide-menus chevron справа от меню.
         * §TZ-DESKTOP-NATIVE-2026-05 0.8.39 — Sheets DOM обновился между
         * April 30 (0.8.4 release) и May 2 (now). Старые селекторы могут
         * не покрывать новые aria-label варианты ("Скрыть строку меню",
         * "Hide menu bar", и т.д.). Расширяем покрытие через broader
         * substring patterns. JS-aggressive hide ниже подстрахует. */
        .docs-toolbar-collapse-button,
        #docs-toolbar-collapse-button,
        .docs-toolbar-hide-menubar,
        .docs-toolbar-hide-menus,
        .docs-toolbar-show-or-hide-bar-button,
        .docs-toolbar-show-bar-button,
        .docs-toolbar-hide-bar-button,
        [class*="toolbar-collapse"],
        [class*="toolbar-show-or-hide"],
        [class*="hide-menubar"],
        [class*="show-menubar"],
        [aria-label*="Свернуть меню"],
        [aria-label*="Развернуть меню"],
        [aria-label*="Скрыть меню"],
        [aria-label*="Показать меню"],
        [aria-label*="Скрыть строку меню"],
        [aria-label*="Показать строку меню"],
        [aria-label*="Hide menus"],
        [aria-label*="Show menus"],
        [aria-label*="Hide the menus"],
        [aria-label*="Show the menus"],
        [aria-label*="Hide menu bar"],
        [aria-label*="Show menu bar"],
        [aria-label*="Hide the menu"],
        [aria-label*="Show the menu"],
        [aria-label="Свернуть"],
        [aria-label="Развернуть"],
        [data-tooltip*="Свернуть меню"],
        [data-tooltip*="Развернуть меню"],
        [data-tooltip*="Скрыть меню"],
        [data-tooltip*="Показать меню"],
        [data-tooltip*="Hide menus"],
        [data-tooltip*="Show menus"],
        [data-tooltip*="Hide menu bar"],
        [data-tooltip*="Show menu bar"],
        button[aria-label*="меню"][aria-label*="скрыт"],
        button[aria-label*="меню"][aria-label*="показ"],
        button[aria-label*="Menu"][aria-label*="bar"] {
            display: none !important;
            width: 0 !important;
            visibility: hidden !important;
        }

        /* ──────────────────────────────────────────────────────
         * 4) Нижний tab-bar Sheets целиком + кнопки `+` / `☰`
         *
         *    Контейнер `#docs-sheet-bar` (или `#waffle-sheet-bar`)
         *    содержит: стрелки `< >`, сами tabs, кнопку `+`, кнопку `☰`.
         *    Теоретически hide контейнера прячет всё. Практика —
         *    кнопки `+` и `☰` могут быть в OTHER siblings того же
         *    parent'а (Google A/B варианты). Поэтому добавляем
         *    explicit-селекторы по ID и aria-label.
         * ────────────────────────────────────────────────────── */
        .docs-sheet-tab-bar-container,
        .docs-sheet-tab-bar,
        .waffle-sheet-tabbar,
        [class^="docs-sheet-tab"],
        [class*=" docs-sheet-tab"],
        [class^="waffle-sheet-tab"],
        [class*=" waffle-sheet-tab"],
        [role="tablist"][aria-label*="Sheet"],
        [role="tablist"][aria-label*="лист"],
        /* Кнопка `+` add-sheet (snake_case ID + camelCase класс варианты) */
        #docs-sheet-add-button,
        #docs-sheet-bar-add-tab-button,
        .docs-sheet-bar-add-tab-button,
        .docs-sheet-button-add,
        .docs-sheet-add-button,
        [aria-label="Добавить лист"],
        [aria-label="Add sheet"],
        [data-tooltip*="Добавить лист"],
        [data-tooltip*="Add sheet"],
        /* Кнопка `☰` all-sheets-list */
        #docs-sheet-list-popup-button,
        #docs-sheet-bar-list-button,
        .docs-sheet-list-popup-button,
        .docs-sheet-list-button,
        .docs-sheet-button-list,
        [aria-label="Все листы"],
        [aria-label="All sheets"],
        [aria-label*="Список листов"],
        [data-tooltip*="Все листы"],
        [data-tooltip*="All sheets"],
        /* Стрелки навигации между листами + scroll-стрелки tab-bar */
        [aria-label="Предыдущий лист"],
        [aria-label="Следующий лист"],
        [aria-label="Previous sheet"],
        [aria-label="Next sheet"],
        [aria-label*="Прокрутить"],
        [aria-label*="прокрутить"],
        [aria-label*="Scroll"],
        [aria-label*="scroll"],
        .docs-sheet-bar-prev-button,
        .docs-sheet-bar-next-button,
        .docs-sheet-bar-scroll-prev,
        .docs-sheet-bar-scroll-next,
        [class*="sheet-bar-scroll"],
        [class*="sheet-bar-prev"],
        [class*="sheet-bar-next"],
        /* Кнопки разворота/сворачивания нижнего pane */
        [aria-label*="Развернуть"],
        [aria-label*="Свернуть"],
        [aria-label*="Expand"],
        [aria-label*="Collapse"],
        /* Bottom pane полностью (status bar + filter result counters) — НЕ трогаем:
         * там живёт «Показано строк: N из M». Поэтому скрываем только навигацию
         * табов/кнопок листов, без общего [class*="sheet-bar"]. */
        [class*="sheet-bar-add"],
        [class*="sheet-bar-list"],
        [class*="sheet-tab"]:not(html):not(body) {
            display: none !important;
            height: 0 !important;
            visibility: hidden !important;
        }

        /* ──────────────────────────────────────────────────────
         * 4b) Right-side menu bar items — Comments/Apps Script/
         *     Side panel/Explore/Drive icon (всё что справа от
         *     меню Файл/Правка/Вставка/Данные)
         * ────────────────────────────────────────────────────── */
        .docs-collab-comments-tab,
        .docs-comments-button,
        #docs-comments-button,
        .docs-sidebar-button,
        .docs-explore-button,
        .docs-explore-data-button,
        .docs-script-button,
        [aria-label="Боковая панель"],
        [aria-label="Side panel"],
        [aria-label*="Откройте боковую панель"],
        [aria-label*="Open side panel"],
        [aria-label*="Apps Script"],
        [aria-label*="Расширения скриптов"],
        [aria-label="Explore"],
        [aria-label="Анализ данных"],
        [aria-label="История версий"],
        [aria-label="Версии"],
        [aria-label="Комментарии"],
        [aria-label="Comments"],
        [aria-label*="Drive"],
        [aria-label*="Диск"],
        /* Toolbar collapse arrow (^ at right edge of toolbar) */
        [aria-label="Свернуть"],
        [aria-label="Скрыть меню"],
        [aria-label="Hide menus"],
        .docs-toolbar-collapse-button,
        #docs-toolbar-collapse-button,
        /* §TZ-DESKTOP 0.4.x round 4 — right-edge sidebar (Sidekick) внутри Sheets:
         * узкая вертикальная панель справа от таблицы с иконками Calendar/
         * Keep/Tasks/Maps/Apps Script + arrow-toggle, который раскрывает
         * боковую панель. Прячем целиком. */
        .docs-tabbed-side-panel,
        .docs-tabbed-side-panel-container,
        .docs-tabbed-side-panel-button,
        .docs-tabbed-side-panel-tab-icon,
        .docs-tabbed-side-panel-tab,
        .docs-side-panel-tab,
        .docs-side-panel-button,
        .docs-toolbar-side-panel-button,
        .docs-sidekick,
        .docs-sidekick-container,
        .docs-sidekick-tab,
        [class*="side-panel-tab"],
        [class*="side-panel-button"],
        [class*="sidekick"],
        [class*="Sidekick"],
        [aria-label*="Боковая панель"],
        [aria-label*="боковая панель"],
        [aria-label*="Side panel"],
        [aria-label*="Show side panel"],
        [aria-label="Открыть боковую панель"],
        [aria-label*="Развернуть боковую"],
        [aria-label*="Скрыть боковую"],
        [aria-label*="Свернуть боковую"],
        [aria-label*="Hide side panel"],
        [aria-label*="Sidekick"],
        [data-tooltip*="Боковая панель"],
        [data-tooltip*="Side panel"],
        [data-tooltip*="Sidekick"] {
            display: none !important;
            width: 0 !important;
            visibility: hidden !important;
        }

        /* §TZ-DESKTOP 0.4.x round 4 — раскрытая боковая панель (если юзер
         * успел открыть до маски). И container справа который остаётся
         * после скрытия toggle. */
        .docs-side-panel,
        #docs-side-panel,
        .docs-companion-panel,
        [role="complementary"][aria-label*="anel"] {
            display: none !important;
            width: 0 !important;
        }

        /* ──────────────────────────────────────────────────────
         * 5) Косметика — убрать оставшийся top padding после
         *    скрытия title-bar + dark-bg + tightening layout
         *    (юзер: «там полно пустого пространства»)
         * ────────────────────────────────────────────────────── */
        html, body {
            margin: 0 !important;
            padding: 0 !important;
            background: #0E0E10 !important;  /* = BgApp в темной теме */
        }

        /* Чёткий tight layout: zero out padding/margin chrome wrappers
         * чтобы менюbar поднялся в самый верх. */
        .docs-titlebar-padding,
        [class*="titlebar-padding"],
        [class*="docs-chrome-padding"],
        .docs-chrome,
        #docs-chrome {
            padding-top: 0 !important;
            margin-top: 0 !important;
            min-height: 0 !important;
            height: auto !important;
            top: 0 !important;
        }

        #docs-editor,
        #docs-editor-container,
        .docs-editor-container {
            margin-top: 0 !important;
            padding-top: 0 !important;
        }

        /* Menu bar — ужать вертикальный padding, прижать к верху */
        #docs-menubar,
        .docs-menubar,
        #docs-menubars {
            padding-top: 0 !important;
            padding-bottom: 0 !important;
            margin-top: 0 !important;
            min-height: 28px !important;
            line-height: 1 !important;
        }

        /* Toolbar tight — без верхнего padding (располагается сразу под меню) */
        #docs-toolbar,
        .docs-toolbar,
        #docs-toolbar-wrapper {
            padding-top: 1px !important;
            margin-top: 0 !important;
            top: 0 !important;
        }
    """.trimIndent()

    /**
     * §TZ-DESKTOP 0.4.x — JS hide-by-text для меню которое не покрывается
     * ID/aria-label селекторами (custom меню с эмодзи в имени, например
     * `📊schedule` для WORKFLOW). CSS не умеет matching по textContent.
     */
    private val HIDE_MENUS_JS: String = """
        (function() {
            try {
                // Whitelist — оставляем ТОЛЬКО Правка/Вставка/Данные.
                // Юзер 2026-04-26 round 4: «правка/вставка/данные у нас
                // открываются в нашем кастомном пункте в строке».
                var keep = ['Правка', 'Вставка', 'Данные', 'Edit', 'Insert', 'Data'];
                var menus = document.querySelectorAll(
                    '#docs-menubar > div, #docs-menubars > div, ' +
                    '#docs-menubar [role="menuitem"], #docs-menubars [role="menuitem"], ' +
                    '#docs-menubar .menu-button, #docs-menubars .menu-button, ' +
                    '#docs-menubar .docs-menu-button, #docs-menubars .docs-menu-button'
                );
                menus.forEach(function(el) {
                    var text = (el.textContent || '').trim();
                    if (!text) return;
                    if (keep.indexOf(text) >= 0) return;
                    el.style.setProperty('display', 'none', 'important');
                });
            } catch (e) {
                console.error('[OTLD] menu hide failed', e);
            }
        })();
    """.trimIndent()

    /**
     * JS обёртка которая инжектит CSS + JS hide-by-text + снимает pre-mask.
     *
     * §TZ-DESKTOP 0.4.x round 4 — порядок:
     *   1. Inject CSS (прячет chrome Sheets).
     *   2. JS hide-by-text прячет custom-меню с эмодзи.
     *   3. body visibility:visible — раскрываем экран только после mask.
     *   4. Retries (800ms, 2000ms) — Sheets bootstrap'ит DOM async.
     */
    val INJECT_JS: String = """
        (function() {
            try {
                var existing = document.getElementById('otld-sheets-mask');
                if (existing) existing.remove();
                var style = document.createElement('style');
                style.id = 'otld-sheets-mask';
                style.type = 'text/css';
                style.appendChild(document.createTextNode(${jsString(CSS)}));
                (document.head || document.documentElement).appendChild(style);
                if (document.body) document.body.style.visibility = 'hidden';
                window.__otldSheetsReady = false;
                console.log('[OTLD] Sheets mask injected');
                var attempts = 0;
                function isReady() {
                    attempts += 1;
                    var maskReady = !!document.getElementById('otld-sheets-mask');
                    var gridReady = !!(
                        document.querySelector('#docs-editor') &&
                        (
                            document.querySelector('#waffle-grid-container') ||
                            document.querySelector('.waffle-grid-container') ||
                            document.querySelector('.grid-container') ||
                            document.querySelector('canvas')
                        )
                    );
                    return maskReady && (gridReady || attempts > 45);
                }
                function compactChrome() {
                    try {
                        var nodes = document.querySelectorAll(
                            '#docs-titlebar-container, .docs-titlebar-padding, #gb, #gbw, ' +
                            '.docs-chrome > div:first-child'
                        );
                        nodes.forEach(function(el) {
                            el.style.setProperty('display', 'none', 'important');
                            el.style.setProperty('height', '0', 'important');
                            el.style.setProperty('min-height', '0', 'important');
                            el.style.setProperty('max-height', '0', 'important');
                            el.style.setProperty('overflow', 'hidden', 'important');
                            el.style.setProperty('visibility', 'hidden', 'important');
                        });
                        var chrome = document.querySelector('#docs-chrome, .docs-chrome');
                        if (chrome) {
                            chrome.style.setProperty('padding-top', '0', 'important');
                            chrome.style.setProperty('min-height', '0', 'important');
                            chrome.style.setProperty('top', '0', 'important');
                        }
                        var toolbar = document.querySelector('#docs-toolbar-wrapper, #docs-toolbar, .docs-toolbar');
                        if (toolbar) {
                            toolbar.style.setProperty('padding-top', '1px', 'important');
                            toolbar.style.setProperty('margin-top', '0', 'important');
                            toolbar.style.setProperty('top', '0', 'important');
                        }
                        if (chrome && toolbar) {
                            var cursor = toolbar;
                            while (cursor && cursor !== chrome && cursor.parentElement) {
                                var parent = cursor.parentElement;
                                Array.prototype.slice.call(parent.children).forEach(function(child) {
                                    if (child === cursor || child.contains(cursor)) return;
                                    var rect = child.getBoundingClientRect();
                                    var toolbarTop = toolbar.getBoundingClientRect().top || 0;
                                    if (rect.height > 8 && rect.bottom <= toolbarTop + 2) {
                                        child.style.setProperty('display', 'none', 'important');
                                        child.style.setProperty('height', '0', 'important');
                                        child.style.setProperty('min-height', '0', 'important');
                                        child.style.setProperty('max-height', '0', 'important');
                                        child.style.setProperty('overflow', 'hidden', 'important');
                                    }
                                });
                                parent.style.setProperty('padding-top', '0', 'important');
                                parent.style.setProperty('margin-top', '0', 'important');
                                cursor = parent;
                            }
                            chrome.style.setProperty('margin-top', '0', 'important');
                        }
                        // §TZ-DESKTOP-NATIVE-2026-05 0.8.40 — JS-aggressive
                        // hide arrow toggle. Расширено vs 0.8.39:
                        //  - Включает title attribute (Edge fallback)
                        //  - Class name patterns
                        //  - Любой element (не только button) внутри toolbar
                        // Юзер: «стрелка осталась в тулбаре» на Win хотя
                        // на Mac пропала. Edge может использовать другие
                        // class names или icon-only buttons без aria-label.
                        try {
                            // Stage 1: keyword match по aria-label/data-tooltip/title
                            var allInteractive = document.querySelectorAll(
                                'button, [role="button"], [role="menuitem"]'
                            );
                            allInteractive.forEach(function(btn) {
                                var label = ((btn.getAttribute('aria-label') || '') + ' ' +
                                            (btn.getAttribute('data-tooltip') || '') + ' ' +
                                            (btn.getAttribute('title') || '')).toLowerCase();
                                if (!label) return;
                                var isMenuRelated = label.indexOf('меню') >= 0 ||
                                                    label.indexOf('menu') >= 0 ||
                                                    label.indexOf('бар') >= 0 ||
                                                    label.indexOf('bar') >= 0 ||
                                                    label.indexOf('строку') >= 0;
                                var isToggleAction = label.indexOf('скры') >= 0 ||
                                                     label.indexOf('пока') >= 0 ||
                                                     label.indexOf('свер') >= 0 ||
                                                     label.indexOf('развер') >= 0 ||
                                                     label.indexOf('hide') >= 0 ||
                                                     label.indexOf('show') >= 0 ||
                                                     label.indexOf('collap') >= 0 ||
                                                     label.indexOf('expand') >= 0 ||
                                                     label.indexOf('toggle') >= 0;
                                if (isMenuRelated && isToggleAction) {
                                    btn.style.setProperty('display', 'none', 'important');
                                    btn.style.setProperty('width', '0', 'important');
                                    btn.style.setProperty('visibility', 'hidden', 'important');
                                }
                            });
                            // Stage 2: class name patterns (для icon-only кнопок
                            // которые могут не иметь aria-label на Edge).
                            var classCandidates = document.querySelectorAll(
                                '[class*="menubar-toggle"], [class*="hide-menubar"], ' +
                                '[class*="show-menubar"], [class*="bar-button"][class*="collapse"], ' +
                                '[class*="bar-button"][class*="hide"], [class*="bar-button"][class*="show"], ' +
                                '[class*="show-or-hide"], [class*="toggle-menubar"]'
                            );
                            classCandidates.forEach(function(el) {
                                el.style.setProperty('display', 'none', 'important');
                                el.style.setProperty('width', '0', 'important');
                                el.style.setProperty('visibility', 'hidden', 'important');
                            });
                            // Stage 3: внутри #docs-toolbar найти кнопку справа
                            // которая выглядит как arrow toggle (positional).
                            // Edge может рендерить иконку-стрелку без какого-либо
                            // aria-label/data-tooltip — тогда только positional.
                            var toolbar2 = document.querySelector('#docs-toolbar, #docs-toolbar-wrapper');
                            if (toolbar2) {
                                var rightButtons = toolbar2.querySelectorAll(
                                    'button:last-child, [role="button"]:last-child'
                                );
                                rightButtons.forEach(function(btn) {
                                    // Hide ТОЛЬКО если button не имеет видимого
                                    // текста (icon-only) И находится в правом краю.
                                    var text = (btn.textContent || '').trim();
                                    var rect = btn.getBoundingClientRect();
                                    var toolbarRect = toolbar2.getBoundingClientRect();
                                    var isIconOnly = text.length <= 2;
                                    var isFarRight = rect.right >= toolbarRect.right - 60;
                                    if (isIconOnly && isFarRight) {
                                        btn.style.setProperty('display', 'none', 'important');
                                        btn.style.setProperty('width', '0', 'important');
                                    }
                                });
                            }
                        } catch (_) {}
                        try { window.dispatchEvent(new Event('resize')); } catch (_) {}
                    } catch (e) {
                        console.error('[OTLD] compact chrome failed', e);
                    }
                }
                function revealWhenReady() {
                    try {
                        compactChrome();
                        ${HIDE_MENUS_JS}
                        if (isReady()) {
                            compactChrome();
                            if (document.body) document.body.style.visibility = 'visible';
                            if (document.documentElement) document.documentElement.style.visibility = 'visible';
                            window.__otldSheetsReady = true;
                            console.info('__OTLD_SHEETS_READY__');
                            return;
                        }
                        setTimeout(revealWhenReady, 120);
                    } catch (e) {
                        if (document.body) document.body.style.visibility = 'visible';
                    }
                }
                requestAnimationFrame(function() {
                    requestAnimationFrame(revealWhenReady);
                });
                setTimeout(compactChrome, 500);
                setTimeout(compactChrome, 1200);
                setTimeout(compactChrome, 2400);
            } catch (e) {
                console.error('[OTLD] mask inject failed', e);
                if (document.body) document.body.style.visibility = 'visible';
                window.__otldSheetsReady = true;
                console.info('__OTLD_SHEETS_READY__');
            }
        })();
        ${HIDE_MENUS_JS}
        setTimeout(function() { ${HIDE_MENUS_JS} }, 800);
        setTimeout(function() { ${HIDE_MENUS_JS} }, 2000);
        setTimeout(function() { try { window.dispatchEvent(new Event('resize')); } catch(e) {} }, 250);
        setTimeout(function() { try { window.dispatchEvent(new Event('resize')); } catch(e) {} }, 900);
        setTimeout(function() { try { window.dispatchEvent(new Event('resize')); } catch(e) {} }, 1800);
    """.trimIndent()

    private fun jsString(text: String): String =
        "\"" + text
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "") + "\""
}
