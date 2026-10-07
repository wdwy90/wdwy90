"""Drives the car app on the emulator: UI dumps, taps by text, screenshots. Review only."""
import os, re, subprocess, sys, time, xml.etree.ElementTree as ET

OUT = os.environ.get("OUT", "shots")
os.makedirs(OUT, exist_ok=True)
log = open(os.path.join(OUT, "drive.log"), "a")

def sh(*args, check=False, text=True):
    r = subprocess.run(["adb", *args], capture_output=True, text=text)
    if check and r.returncode:
        raise RuntimeError(f"adb {' '.join(args)}: {r.stderr}")
    return r.stdout if text else r.stdout

def note(msg):
    print(msg); log.write(msg + "\n"); log.flush()

def dump(name=None):
    sh("shell", "uiautomator", "dump", "/sdcard/ui.xml")
    xml = sh("shell", "cat", "/sdcard/ui.xml")
    if name:
        open(os.path.join(OUT, name + ".xml"), "w").write(xml)
    return xml

def nodes(xml):
    try:
        root = ET.fromstring(xml[xml.find("<?xml"):] if "<?xml" in xml else xml)
    except ET.ParseError:
        return []
    out = []
    for n in root.iter("node"):
        b = re.findall(r"\d+", n.get("bounds", ""))
        if len(b) == 4:
            out.append((n.get("text", ""), n.get("content-desc", ""), tuple(map(int, b)), n.get("clickable") == "true"))
    return out

def texts(xml):
    return [t or d for t, d, _, _ in nodes(xml) if (t or d)]

def tap_text(label, exact=False, wait=4):
    for attempt in range(3):
        xml = dump()
        for t, d, (x1, y1, x2, y2), _ in nodes(xml):
            for s in (t, d):
                if s and ((s == label) if exact else (label.lower() in s.lower())):
                    sh("shell", "input", "tap", str((x1 + x2) // 2), str((y1 + y2) // 2))
                    note(f"tap '{label}' -> '{s}' at {(x1 + x2) // 2},{(y1 + y2) // 2}")
                    time.sleep(wait)
                    return True
        time.sleep(2)
    note(f"NOT FOUND '{label}'; on screen: {texts(dump())[:40]}")
    return False

def shot(name):
    png = subprocess.run(["adb", "exec-out", "screencap", "-p"], capture_output=True).stdout
    at = png.find(b"\x89PNG")  # screencap may print a multi-display warning first
    open(os.path.join(OUT, name + ".png"), "wb").write(png[at:] if at >= 0 else png)
    note(f"shot {name}")

def drive(cmd, arg="", wait=11):
    """Sends a command to the debug-only DebugDriver in the car app. The templates host delays updates
    that come faster than about one per 10 s ("too many refreshes in a short span of time"), so each
    command waits long enough for its screen to be drawn before the next one."""
    sh("shell", "am", "broadcast", "-a", "com.wdwy90.pullupmenu.DRIVE", "-p", "com.wdwy90.pullupmenu",
       "--es", "cmd", cmd, "--es", "arg", "'" + arg.replace("'", "'\\''") + "'")
    note(f"drive {cmd} {arg!r}")
    time.sleep(wait)

def back(wait=3):
    sh("shell", "input", "keyevent", "KEYCODE_BACK")
    time.sleep(wait)

if __name__ == "__main__":
    cmd = sys.argv[1]
    if cmd == "shot":
        shot(sys.argv[2])
    elif cmd == "tap":
        sys.exit(0 if tap_text(sys.argv[2]) else 1)
    elif cmd == "back":
        back()
    elif cmd == "drive":
        drive(sys.argv[2], sys.argv[3] if len(sys.argv) > 3 else "", float(sys.argv[4]) if len(sys.argv) > 4 else 11)
