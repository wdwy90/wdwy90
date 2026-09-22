"""Find broker verification emails in your inbox and open their confirmation links."""

from __future__ import annotations

import email
import imaplib
import os
import re
import urllib.request
from datetime import timedelta
from email.message import EmailMessage
from email.policy import default as default_policy
from email.utils import parseaddr
from html.parser import HTMLParser
from typing import Callable, Iterable, Iterator
from urllib.parse import urlparse

from . import core

LINK_WORDS = re.compile(r"confirm|verif|opt.?out|remov|suppress|activate|validat|token|delete", re.I)
SKIP_WORDS = re.compile(
    r"unsubscribe|privacy.?policy|terms|preferences|facebook|twitter|instagram|linkedin|youtube|"
    r"\.(png|jpe?g|gif|svg)\b",
    re.I,
)
STRONG_WORDS = re.compile(r"confirm|verif|activate|validat", re.I)
# Email service providers that wrap links for click tracking. Only trusted when the
# sender itself is the broker.
TRACKING_HOSTS = ("sendgrid.net", "mailgun.org", "list-manage.com", "mandrillapp.com",
                  "sparkpostmail.com", "amazonses.com", "mailchi.mp", "rs6.net", "mcsv.net")

Visitor = Callable[[str], "tuple[bool, str]"]


# ---------------------------------------------------------------- matching

def _bare_host(url_or_host: str) -> str:
    host = urlparse(url_or_host).hostname if "//" in url_or_host else url_or_host
    host = (host or "").lower()
    return host[4:] if host.startswith("www.") else host


def broker_domains(broker: dict) -> set[str]:
    domains = {_bare_host(broker["optout_url"])}
    if broker.get("search_url"):
        domains.add(_bare_host(broker["search_url"]))
    domains.update(d.lower() for d in broker.get("domains", []))
    # also accept the registrable domain so mail from e.g. notify.spokeo.com matches
    domains.update(".".join(d.split(".")[-2:]) for d in list(domains) if d.count(".") >= 2)
    return {d for d in domains if d}


def host_matches(host: str, domains: Iterable[str]) -> bool:
    host = _bare_host(host)
    return any(host == d or host.endswith("." + d) for d in domains)


class _LinkParser(HTMLParser):
    def __init__(self):
        super().__init__()
        self.links: list[tuple[str, str]] = []
        self._href: str | None = None
        self._text: list[str] = []

    def handle_starttag(self, tag, attrs):
        if tag == "a":
            self._href = dict(attrs).get("href")
            self._text = []

    def handle_data(self, data):
        if self._href is not None:
            self._text.append(data)

    def handle_endtag(self, tag):
        if tag == "a" and self._href:
            self.links.append((self._href.strip(), " ".join("".join(self._text).split())))
            self._href = None


def extract_links(msg: EmailMessage) -> list[tuple[str, str]]:
    """Return (url, anchor text) pairs from every text part of the message."""
    links: list[tuple[str, str]] = []
    for part in msg.walk():
        ctype = part.get_content_type()
        if ctype not in ("text/html", "text/plain"):
            continue
        try:
            body = part.get_content()
        except (LookupError, UnicodeDecodeError):
            continue
        if ctype == "text/html":
            parser = _LinkParser()
            parser.feed(body)
            links.extend(parser.links)
        else:
            links.extend((u.rstrip(".,)>\"'"), "") for u in re.findall(r"https?://\S+", body))
    seen, out = set(), []
    for url, text in links:
        if url.startswith("http") and url not in seen:
            seen.add(url)
            out.append((url, text))
    return out


def match_broker(msg: EmailMessage, brokers: list[dict]) -> dict | None:
    sender = parseaddr(msg.get("From", ""))[1]
    sender_host = sender.rpartition("@")[2]
    for b in brokers:
        if sender_host and host_matches(sender_host, broker_domains(b)):
            return b
    return None


def confirmation_link(msg: EmailMessage, broker: dict) -> str | None:
    """Pick the verification link in a broker email. Only links pointing at the broker's own
    domains (or its click-tracking provider) are considered, so a spoofed or unrelated
    email can't make us open an arbitrary site."""
    domains = broker_domains(broker)
    best: tuple[int, str] | None = None
    for url, text in extract_links(msg):
        host = urlparse(url).hostname or ""
        if not (host_matches(host, domains) or host_matches(host, TRACKING_HOSTS)):
            continue
        haystack = f"{url} {text}"
        if SKIP_WORDS.search(haystack) or not LINK_WORDS.search(haystack):
            continue
        score = (2 if STRONG_WORDS.search(text) else 0) + (1 if STRONG_WORDS.search(url) else 0)
        if best is None or score > best[0]:
            best = (score, url)
    return best[1] if best else None


# ---------------------------------------------------------------- mailbox

class ImapMailbox:
    """Reads recent mail over IMAP. Works with Gmail (app password), Outlook, iCloud, etc.

    Checks spam folders too, since broker verification emails often land there."""

    def __init__(self, host: str, user: str, password: str, folders: list[str] | None = None):
        self.host, self.user, self.password = host, user, password
        self.folders = folders or ["INBOX", "[Gmail]/Spam", "Junk", "Spam", "Junk Email"]

    @classmethod
    def from_env(cls) -> "ImapMailbox":
        user = os.environ.get("PA_IMAP_USER") or os.environ.get("PA_SMTP_USER")
        password = os.environ.get("PA_IMAP_PASSWORD") or os.environ.get("PA_SMTP_PASSWORD")
        if not (user and password):
            raise SystemExit("Set PA_IMAP_USER and PA_IMAP_PASSWORD (or the PA_SMTP_* equivalents) "
                             "so the assistant can read verification emails.")
        folders = os.environ.get("PA_IMAP_FOLDERS")
        return cls(os.environ.get("PA_IMAP_HOST", "imap.gmail.com"), user, password,
                   folders.split(",") if folders else None)

    def messages_since(self, days: int, from_domains: Iterable[str] | None = None) -> Iterator[EmailMessage]:
        """Yield messages received in the last `days` days, optionally only from these domains."""
        since = (core.now() - timedelta(days=days)).strftime("%d-%b-%Y")
        queries = [["SINCE", since, "FROM", f'"{d}"'] for d in sorted(set(from_domains))] \
            if from_domains else [["SINCE", since]]
        with imaplib.IMAP4_SSL(self.host) as imap:
            imap.login(self.user, self.password)
            for folder in self.folders:
                try:
                    status, _ = imap.select(f'"{folder}"', readonly=True)
                except imaplib.IMAP4.error:
                    continue
                if status != "OK":
                    continue
                nums: set[bytes] = set()
                for q in queries:
                    _, data = imap.search(None, *q)
                    nums.update(data[0].split())
                for num in sorted(nums, key=int):
                    _, parts = imap.fetch(num, "(BODY.PEEK[])")
                    raw = next((p[1] for p in parts if isinstance(p, tuple)), None)
                    if raw:
                        yield email.message_from_bytes(raw, policy=default_policy)


def sender_domains(brokers: Iterable[dict]) -> set[str]:
    return {d for b in brokers for d in broker_domains(b)}


def failed_authentication(msg: EmailMessage) -> bool:
    """True when the receiving mail server says SPF or DMARC failed (likely spoofed)."""
    results = " ".join(str(h) for h in msg.get_all("Authentication-Results", [])).lower()
    return bool(re.search(r"\b(dmarc|spf)=(fail|softfail)", results))


# ---------------------------------------------------------------- visiting

def urllib_visitor(url: str) -> tuple[bool, str]:
    req = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0 (privacy-assistant)"})
    try:
        with urllib.request.urlopen(req, timeout=30) as resp:
            return resp.status < 400, f"HTTP {resp.status} {resp.geturl()}"
    except Exception as e:  # noqa: BLE001 - report any network failure to the user
        return False, str(e)


def process_inbox(brokers: list[dict], tracker: core.Tracker, messages: Iterable[EmailMessage],
                  visit: Visitor, only: set[str] | None = None) -> list[dict]:
    """Open confirmation links in new broker emails. Returns one result dict per email handled."""
    results = []
    for msg in messages:
        msg_id = msg.get("Message-ID") or f"{msg.get('From')}|{msg.get('Date')}|{msg.get('Subject')}"
        if tracker.is_processed(msg_id):
            continue
        broker = match_broker(msg, brokers)
        if not broker or (only and broker["id"] not in only):
            continue
        if failed_authentication(msg):
            tracker.mark_processed(msg_id, broker["id"], "skipped: failed SPF/DMARC")
            continue
        link = confirmation_link(msg, broker)
        if not link:
            tracker.mark_processed(msg_id, broker["id"], "no confirmation link")
            continue
        ok, detail = visit(link)
        result = {"broker": broker["id"], "subject": msg.get("Subject", ""), "url": link,
                  "ok": ok, "detail": detail}
        results.append(result)
        if ok:
            tracker.mark_processed(msg_id, broker["id"], detail)
            row = tracker.get(broker["id"])
            if row is None or row["status"] in ("unchecked", "found", "submitted"):
                tracker.set(broker["id"], "submitted", note=f"Confirmed via email link: {link}")
    return results


def watch(mailbox, brokers: list[dict], tracker: core.Tracker, visit: Visitor,
          only: set[str] | None = None, minutes: float = 10, poll_seconds: int = 30,
          sleep: Callable[[float], None] | None = None, on_result: Callable[[dict], None] | None = None,
          ) -> list[dict]:
    """Poll the mailbox until every broker in `only` is confirmed or time runs out."""
    import time
    sleep = sleep or time.sleep
    deadline = time.monotonic() + minutes * 60
    results: list[dict] = []
    pending = set(only) if only else None
    domains = sender_domains(b for b in brokers if not only or b["id"] in only)
    while True:
        for r in process_inbox(brokers, tracker, mailbox.messages_since(2, domains), visit, only):
            results.append(r)
            if on_result:
                on_result(r)
            if r["ok"] and pending is not None:
                pending.discard(r["broker"])
        if pending is not None and not pending:
            return results
        if time.monotonic() + poll_seconds > deadline:
            return results
        sleep(poll_seconds)
