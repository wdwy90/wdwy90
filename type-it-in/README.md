# Type It In

Types your home PC's clipboard into the virtual desktop window as keystrokes. It works even when clipboard sharing is turned off.

- **One direction only:** home PC → virtual desktop. Nothing comes back out.
- **No network, no install on the work side.** It runs only on your home PC.

> Check your company's acceptable-use policy before using it. It sends only keystrokes, but it still works around a clipboard restriction IT may have set on purpose.

## Setup (Windows, about 5 minutes)

1. Install **AutoHotkey v2** from https://www.autohotkey.com (choose v2, not v1.1).
2. Download `TypeItIn.ahk` to your home PC, for example into `Documents`.
3. Double-click `TypeItIn.ahk`. A green **H** icon appears in the system tray.
4. Optional, to start it with Windows: press `Win+R`, type `shell:startup`, and put a shortcut to `TypeItIn.ahk` in that folder.

## Use

1. Copy text on your home PC as usual (`Ctrl+C`).
2. Click into the spot in the virtual desktop where the text should go.
3. Press **Ctrl+Alt+Shift+V**.
4. Keep your hands off the keyboard and mouse until it finishes. Press **Esc** to stop.

Typing stops on its own if you switch to another window. For more than 1,000 characters it asks first and shows how long typing will take (about 50 characters per second).

## Settings

Edit the top of `TypeItIn.ahk` in Notepad, save, then right-click the tray icon → **Reload Script**.

| Setting | Default | When to change it |
|---|---|---|
| `TYPE_HOTKEY` | `$^!+v` (Ctrl+Alt+Shift+V) | If it clashes with another app. `^` = Ctrl, `!` = Alt, `+` = Shift, `#` = Win. Keep the leading `$` |
| `KEY_DELAY_MS` | `10` | Raise to 20–40 if characters are dropped or out of order |
| `KEY_PRESS_MS` | `10` | Raise to 20–30 together with the delay if keys still drop |
| `CONFIRM_OVER` | `1000` | Character count that triggers the "are you sure" prompt |
| `ASCII_ONLY` | `true` | Swaps smart quotes, long dashes, bullets and non-breaking spaces for plain characters, which virtual desktops often drop or mangle |
| `NEWLINE_SEND` | `{Enter}` | Use `+{Enter}` (Shift+Enter) when typing into Teams or other chat apps, so each line doesn't send as a separate message |
| `TAB_SEND` | `{Tab}` | Use `"    "` if Tab jumps between fields instead of indenting |

## Troubleshooting

| Problem | Fix |
|---|---|
| Hotkey does nothing in the virtual desktop | Some clients grab all keys in full-screen mode. Run the virtual desktop windowed, or turn off "send Windows key combinations to the remote session" in the client. If that doesn't help, right-click `TypeItIn.ahk` → **Run as administrator**. |
| Characters missing or doubled | Raise `KEY_DELAY_MS` and `KEY_PRESS_MS`. |
| Wrong characters (`@` shows as `"`, etc.) | The keyboard layout differs between your PC and the virtual desktop. Set both to the same layout, such as US English. |
| Extra indentation in code editors | The editor auto-indents each new line. Paste into Notepad in the virtual desktop first, or turn off auto-indent. |
| Emoji or accented letters don't come through | Virtual desktop clients often can't type them. Stick to plain text. |

## Limits

- Text only: no images, files or formatting.
- Typing large amounts is slow. For long documents, use an approved channel such as work email or OneDrive.
