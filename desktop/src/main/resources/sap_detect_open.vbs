Option Explicit

' ============================================================
' OTL — SAP detect open documents
' ============================================================
' Запускается из OTLHelper при Ctrl+Q. Проходит по всем
' открытым SAP connection'ам и sessions, собирает информацию о
' каждом окне:
'   • transaction code (Info.Transaction)
'   • номер документа (из window title или screen field)
'   • тип (ORDER / DELIVERY / OTHER)
'   • полный заголовок окна
'   • session ID
'
' Результат пишется в файл (путь через env var OTL_SAP_DETECT_OUT)
' в формате JSON.
'
' Exit codes:
'   0 — OK (даже если найдено 0 документов)
'   2 — SAP не запущен / scripting не активен
' ============================================================

Dim sh, fso, outputPath, ts, json
Dim SapGuiAuto, application, conn, sess, info
Dim cIdx, sIdx, cCount, sCount
Dim txnCode, titleText, docNum, docType
Dim first

Set sh = CreateObject("WScript.Shell")
Set fso = CreateObject("Scripting.FileSystemObject")

outputPath = sh.ExpandEnvironmentStrings("%OTL_SAP_DETECT_OUT%")
If outputPath = "%OTL_SAP_DETECT_OUT%" Or Len(outputPath) = 0 Then
    outputPath = sh.ExpandEnvironmentStrings("%TEMP%") & "\otl_sap_detect.json"
End If

' ── Connect to SAP ──
On Error Resume Next
Set SapGuiAuto = GetObject("SAPGUI")
If Err.Number <> 0 Or SapGuiAuto Is Nothing Then
    WriteJson outputPath, "{""ok"":false,""error"":""sap_not_running"",""sessions"":[]}"
    WScript.Quit 2
End If
Set application = SapGuiAuto.GetScriptingEngine
If Err.Number <> 0 Or application Is Nothing Then
    WriteJson outputPath, "{""ok"":false,""error"":""scripting_disabled"",""sessions"":[]}"
    WScript.Quit 2
End If
Err.Clear
On Error GoTo 0

' ── Iterate connections + sessions ──
json = "{""ok"":true,""sessions"":["
first = True

cCount = application.Children.Count
For cIdx = 0 To cCount - 1
    On Error Resume Next
    Set conn = application.Children(cIdx)
    If Err.Number = 0 And Not (conn Is Nothing) Then
        sCount = conn.Children.Count
        For sIdx = 0 To sCount - 1
            Set sess = conn.Children(sIdx)
            If Err.Number = 0 And Not (sess Is Nothing) Then
                Set info = sess.Info
                txnCode = ""
                titleText = ""
                docNum = ""
                docType = "OTHER"

                If Not (info Is Nothing) Then
                    txnCode = SafeStr(info.Transaction)
                End If

                titleText = SafeStr(sess.findById("wnd[0]").Text)

                ' Determine docType + try to extract docNum from screen
                If StartsWith(txnCode, "VL0") Or StartsWith(txnCode, "VL3") Then
                    docType = "DELIVERY"
                    docNum = SafeStr(sess.findById("wnd[0]/usr/ctxtLIKP-VBELN").Text)
                ElseIf StartsWith(txnCode, "ME2") Or StartsWith(txnCode, "ME3") Or StartsWith(txnCode, "ME9") Then
                    docType = "ORDER"
                    ' Title обычно содержит номер — extract via regex
                    docNum = ExtractNumberFromTitle(titleText)
                End If

                ' Если из screen не получили — try title как fallback
                If Len(docNum) = 0 Then
                    docNum = ExtractNumberFromTitle(titleText)
                End If

                If Not first Then json = json & ","
                first = False
                json = json & "{"
                json = json & """sessId"":""" & JsonEscape(SafeStr(sess.Id)) & ""","
                json = json & """txn"":""" & JsonEscape(txnCode) & ""","
                json = json & """docNum"":""" & JsonEscape(docNum) & ""","
                json = json & """docType"":""" & JsonEscape(docType) & ""","
                json = json & """title"":""" & JsonEscape(titleText) & """"
                json = json & "}"
            End If
            Err.Clear
        Next
    End If
    Err.Clear
Next
On Error GoTo 0

json = json & "]}"

WriteJson outputPath, json
WScript.Quit 0


' ── Helpers ─────────────────────────────────────────────────

Sub WriteJson(path, txt)
    Dim t
    On Error Resume Next
    Set t = fso.CreateTextFile(path, True, True)  ' overwrite, unicode
    t.Write txt
    t.Close
    Err.Clear
    On Error GoTo 0
End Sub

Function SafeStr(v)
    On Error Resume Next
    SafeStr = CStr(v)
    If Err.Number <> 0 Then SafeStr = ""
    Err.Clear
    On Error GoTo 0
End Function

Function StartsWith(s, prefix)
    StartsWith = (Len(s) >= Len(prefix) And Left(s, Len(prefix)) = prefix)
End Function

' Извлечь длинный (8+ цифр) номер из текста заголовка окна.
' Например "Stand. Purchase Order 4400896820 - Display" → "4400896820"
Function ExtractNumberFromTitle(text)
    Dim regex, matches
    ExtractNumberFromTitle = ""
    On Error Resume Next
    Set regex = New RegExp
    regex.Pattern = "\d{7,12}"
    regex.Global = False
    Set matches = regex.Execute(text)
    If matches.Count > 0 Then
        ExtractNumberFromTitle = matches.Item(0).Value
    End If
    Err.Clear
    On Error GoTo 0
End Function

Function JsonEscape(s)
    Dim r
    r = s
    r = Replace(r, "\", "\\")
    r = Replace(r, """", "\""")
    r = Replace(r, vbCrLf, "\n")
    r = Replace(r, vbCr, "\n")
    r = Replace(r, vbLf, "\n")
    r = Replace(r, vbTab, "\t")
    JsonEscape = r
End Function
