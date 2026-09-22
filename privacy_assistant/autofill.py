"""Browser automation: fill and submit opt-out forms, and open confirmation links.

Uses Playwright with a visible browser and a persistent profile, so cookies carry
over between runs and the email-confirmation step happens in the same session as
the form (several brokers require that). Captchas and bot checks are never bypassed:
the assistant pauses and you solve them in the window.
"""

from __future__ import annotations

import os
import re
from typing import Callable

from . import core

Pause = Callable[[str], str]

CAPTCHA_SELECTOR = ", ".join([
    "iframe[src*='recaptcha']", "iframe[src*='hcaptcha']", "iframe[src*='challenges.cloudflare.com']",
    ".g-recaptcha", ".h-captcha", ".cf-turnstile",
])
CHALLENGE_TITLES = re.compile(r"just a moment|attention required|are you a human|verify you are human", re.I)
SKIP_TYPES = {"hidden", "submit", "button", "checkbox", "radio", "password", "file", "image", "reset", "search"}
CONSENT_WORDS = re.compile(r"agree|terms|certify|consent|acknowledge|i am the|my own", re.I)
SUBMIT_WORDS = re.compile(r"remov|opt.?out|submit|continue|delete|suppress|send|request|confirm|verify", re.I)

AUTOCOMPLETE_MAP = {
    "email": "email", "tel": "phone", "given-name": "first_name", "family-name": "last_name",
    "additional-name": "middle_name", "name": "full_name", "postal-code": "zip",
    "address-level2": "city", "address-level1": "state", "street-address": "street",
    "address-line1": "street", "url": "listing_url",
}
# Order matters: earlier rules win.
DESCRIPTOR_RULES = [
    ("email", re.compile(r"e[- ]?mail")),
    ("listing_url", re.compile(r"\b(url|link|profile|record)\b")),
    ("phone", re.compile(r"phone|mobile|\btel\b")),
    ("first_name", re.compile(r"first|given|fname")),
    ("last_name", re.compile(r"last|surname|family|lname")),
    ("middle_name", re.compile(r"middle")),
    ("zip", re.compile(r"\bzip|postal")),
    ("city", re.compile(r"\bcity\b")),
    ("state", re.compile(r"\bstate\b|province")),
    ("street", re.compile(r"street|address")),
    ("birth_year", re.compile(r"birth|\bdob\b")),
    ("full_name", re.compile(r"\bname\b|full.?name")),
]


def classify_field(descriptor: str, input_type: str = "text", autocomplete: str = "") -> str | None:
    """Map a form field to a profile value key, or None to leave it alone."""
    input_type = (input_type or "text").lower()
    if input_type in SKIP_TYPES:
        return None
    if input_type == "email":
        return "email"
    if input_type == "url":
        return "listing_url"
    if input_type == "tel":
        return "phone"
    ac = (autocomplete or "").lower().split()[-1:] or [""]
    if ac[0] in AUTOCOMPLETE_MAP:
        return AUTOCOMPLETE_MAP[ac[0]]
    d = descriptor.lower().replace("_", " ").replace("-", " ")
    for key, rx in DESCRIPTOR_RULES:
        if rx.search(d):
            return key
    return None


def profile_values(profile: core.Profile, listing_url: str | None) -> dict[str, str]:
    addr = profile.primary_address
    return {
        "email": profile.emails[0] if profile.emails else "",
        "phone": profile.phones[0] if profile.phones else "",
        "first_name": profile.first_name,
        "last_name": profile.last_name,
        "middle_name": profile.middle_name,
        "full_name": f"{profile.first_name} {profile.last_name}",
        "street": addr.street,
        "city": addr.city,
        "state": addr.state.upper(),
        "zip": addr.zip,
        "birth_year": profile.birth_year,
        "listing_url": listing_url or "",
    }


# ---------------------------------------------------------------- page helpers

def has_captcha(page) -> bool:
    try:
        return page.locator(CAPTCHA_SELECTOR).count() > 0 or bool(CHALLENGE_TITLES.search(page.title()))
    except Exception:  # noqa: BLE001 - page may be mid-navigation
        return False


def _descriptor(el) -> str:
    return el.evaluate(
        """e => {
            const labels = e.labels ? Array.from(e.labels).map(l => l.innerText) : [];
            return [e.name, e.id, e.placeholder, e.getAttribute('aria-label'), ...labels].join(' ');
        }"""
    )


def _is_site_search(el) -> bool:
    return el.evaluate(
        "e => e.getAttribute('role') === 'searchbox' || e.name === 'q' || "
        "(e.getAttribute('aria-label') || '').trim().toLowerCase() === 'search'"
    )


def _fields(scope):
    return scope.locator("input:visible, textarea:visible, select:visible")


def _plan(scope) -> list[tuple[object, str]]:
    plan = []
    for i in range(_fields(scope).count()):
        el = _fields(scope).nth(i)
        if _is_site_search(el):
            continue
        key = classify_field(_descriptor(el), el.get_attribute("type") or "text",
                             el.get_attribute("autocomplete") or "")
        if key:
            plan.append((el, key))
    return plan


def _pick_scope(page, form_selector: str | None):
    """Choose the form most likely to be the opt-out form (not the site's header search)."""
    if form_selector:
        return page.locator(form_selector).first
    forms = page.locator("form")
    best, best_n = page.locator("body"), 0
    for i in range(forms.count()):
        n = len(_plan(forms.nth(i)))
        if n > best_n:
            best, best_n = forms.nth(i), n
    return best


def fill_form(page, values: dict[str, str], form_selector: str | None = None):
    """Fill recognizable fields. Returns (scope, list of filled keys)."""
    scope = _pick_scope(page, form_selector)
    filled = []
    for el, key in _plan(scope):
        value = values.get(key, "")
        if not value:
            continue
        tag = el.evaluate("e => e.tagName.toLowerCase()")
        try:
            if tag == "select":
                full = core.US_STATES.get(value, value).replace("-", " ")
                for attempt in ({"value": value}, {"label": full}, {"label": value}):
                    try:
                        el.select_option(**attempt, timeout=1000)
                        break
                    except Exception:  # noqa: BLE001 - try the next way of matching the option
                        continue
                else:
                    continue
            elif el.input_value():
                continue
            else:
                el.fill(value)
            filled.append(key)
        except Exception:  # noqa: BLE001 - a field we can't fill is left for the user
            continue
    boxes = scope.locator("input[type=checkbox]:visible")
    for i in range(boxes.count()):
        box = boxes.nth(i)
        if CONSENT_WORDS.search(_descriptor(box)) and not box.is_checked():
            box.check()
            filled.append("consent")
    return scope, filled


def click_submit(scope) -> bool:
    buttons = scope.locator("button[type=submit]:visible, input[type=submit]:visible")
    if buttons.count() == 0:
        buttons = scope.locator("button:visible").filter(has_text=SUBMIT_WORDS)
    if buttons.count() == 0:
        return False
    buttons.first.click()
    return True


def wait_for_human_check(page, pause: Pause) -> None:
    if has_captcha(page):
        pause("A captcha / bot check is showing. Solve it in the browser window, then press Enter")


# ---------------------------------------------------------------- flows

def launch(headless: bool = False):
    """Start Playwright with a persistent browser profile. Returns (playwright, context)."""
    try:
        from playwright.sync_api import sync_playwright
    except ImportError:
        raise SystemExit("Browser automation needs Playwright:\n"
                         "  pip install playwright && playwright install chromium")
    pw = sync_playwright().start()
    kwargs = {"headless": headless, "viewport": {"width": 1280, "height": 900}}
    if os.environ.get("PA_CHROMIUM_PATH"):
        kwargs["executable_path"] = os.environ["PA_CHROMIUM_PATH"]
    context = pw.chromium.launch_persistent_context(str(core.data_dir() / "browser-profile"), **kwargs)
    return pw, context


def submit_optout(context, broker: dict, profile: core.Profile, listing_url: str | None,
                  pause: Pause, auto_submit: bool = True) -> dict:
    """Open a broker's opt-out page, fill it, and submit it when no captcha blocks us."""
    page = context.new_page()
    page.goto(broker["optout_url"], wait_until="domcontentloaded", timeout=60000)
    wait_for_human_check(page, pause)
    auto = broker.get("autofill", {})
    scope, filled = fill_form(page, profile_values(profile, listing_url), auto.get("form"))
    submitted = False
    if auto_submit and filled and not has_captcha(page):
        submitted = click_submit(scope)
        if submitted:
            try:
                page.wait_for_load_state("domcontentloaded", timeout=15000)
            except Exception:  # noqa: BLE001 - single-page forms don't navigate
                pass
    return {"page": page, "filled": filled, "submitted": submitted, "captcha": has_captcha(page)}


def browser_visitor(context) -> Callable[[str], "tuple[bool, str]"]:
    """Open confirmation links in the same browser session and press an obvious confirm button."""
    def visit(url: str) -> tuple[bool, str]:
        page = context.new_page()
        try:
            resp = page.goto(url, wait_until="domcontentloaded", timeout=60000)
            try:
                page.wait_for_load_state("networkidle", timeout=10000)
            except Exception:  # noqa: BLE001 - some pages never go idle
                pass
            confirm = page.locator("button:visible, input[type=submit]:visible").filter(
                has_text=re.compile(r"^\s*(confirm|verify|yes|remove|continue)", re.I))
            if confirm.count() == 1 and not has_captcha(page):
                confirm.first.click()
                page.wait_for_load_state("domcontentloaded", timeout=15000)
            status = resp.status if resp else 0
            ok = status < 400 and not has_captcha(page)
            detail = f"HTTP {status} {page.url}" + (" (captcha shown — finish in browser)" if has_captcha(page) else "")
            return ok, detail
        except Exception as e:  # noqa: BLE001 - report failure, keep going with other brokers
            return False, str(e)
    return visit
