"""Command-line interface for the data-broker removal assistant."""

from __future__ import annotations

import argparse
import json
import sys
import webbrowser
from pathlib import Path

from . import core


def _ask(prompt: str, default: str = "") -> str:
    suffix = f" [{default}]" if default else ""
    return input(f"{prompt}{suffix}: ").strip() or default


def _ask_list(prompt: str) -> list[str]:
    raw = _ask(f"{prompt} (comma-separated, blank to skip)")
    return [x.strip() for x in raw.split(",") if x.strip()]


def cmd_init(args) -> None:
    if args.from_json:
        profile = core.Profile.from_dict(json.loads(Path(args.from_json).read_text()))
    else:
        print("Your details stay on this computer and are only used to find and remove your listings.\n")
        profile = core.Profile(
            first_name=_ask("First name"),
            last_name=_ask("Last name"),
            middle_name=_ask("Middle name/initial"),
            aliases=_ask_list("Other names (maiden, nicknames)"),
            emails=_ask_list("Email addresses"),
            phones=_ask_list("Phone numbers"),
            birth_year=_ask("Birth year (helps match listings)"),
        )
        print("\nAddresses — current first, then past addresses. Blank street to finish.")
        while True:
            street = _ask("  Street")
            if not street:
                break
            profile.addresses.append(core.Address(
                street=street, city=_ask("  City"), state=_ask("  State (2-letter)").upper(), zip=_ask("  ZIP"),
            ))
    path = core.save_profile(profile)
    print(f"Saved profile to {path}")


def cmd_brokers(args) -> None:
    for b in core.load_brokers():
        print(f"{b['id']:<26} {b['method']:<6} {b['name']}")


def cmd_scan(args) -> None:
    profile = core.load_profile()
    tracker = core.Tracker()
    brokers = core.load_brokers()
    if args.broker:
        brokers = [core.get_broker(args.broker, brokers)]
    elif not args.all:
        brokers = [b for b, action in core.due_items(brokers, tracker)
                   if action.startswith(("Check", "Re-scan", "Verify"))]
    if not brokers:
        print("Nothing to scan right now. Use --all to scan everything anyway.")
        return
    print(f"Checking {len(brokers)} broker(s) for {profile.full_name}.\n"
          "Open each link, look for your listing, then record the result with `mark`.\n")
    for b in brokers:
        url = core.search_url(b, profile)
        print(f"- {b['name']} ({b['id']})")
        print(f"    {url or 'No public search — submit opt-out directly: ' + b['optout_url']}")
        if args.open and url:
            webbrowser.open_new_tab(url)
    print("\nRecord results, e.g.:\n"
          "  privacy-assistant mark spokeo found --url https://www.spokeo.com/...\n"
          "  privacy-assistant mark whitepages not_found")


def cmd_mark(args) -> None:
    core.get_broker(args.broker)
    core.Tracker().set(args.broker, args.status, listing_url=args.url, note=args.note)
    print(f"{args.broker}: {args.status}")


def cmd_optout(args) -> None:
    profile = core.load_profile()
    tracker = core.Tracker()
    brokers = core.load_brokers()
    if args.broker == "found":
        targets = [b for b in brokers if (r := tracker.get(b["id"])) and r["status"] == "found"]
        if not targets:
            print("No brokers are marked 'found'. Run `scan` and `mark` first.")
            return
    else:
        targets = [core.get_broker(args.broker, brokers)]

    for b in targets:
        row = tracker.get(b["id"])
        listing = row["listing_url"] if row else None
        print(f"\n== {b['name']} ==")
        if b["method"] == "email":
            msg = core.deletion_email(b, profile, listing)
            if args.send:
                core.send_email(msg)
                tracker.set(b["id"], "submitted", note=f"Emailed {b['email']}")
                print(f"Sent deletion request to {b['email']} and marked submitted.")
            else:
                path = core.save_draft(msg, b["id"])
                print(f"Draft saved: {path}\nReview it, then rerun with --send (or send it yourself and "
                      f"`mark {b['id']} submitted`).")
        else:
            print(f"Opt-out page: {b['optout_url']}")
            if listing:
                print(f"Your listing: {listing}")
            print(f"Steps: {b.get('steps', '')}")
            if b.get("needs_email_verification"):
                print("Note: watch your inbox for a confirmation link.")
            if b.get("needs_phone_verification"):
                print("Note: requires a phone verification call.")
            print(f"When done: privacy-assistant mark {b['id']} submitted")
            if args.open:
                webbrowser.open_new_tab(b["optout_url"])


def cmd_status(args) -> None:
    tracker = core.Tracker()
    print(f"{'BROKER':<26} {'STATUS':<11} {'UPDATED':<11} NEXT ACTION")
    for b in core.load_brokers():
        row = tracker.get(b["id"])
        status = row["status"] if row else "unchecked"
        updated = row["updated_at"][:10] if row else "-"
        print(f"{b['id']:<26} {status:<11} {updated:<11} {core.next_action(b, row) or '-'}")


def cmd_due(args) -> None:
    items = core.due_items(core.load_brokers(), core.Tracker())
    if not items:
        print("You're all caught up.")
        return
    for b, action in items:
        print(f"- {b['name']} ({b['id']}): {action}")
    print(f"\n{len(items)} item(s) due.")


def cmd_report(args) -> None:
    out = Path(args.output) if args.output else core.data_dir() / "report.html"
    out.write_text(core.html_report(core.load_brokers(), core.Tracker(), core.load_profile()))
    print(f"Report written to {out}")
    if args.open:
        webbrowser.open(out.resolve().as_uri())


def build_parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(
        prog="privacy-assistant",
        description="Find and remove your personal info from data broker / people-search sites.",
    )
    sub = p.add_subparsers(dest="command", required=True)

    s = sub.add_parser("init", help="Create or replace your profile")
    s.add_argument("--from-json", help="Load profile from a JSON file instead of prompting")
    s.set_defaults(func=cmd_init)

    sub.add_parser("brokers", help="List supported brokers").set_defaults(func=cmd_brokers)

    s = sub.add_parser("scan", help="Show search links to check for your listings")
    s.add_argument("broker", nargs="?", help="Only this broker id")
    s.add_argument("--all", action="store_true", help="Include brokers not currently due")
    s.add_argument("--open", action="store_true", help="Open links in your browser")
    s.set_defaults(func=cmd_scan)

    s = sub.add_parser("mark", help="Record a broker's status")
    s.add_argument("broker")
    s.add_argument("status", choices=core.STATUSES)
    s.add_argument("--url", help="URL of your listing on that site")
    s.add_argument("--note")
    s.set_defaults(func=cmd_mark)

    s = sub.add_parser("optout", help="Walk through (or email) an opt-out request")
    s.add_argument("broker", help="Broker id, or 'found' for every broker marked found")
    s.add_argument("--send", action="store_true", help="Actually send email requests via SMTP")
    s.add_argument("--open", action="store_true", help="Open web opt-out pages in your browser")
    s.set_defaults(func=cmd_optout)

    sub.add_parser("status", help="Show status for every broker").set_defaults(func=cmd_status)
    sub.add_parser("due", help="Show what needs attention now").set_defaults(func=cmd_due)

    s = sub.add_parser("report", help="Write an HTML dashboard")
    s.add_argument("-o", "--output")
    s.add_argument("--open", action="store_true")
    s.set_defaults(func=cmd_report)
    return p


def main(argv: list[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    args.func(args)
    return 0


if __name__ == "__main__":
    sys.exit(main())
