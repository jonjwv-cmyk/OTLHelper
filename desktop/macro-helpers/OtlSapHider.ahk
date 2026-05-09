; §TZ-DESKTOP-0.10.22 — SAP window hider helper.
;
; Запускается VBS макросом (wf_plan_macro.vbs) через cscript →
; sh.Run async с одним аргументом: путь к targets file.
;
; Targets file содержит список HWND (по одному на строку) тех окон
; SAP, которые нужно скрыть. VBS пишет в этот файл HWND именно нашей
; macro-session (которую сам создаёт через CreateNewSapSession), то
; есть ТОЛЬКО созданные нашим скриптом окна. Существующие сессии юзера
; (например ME23N в которой он работает) НЕ попадают в этот список и
; не трогаются.
;
; Lifecycle:
;   - VBS создаёт targets file (изначально пустой) и spawn'ит этот EXE
;   - На каждом WaitForId VBS обновляет targets file (новые popup'ы)
;   - Helper читает targets file каждые 200ms, делает WinHide на каждом
;   - VBS перед `WScript.Quit 0` удаляет targets file → helper exit'ит
;   - Fail-safe: timeout 15 минут даже если targets file не удалён
;     (на случай если макрос упал)
;
; Скомпилирован через Ahk2Exe в CI workflow (.github/workflows/
; release-desktop.yml) с AHK runtime внутри (~1.5 MB standalone EXE).
; Bundled в Win-сборку OTL Helper через Compose Desktop appResources
; (`desktop/app-resources/windows/helpers/OtlSapHider.exe`).

#SingleInstance Force
#NoTrayIcon
DetectHiddenWindows true

targetsFile := A_Args.Length >= 1 ? A_Args[1] : ""
if (targetsFile = "")
    ExitApp

debugLog := "C:\Users\Public\otl-sap-hider-debug.log"

LogDbg(msg) {
    global debugLog
    try FileAppend "[" FormatTime(, "yyyy-MM-dd HH:mm:ss.fff") "] " msg "`n", debugLog
}

LogDbg("hider start, targetsFile=" targetsFile)

deadline := A_TickCount + 15 * 60 * 1000  ; 15 минут fail-safe
tickCounter := 0
lastHandlesCount := -1

Loop {
    if (A_TickCount > deadline) {
        LogDbg("deadline reached, exit")
        ExitApp
    }
    if (!FileExist(targetsFile)) {
        LogDbg("targets file gone, exit")
        ExitApp
    }

    try {
        text := FileRead(targetsFile)
        ; §0.10.23 BUG FIX — Loop Parse вместо `for line in StrSplit()`.
        ; В AHK v2 `for v in array` даёт INDEX в v, не value → WinHide
        ; вызывался с ahk_id 1, 2, 3... (несуществующие HWND).
        ; Loop Parse корректно итерирует строки в A_LoopField.
        handlesProcessed := 0
        Loop Parse text, "`n", "`r" {
            line := Trim(A_LoopField)
            if (line != "") {
                try {
                    WinHide("ahk_id " . line)
                    handlesProcessed += 1
                }
            }
        }
        ; Лог только при изменении count чтобы не спамить
        if (handlesProcessed != lastHandlesCount) {
            LogDbg("targets=" handlesProcessed)
            lastHandlesCount := handlesProcessed
        }
    } catch as err {
        LogDbg("loop error: " err.Message)
    }
    Sleep(200)
}
