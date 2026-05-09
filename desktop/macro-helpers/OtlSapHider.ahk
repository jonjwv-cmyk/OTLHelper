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

deadline := A_TickCount + 15 * 60 * 1000  ; 15 минут fail-safe

Loop {
    if (A_TickCount > deadline)
        ExitApp
    if (!FileExist(targetsFile))
        ExitApp

    try {
        text := FileRead(targetsFile)
        for line in StrSplit(text, "`n", "`r") {
            line := Trim(line)
            if (line != "")
                try WinHide("ahk_id " . line)
        }
    }
    Sleep(200)
}
