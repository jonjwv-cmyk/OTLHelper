Option Explicit

' ============================================================
' OTL — SAP detect open documents (v2 §0.11.11)
' ============================================================
' Перепиcан после 0.11.10 — старая версия возвращала sessions:[]
' хотя SAP открыт. Причина: итерация по `application.Children`
' в новом cscript-instance не видела connections (lazy-load в COM
' instance? scripting permission scope?). Фикс:
'   1. Используем ActiveSession как primary entry — она работает
'      даже если Children пустые в новом instance.
'   2. Через ActiveSession.Parent получаем Connection, потом её
'      Children = sessions.
'   3. Также fallback итерируем Connections collection.
'   4. Err.Clear после каждого suspect-call (раньше Err копился
'      между sessions и блокировал последующие).
'   5. RegWrite WarnOnAttach=0 на всякий случай (хотя юзер сказал
'      окно подтверждения не появляется).
'
' Output: JSON file (path в env var OTL_SAP_DETECT_OUT).
' Exit codes: 0 OK, 2 SAP не запущен / scripting не активен.
' ============================================================

Dim sh, fso, outputPath, json, debugMsgs

Set sh = CreateObject("WScript.Shell")
Set fso = CreateObject("Scripting.FileSystemObject")

outputPath = sh.ExpandEnvironmentStrings("%OTL_SAP_DETECT_OUT%")
If outputPath = "%OTL_SAP_DETECT_OUT%" Or Len(outputPath) = 0 Then
    outputPath = sh.ExpandEnvironmentStrings("%TEMP%") & "\otl_sap_detect.json"
End If

debugMsgs = ""

' ── Suppress SAP scripting popup (на случай) ──
On Error Resume Next
sh.RegWrite "HKCU\Software\SAP\SAPGUI Front\SAP Frontend Server\Security\WarnOnAttach", 0, "REG_DWORD"
sh.RegWrite "HKCU\Software\SAP\SAPGUI Front\SAP Frontend Server\Security\WarnOnConnection", 0, "REG_DWORD"
Err.Clear
On Error GoTo 0

' ── Connect to SAP ──
Dim SapGuiAuto, application, activeSess
On Error Resume Next
Set SapGuiAuto = GetObject("SAPGUI")
If Err.Number <> 0 Or SapGuiAuto Is Nothing Then
    WriteJson outputPath, "{""ok"":false,""error"":""sap_not_running"",""sessions"":[],""debug"":""GetObject failed: " & JsonEscape(SafeStr(Err.Description)) & """}"
    WScript.Quit 2
End If
Err.Clear
Set application = SapGuiAuto.GetScriptingEngine
If Err.Number <> 0 Or application Is Nothing Then
    WriteJson outputPath, "{""ok"":false,""error"":""scripting_disabled"",""sessions"":[],""debug"":""GetScriptingEngine failed: " & JsonEscape(SafeStr(Err.Description)) & """}"
    WScript.Quit 2
End If
Err.Clear
On Error GoTo 0

' ── Build session list ──
Dim seenSessions
Set seenSessions = CreateObject("Scripting.Dictionary")

' Strategy A: ActiveSession + walk siblings via Parent (Connection).
' Это работает даже если application.Children в COM-instance lazy-empty.
On Error Resume Next
Set activeSess = application.ActiveSession
If Err.Number = 0 And Not (activeSess Is Nothing) Then
    debugMsgs = debugMsgs & "active_session_ok|"
    Dim parentConn
    Set parentConn = activeSess.Parent
    If Err.Number = 0 And Not (parentConn Is Nothing) Then
        debugMsgs = debugMsgs & "parent_conn_ok|"
        AddSessionsFromConnection parentConn, seenSessions
    End If
End If
Err.Clear
On Error GoTo 0

' Strategy B: пройтись по application.Connections (alias for Children).
On Error Resume Next
Dim connsCount, ci, conn
connsCount = 0
connsCount = application.Connections.Count
debugMsgs = debugMsgs & "conns_count=" & connsCount & "|"
If Err.Number = 0 And connsCount > 0 Then
    For ci = 0 To connsCount - 1
        Err.Clear
        Set conn = application.Connections.ElementAt(ci)
        If Err.Number = 0 And Not (conn Is Nothing) Then
            AddSessionsFromConnection conn, seenSessions
        End If
        Err.Clear
    Next
End If
Err.Clear
On Error GoTo 0

' Strategy C: legacy через application.Children (на случай если
' предыдущие не сработали — fallback).
On Error Resume Next
Dim childrenCount
childrenCount = 0
childrenCount = application.Children.Count
debugMsgs = debugMsgs & "children_count=" & childrenCount & "|"
If Err.Number = 0 And childrenCount > 0 Then
    For ci = 0 To childrenCount - 1
        Err.Clear
        Set conn = application.Children(ci)
        If Err.Number = 0 And Not (conn Is Nothing) Then
            AddSessionsFromConnection conn, seenSessions
        End If
        Err.Clear
    Next
End If
Err.Clear
On Error GoTo 0

' ── Build JSON output ──
json = "{""ok"":true,""sessions"":["
Dim keys, k, first
keys = seenSessions.Keys
first = True
For Each k In keys
    If Not first Then json = json & ","
    first = False
    json = json & seenSessions.Item(k)
Next
json = json & "],""debug"":""" & JsonEscape(debugMsgs) & """}"

WriteJson outputPath, json
WScript.Quit 0


' ────────────────────────────────────────────────────────────
' AddSessionsFromConnection: enumerate sessions of a connection,
' add to seenSessions dict (key = session.Id) as JSON object string.
' ────────────────────────────────────────────────────────────
Sub AddSessionsFromConnection(conn, dict)
    Dim sCount, si, sess, sessId
    On Error Resume Next
    sCount = 0
    sCount = conn.Children.Count
    Err.Clear
    If sCount <= 0 Then Exit Sub

    For si = 0 To sCount - 1
        Err.Clear
        Set sess = conn.Children(si)
        If Err.Number = 0 And Not (sess Is Nothing) Then
            sessId = SafeStr(sess.Id)
            If Len(sessId) > 0 And Not dict.Exists(sessId) Then
                dict.Add sessId, BuildSessionJson(sess, sessId)
            End If
        End If
        Err.Clear
    Next
    On Error GoTo 0
End Sub

' Builds JSON object string for one session.
Function BuildSessionJson(sess, sessId)
    Dim info, txnCode, titleText, docNum, docType, j

    On Error Resume Next
    Set info = sess.Info
    Err.Clear

    txnCode = ""
    If Not (info Is Nothing) Then
        txnCode = SafeStr(info.Transaction)
    End If
    Err.Clear

    titleText = ""
    titleText = SafeStr(sess.findById("wnd[0]").Text)
    Err.Clear

    docType = "OTHER"
    docNum = ""

    If StartsWith(txnCode, "VL0") Or StartsWith(txnCode, "VL3") Then
        docType = "DELIVERY"
        ' Try screen field
        docNum = SafeStr(sess.findById("wnd[0]/usr/ctxtLIKP-VBELN").Text)
        Err.Clear
    ElseIf StartsWith(txnCode, "ME2") Or StartsWith(txnCode, "ME3") Or StartsWith(txnCode, "ME9") Then
        docType = "ORDER"
    End If

    ' If still empty — try title regex (works for many transactions)
    If Len(docNum) = 0 Then
        docNum = ExtractNumberFromTitle(titleText)
    End If

    On Error GoTo 0

    j = "{"
    j = j & """sessId"":""" & JsonEscape(sessId) & ""","
    j = j & """txn"":""" & JsonEscape(txnCode) & ""","
    j = j & """docNum"":""" & JsonEscape(docNum) & ""","
    j = j & """docType"":""" & JsonEscape(docType) & ""","
    j = j & """title"":""" & JsonEscape(titleText) & """"
    j = j & "}"
    BuildSessionJson = j
End Function


' ────────────────────────────────────────────────────────────
' Helpers
' ────────────────────────────────────────────────────────────

Sub WriteJson(path, txt)
    Dim t
    On Error Resume Next
    Set t = fso.CreateTextFile(path, True, True)
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
