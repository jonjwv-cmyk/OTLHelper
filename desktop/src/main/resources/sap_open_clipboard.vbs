Option Explicit

' ============================================================
' OTL — SAP open from clipboard (bundled VBS)
' ============================================================
' Запускается из OTLHelper при triple Ctrl+C. Номер и тип target'а
' приходят через env vars:
'   OTL_SAP_NUMBER — номер заказа/поставки (только цифры)
'   OTL_SAP_TYPE   — "ORDER" (ME23N) или "DELIVERY" (VL02N)
'
' Open всегда в новой SAP сессии (createSession), чтобы не
' прерывать текущую работу юзера.
'
' Exit codes:
'   0  — OK
'   1  — env vars missing/invalid
'   2  — SAP не запущен / scripting не активен
'   3  — Не удалось создать новую SAP session (timeout)
'   4  — SAP scripting error (ID controls не найден)
' ============================================================

Dim sh, num, sapType, isOrder
Dim SapGuiAuto, application, connection, session, baseCount, t0

Set sh = CreateObject("WScript.Shell")
num = sh.ExpandEnvironmentStrings("%OTL_SAP_NUMBER%")
sapType = sh.ExpandEnvironmentStrings("%OTL_SAP_TYPE%")

If num = "%OTL_SAP_NUMBER%" Or Len(num) = 0 Then WScript.Quit 1
If sapType = "%OTL_SAP_TYPE%" Or Len(sapType) = 0 Then WScript.Quit 1

isOrder = (UCase(Trim(sapType)) = "ORDER")

' ── Connect to SAP ──
On Error Resume Next
Set SapGuiAuto = GetObject("SAPGUI")
If Err.Number <> 0 Or SapGuiAuto Is Nothing Then WScript.Quit 2
Set application = SapGuiAuto.GetScriptingEngine
If Err.Number <> 0 Or application Is Nothing Then WScript.Quit 2
Set connection = application.Children(0)
If Err.Number <> 0 Or connection Is Nothing Then WScript.Quit 2
Err.Clear
On Error GoTo 0

' ── Create new session ──
baseCount = connection.Children.Count
Set session = connection.Children(0)

On Error Resume Next
session.createSession
Err.Clear
On Error GoTo 0

t0 = Timer
Do While connection.Children.Count <= baseCount
    WScript.Sleep 100
    If Timer - t0 > 10 Then WScript.Quit 3
Loop

Set session = connection.Children(connection.Children.Count - 1)

' ── Run transaction ──
On Error Resume Next
If isOrder Then
    ' ME23N — точная запись юзера
    session.findById("wnd[0]").maximize
    session.findById("wnd[0]/tbar[0]/okcd").text = "ME23N"
    session.findById("wnd[0]").sendVKey 0
    session.findById("wnd[0]/tbar[1]/btn[17]").press
    session.findById("wnd[1]/usr/subSUB0:SAPLMEGUI:0003/ctxtMEPO_SELECT-EBELN").text = num
    session.findById("wnd[1]/tbar[0]/btn[0]").press
    session.findById("wnd[0]/tbar[1]/btn[7]").press
Else
    ' VL02N — точная запись юзера
    session.findById("wnd[0]").maximize
    session.findById("wnd[0]/tbar[0]/okcd").text = "VL02N"
    session.findById("wnd[0]").sendVKey 0
    session.findById("wnd[0]/usr/ctxtLIKP-VBELN").text = num
    session.findById("wnd[0]/usr/ctxtLIKP-VBELN").caretPosition = Len(num)
    session.findById("wnd[0]").sendVKey 0
    session.findById("wnd[0]/tbar[1]/btn[25]").press
End If

If Err.Number <> 0 Then
    Err.Clear
    On Error GoTo 0
    WScript.Quit 4
End If
Err.Clear
On Error GoTo 0

WScript.Quit 0
