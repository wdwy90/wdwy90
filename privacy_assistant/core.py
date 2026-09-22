"""Core logic: profile, broker catalog, status tracking, email drafting, reports."""

from __future__ import annotations

import html
import json
import os
import smtplib
import sqlite3
from dataclasses import asdict, dataclass, field
from datetime import datetime, timedelta, timezone
from email.message import EmailMessage
from pathlib import Path
from urllib.parse import quote

BROKERS_FILE = Path(__file__).with_name("brokers.json")

STATUSES = ("unchecked", "not_found", "found", "submitted", "removed")

US_STATES = {
    "AL": "Alabama", "AK": "Alaska", "AZ": "Arizona", "AR": "Arkansas", "CA": "California",
    "CO": "Colorado", "CT": "Connecticut", "DE": "Delaware", "DC": "District-of-Columbia",
    "FL": "Florida", "GA": "Georgia", "HI": "Hawaii", "ID": "Idaho", "IL": "Illinois",
    "IN": "Indiana", "IA": "Iowa", "KS": "Kansas", "KY": "Kentucky", "LA": "Louisiana",
    "ME": "Maine", "MD": "Maryland", "MA": "Massachusetts", "MI": "Michigan", "MN": "Minnesota",
    "MS": "Mississippi", "MO": "Missouri", "MT": "Montana", "NE": "Nebraska", "NV": "Nevada",
    "NH": "New-Hampshire", "NJ": "New-Jersey", "NM": "New-Mexico", "NY": "New-York",
    "NC": "North-Carolina", "ND": "North-Dakota", "OH": "Ohio", "OK": "Oklahoma", "OR": "Oregon",
    "PA": "Pennsylvania", "RI": "Rhode-Island", "SC": "South-Carolina", "SD": "South-Dakota",
    "TN": "Tennessee", "TX": "Texas", "UT": "Utah", "VT": "Vermont", "VA": "Virginia",
    "WA": "Washington", "WV": "West-Virginia", "WI": "Wisconsin", "WY": "Wyoming",
}


def data_dir() -> Path:
    d = Path(os.environ.get("PRIVACY_ASSISTANT_HOME", Path.home() / ".privacy-assistant"))
    d.mkdir(parents=True, exist_ok=True)
    return d


def now() -> datetime:
    return datetime.now(timezone.utc)


# ---------------------------------------------------------------- profile

@dataclass
class Address:
    street: str = ""
    city: str = ""
    state: str = ""
    zip: str = ""

    def one_line(self) -> str:
        return ", ".join(p for p in (self.street, self.city, f"{self.state} {self.zip}".strip()) if p)


@dataclass
class Profile:
    first_name: str
    last_name: str
    middle_name: str = ""
    aliases: list[str] = field(default_factory=list)
    emails: list[str] = field(default_factory=list)
    phones: list[str] = field(default_factory=list)
    addresses: list[Address] = field(default_factory=list)
    birth_year: str = ""

    @property
    def full_name(self) -> str:
        return " ".join(p for p in (self.first_name, self.middle_name, self.last_name) if p)

    @property
    def primary_address(self) -> Address:
        return self.addresses[0] if self.addresses else Address()

    @classmethod
    def from_dict(cls, d: dict) -> "Profile":
        d = dict(d)
        d["addresses"] = [Address(**a) for a in d.get("addresses", [])]
        return cls(**d)

    def to_dict(self) -> dict:
        return asdict(self)


def profile_path() -> Path:
    return data_dir() / "profile.json"


def load_profile() -> Profile:
    p = profile_path()
    if not p.exists():
        raise SystemExit("No profile found. Run `privacy-assistant init` first.")
    return Profile.from_dict(json.loads(p.read_text()))


def save_profile(profile: Profile) -> Path:
    p = profile_path()
    p.write_text(json.dumps(profile.to_dict(), indent=2))
    os.chmod(p, 0o600)
    return p


# ---------------------------------------------------------------- brokers

def load_brokers(path: Path = BROKERS_FILE) -> list[dict]:
    return json.loads(path.read_text())["brokers"]


def get_broker(broker_id: str, brokers: list[dict] | None = None) -> dict:
    for b in brokers or load_brokers():
        if b["id"] == broker_id:
            return b
    raise SystemExit(f"Unknown broker '{broker_id}'. Run `privacy-assistant brokers` to list ids.")


def search_url(broker: dict, profile: Profile) -> str | None:
    template = broker.get("search_url")
    if not template:
        return None
    addr = profile.primary_address
    state = addr.state.upper()
    values = {
        "first": profile.first_name,
        "last": profile.last_name,
        "city": addr.city.replace(" ", "-"),
        "state": state,
        "state_full": US_STATES.get(state, state),
    }
    return template.format(**{k: quote(v, safe="-") for k, v in values.items()})


# ---------------------------------------------------------------- tracking

class Tracker:
    def __init__(self, db_path: Path | None = None):
        self.db = sqlite3.connect(db_path or data_dir() / "tracker.db")
        self.db.row_factory = sqlite3.Row
        self.db.executescript(
            """
            CREATE TABLE IF NOT EXISTS status (
                broker_id TEXT PRIMARY KEY,
                status TEXT NOT NULL,
                listing_url TEXT,
                updated_at TEXT NOT NULL
            );
            CREATE TABLE IF NOT EXISTS history (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                broker_id TEXT NOT NULL,
                status TEXT NOT NULL,
                note TEXT,
                at TEXT NOT NULL
            );
            CREATE TABLE IF NOT EXISTS processed_messages (
                message_id TEXT PRIMARY KEY,
                broker_id TEXT NOT NULL,
                detail TEXT,
                at TEXT NOT NULL
            );
            """
        )

    def get(self, broker_id: str) -> sqlite3.Row | None:
        return self.db.execute("SELECT * FROM status WHERE broker_id = ?", (broker_id,)).fetchone()

    def set(self, broker_id: str, status: str, listing_url: str | None = None,
            note: str | None = None, at: datetime | None = None) -> None:
        if status not in STATUSES:
            raise SystemExit(f"Invalid status '{status}'. Choose from: {', '.join(STATUSES)}")
        ts = (at or now()).isoformat()
        existing = self.get(broker_id)
        if listing_url is None and existing:
            listing_url = existing["listing_url"]
        with self.db:
            self.db.execute(
                "INSERT INTO status (broker_id, status, listing_url, updated_at) VALUES (?, ?, ?, ?) "
                "ON CONFLICT(broker_id) DO UPDATE SET status=excluded.status, "
                "listing_url=excluded.listing_url, updated_at=excluded.updated_at",
                (broker_id, status, listing_url, ts),
            )
            self.db.execute(
                "INSERT INTO history (broker_id, status, note, at) VALUES (?, ?, ?, ?)",
                (broker_id, status, note, ts),
            )

    def is_processed(self, message_id: str) -> bool:
        return self.db.execute(
            "SELECT 1 FROM processed_messages WHERE message_id = ?", (message_id,)
        ).fetchone() is not None

    def mark_processed(self, message_id: str, broker_id: str, detail: str = "") -> None:
        with self.db:
            self.db.execute(
                "INSERT OR IGNORE INTO processed_messages (message_id, broker_id, detail, at) "
                "VALUES (?, ?, ?, ?)",
                (message_id, broker_id, detail, now().isoformat()),
            )

    def history(self, broker_id: str) -> list[sqlite3.Row]:
        return self.db.execute(
            "SELECT * FROM history WHERE broker_id = ? ORDER BY id", (broker_id,)
        ).fetchall()


def next_action(broker: dict, row: sqlite3.Row | None, when: datetime | None = None) -> str | None:
    """Return what the user should do for this broker now, or None if nothing is due."""
    when = when or now()
    if row is None or row["status"] == "unchecked":
        return "Check whether you're listed"
    status = row["status"]
    updated = datetime.fromisoformat(row["updated_at"])
    if status == "found":
        return "Submit opt-out"
    if status == "submitted":
        if when >= updated + timedelta(days=broker.get("processing_days", 7) + 7):
            return "Verify removal (processing window has passed)"
        return None
    if status in ("removed", "not_found"):
        if when >= updated + timedelta(days=broker.get("recheck_days", 90)):
            return "Re-scan (listings often reappear)"
    return None


def due_items(brokers: list[dict], tracker: Tracker, when: datetime | None = None) -> list[tuple[dict, str]]:
    out = []
    for b in brokers:
        action = next_action(b, tracker.get(b["id"]), when)
        if action:
            out.append((b, action))
    return out


# ---------------------------------------------------------------- email requests

def deletion_email(broker: dict, profile: Profile, listing_url: str | None = None) -> EmailMessage:
    addr_lines = "\n".join(f"  - {a.one_line()}" for a in profile.addresses) or "  - (none provided)"
    extra = []
    if profile.aliases:
        extra.append(f"Other names: {', '.join(profile.aliases)}")
    if profile.emails:
        extra.append(f"Email addresses: {', '.join(profile.emails)}")
    if profile.phones:
        extra.append(f"Phone numbers: {', '.join(profile.phones)}")
    if listing_url:
        extra.append(f"Listing URL: {listing_url}")
    extra_block = "\n".join(extra)

    body = f"""To the {broker['name']} privacy team,

I am requesting that you delete all personal information you hold about me and
opt me out of any sale or sharing of my personal information. This request is
made under the California Consumer Privacy Act (as amended by the CPRA) and any
other applicable state privacy law, including those of Virginia, Colorado,
Connecticut, Texas, Oregon, and others that grant a right to deletion.

Please use the details below only to locate and remove my records:

Name: {profile.full_name}
{extra_block}
Addresses:
{addr_lines}

Please confirm in writing once my information has been deleted, and ensure it is
not re-added from future data sources. If you require additional verification,
tell me exactly what is needed and why.

Thank you,
{profile.full_name}
"""
    msg = EmailMessage()
    msg["To"] = broker["email"]
    msg["From"] = profile.emails[0] if profile.emails else ""
    msg["Subject"] = f"Personal data deletion and opt-out request - {profile.full_name}"
    msg.set_content(body)
    return msg


def save_draft(msg: EmailMessage, broker_id: str) -> Path:
    outbox = data_dir() / "outbox"
    outbox.mkdir(exist_ok=True)
    path = outbox / f"{broker_id}-{now().strftime('%Y%m%d-%H%M%S')}.eml"
    path.write_bytes(bytes(msg))
    return path


def send_email(msg: EmailMessage) -> None:
    """Send via SMTP. Configure with PA_SMTP_HOST/PORT/USER/PASSWORD (e.g. Gmail + app password)."""
    host = os.environ.get("PA_SMTP_HOST")
    user = os.environ.get("PA_SMTP_USER")
    password = os.environ.get("PA_SMTP_PASSWORD")
    if not (host and user and password):
        raise SystemExit("Set PA_SMTP_HOST, PA_SMTP_USER and PA_SMTP_PASSWORD to send email.")
    port = int(os.environ.get("PA_SMTP_PORT", "587"))
    if not msg["From"]:
        del msg["From"]
        msg["From"] = user
    with smtplib.SMTP(host, port, timeout=30) as smtp:
        smtp.starttls()
        smtp.login(user, password)
        smtp.send_message(msg)


# ---------------------------------------------------------------- report

def html_report(brokers: list[dict], tracker: Tracker, profile: Profile) -> str:
    rows = []
    counts = {s: 0 for s in STATUSES}
    for b in brokers:
        row = tracker.get(b["id"])
        status = row["status"] if row else "unchecked"
        counts[status] += 1
        action = next_action(b, row) or ""
        s_url = search_url(b, profile)
        links = []
        if s_url:
            links.append(f'<a href="{html.escape(s_url)}" target="_blank" rel="noopener">Search</a>')
        links.append(f'<a href="{html.escape(b["optout_url"])}" target="_blank" rel="noopener">Opt out</a>')
        if row and row["listing_url"]:
            links.append(f'<a href="{html.escape(row["listing_url"])}" target="_blank" rel="noopener">Listing</a>')
        updated = row["updated_at"][:10] if row else ""
        rows.append(
            f"<tr><td>{html.escape(b['name'])}<div class='id'>{b['id']}</div></td>"
            f"<td><span class='pill {status}'>{status.replace('_', ' ')}</span></td>"
            f"<td>{updated}</td><td>{html.escape(action)}</td><td class='links'>{' '.join(links)}</td></tr>"
        )
    summary = " · ".join(f"{counts[s]} {s.replace('_', ' ')}" for s in STATUSES)
    return f"""<!doctype html>
<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Data Broker Removal</title>
<style>
:root {{ --bg:#fafaf9; --fg:#1c1917; --muted:#78716c; --line:#e7e5e4; --card:#fff; }}
@media (prefers-color-scheme: dark) {{ :root {{ --bg:#1c1917; --fg:#f5f5f4; --muted:#a8a29e; --line:#44403c; --card:#292524; }} }}
body {{ margin:0; padding:24px 16px; background:var(--bg); color:var(--fg); font:15px/1.5 system-ui,sans-serif; }}
main {{ max-width:1000px; margin:auto; }}
h1 {{ font-size:22px; margin:0 0 4px; }}
.sub {{ color:var(--muted); margin-bottom:20px; }}
.wrap {{ overflow-x:auto; background:var(--card); border:1px solid var(--line); border-radius:10px; }}
table {{ width:100%; border-collapse:collapse; }}
th, td {{ text-align:left; padding:10px 12px; border-bottom:1px solid var(--line); vertical-align:top; }}
th {{ font-size:12px; text-transform:uppercase; letter-spacing:.04em; color:var(--muted); }}
.id {{ font-size:12px; color:var(--muted); font-family:ui-monospace,monospace; }}
.links a {{ margin-right:10px; white-space:nowrap; }}
.pill {{ padding:2px 8px; border-radius:99px; font-size:12px; white-space:nowrap; background:#e7e5e4; color:#1c1917; }}
.found {{ background:#fecaca; }} .submitted {{ background:#fde68a; }}
.removed, .not_found {{ background:#bbf7d0; }}
</style></head><body><main>
<h1>Data broker removal — {html.escape(profile.full_name)}</h1>
<div class="sub">Generated {now().strftime('%Y-%m-%d %H:%M UTC')} · {summary}</div>
<div class="wrap"><table>
<thead><tr><th>Broker</th><th>Status</th><th>Updated</th><th>Next action</th><th>Links</th></tr></thead>
<tbody>{''.join(rows)}</tbody></table></div>
<p class="sub">Update statuses with <code>privacy-assistant mark &lt;broker-id&gt; &lt;status&gt;</code>.</p>
</main></body></html>
"""
