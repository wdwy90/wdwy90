#Requires AutoHotkey v2.0
#SingleInstance Force

; Type It In
; Copy text on this PC, click into the virtual desktop window, press the
; hotkey, and the clipboard is typed into that window as keystrokes.
; One direction only (this PC -> virtual desktop). No network access.

; ===== Settings =====
TYPE_HOTKEY  := "$^!+v"     ; Ctrl+Alt+Shift+V ($ = grab it before other apps see it)
ABORT_KEY    := "Esc"       ; stops typing mid-way
KEY_DELAY_MS := 10          ; pause between keys; raise to 20-40 if characters drop
KEY_PRESS_MS := 10          ; how long each key is held down
CONFIRM_OVER := 1000        ; ask before typing more than this many characters
ASCII_ONLY   := true        ; swap smart quotes, long dashes, etc. for plain ASCII
NEWLINE_SEND := "{Enter}"   ; use "+{Enter}" for chat apps where Enter sends
TAB_SEND     := "{Tab}"     ; use "    " to type spaces instead of Tab

global typing := false
global aborted := false

Hotkey TYPE_HOTKEY, TypeClipboard
Hotkey ABORT_KEY, Abort, "Off"

A_IconTip := "Type It In - " HotkeyName(TYPE_HOTKEY) " types the clipboard"
TrayTip HotkeyName(TYPE_HOTKEY) " types the clipboard into the active window.`nEsc stops it.", "Type It In"

TypeClipboard(*) {
    global typing, aborted
    if typing
        return

    text := A_Clipboard
    if (text = "") {
        TrayTip "The clipboard has no text to type.", "Type It In"
        return
    }

    ; Let go of the hotkey first, or Ctrl/Alt would combine with typed keys.
    for key in ["Ctrl", "Alt", "Shift", "LWin", "RWin", HotkeyKey(TYPE_HOTKEY)]
        KeyWait key

    target := WinExist("A")

    text := StrReplace(text, "`r`n", "`n")
    text := StrReplace(text, "`r", "`n")
    if ASCII_ONLY
        text := ToAscii(text)

    len := StrLen(text)
    if (len > CONFIRM_OVER) {
        secs := Round(len * (KEY_DELAY_MS + KEY_PRESS_MS) / 1000)
        msg := "Type " len " characters? This takes about " secs " seconds.`n`n"
             . "Don't touch the keyboard or mouse while it types. Press Esc to stop."
        if (MsgBox(msg, "Type It In", "YesNo Icon?") != "Yes")
            return
        WinActivate target
        if !WinWaitActive(target, , 2) {
            TrayTip "Couldn't return to the target window. Nothing was typed.", "Type It In"
            return
        }
        Sleep 300
    }

    SetKeyDelay KEY_DELAY_MS, KEY_PRESS_MS
    typing := true
    aborted := false
    Hotkey ABORT_KEY, "On"
    typed := 0
    reason := ""
    try {
        Loop Parse text {
            if aborted {
                reason := "Stopped (Esc)"
                break
            }
            ; Never type into a window the user didn't pick.
            if !WinActive(target) {
                reason := "Stopped - window lost focus"
                break
            }
            ch := A_LoopField
            if (ch = "`n")
                SendEvent NEWLINE_SEND
            else if (ch = "`t")
                SendEvent TAB_SEND
            else
                SendEvent "{Text}" ch
            typed++
        }
    } finally {
        Hotkey ABORT_KEY, "Off"
        typing := false
    }

    if (reason != "")
        TrayTip reason " after " typed " of " len " characters.", "Type It In"
}

Abort(*) {
    global aborted := true
}

; "$^!+v" -> "v"
HotkeyKey(hk) {
    return RegExReplace(hk, "^[$*~<>^!+#]+")
}

; "$^!+v" -> "Ctrl+Alt+Shift+V"
HotkeyName(hk) {
    mods := RegExReplace(hk, "[^^!+#]")
    name := ""
    for pair in [["^", "Ctrl+"], ["!", "Alt+"], ["+", "Shift+"], ["#", "Win+"]]
        if InStr(mods, pair[1])
            name .= pair[2]
    return name . StrUpper(HotkeyKey(hk))
}

ToAscii(s) {
    static repl := Map(
        Chr(0x2018), "'",   Chr(0x2019), "'",   Chr(0x201A), "'",
        Chr(0x201C), '"',   Chr(0x201D), '"',   Chr(0x201E), '"',
        Chr(0x2013), "-",   Chr(0x2014), "-",   Chr(0x2212), "-",
        Chr(0x2026), "...", Chr(0x2022), "*",   Chr(0x00A0), " ",
        Chr(0x200B), "",    Chr(0xFEFF), ""
    )
    for from, to in repl
        s := StrReplace(s, from, to)
    return s
}
