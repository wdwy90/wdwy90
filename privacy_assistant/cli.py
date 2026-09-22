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


def _pause(message: str) -> str:
    return input(f"\n>> {message}: ").strip()


def _print_result(r: dict) -> None:
    mark = "confirmed" if r["ok"] else "FAILED"
    print(f"  [{r['broker']}] {mark}: {r['subject']!r} -> {r['detail']}")


def _smtp_configured() -> bool:
    import os
    return all(os.environ.get(k) for k in ("PA_SMTP_HOST", "PA_SMTP_USER", "PA_SMTP_PASSWORD"))


def _mailbox_or_none():
    from . import inbox
    try:
        return inbox.ImapMailbox.from_env()
    except SystemExit as e:
        print(f"Note: {e}\nContinuing without automatic email confirmation.")
        return None


def cmd_auto(args) -> None:
    from . import autofill, inbox
    profile = core.load_profile()
    tracker = core.Tracker()
    brokers = core.load_brokers()
    if args.target == "found":
        targets = [b for b in brokers if (r := tracker.get(b["id"])) and r["status"] == "found"]
    elif args.target == "all":
        targets = [b for b in brokers
                   if not (r := tracker.get(b["id"])) or r["status"] not in ("submitted", "removed", "not_found")]
    else:
        targets = [core.get_broker(args.target, brokers)]
    if not targets:
        print("Nothing to do. Mark brokers 'found' after a scan, or use `auto all`.")
        return

    mailbox = None if args.no_inbox else _mailbox_or_none()
    pw, context = autofill.launch(headless=args.headless)
    visit = autofill.browser_visitor(context)
    try:
        for b in targets:
            row = tracker.get(b["id"])
            listing = row["listing_url"] if row else None
            print(f"\n== {b['name']} ==")
            if b["method"] == "email":
                msg = core.deletion_email(b, profile, listing)
                if _smtp_configured():
                    core.send_email(msg)
                    tracker.set(b["id"], "submitted", note=f"auto: emailed {b['email']}")
                    print(f"Emailed deletion request to {b['email']}.")
                else:
                    print(f"SMTP not configured; draft saved to {core.save_draft(msg, b['id'])}")
                continue

            res = autofill.submit_optout(context, b, profile, listing, _pause, auto_submit=not args.no_submit)
            print(f"Filled: {', '.join(res['filled']) or 'nothing recognized'}; "
                  f"submitted: {'yes' if res['submitted'] else 'no'}")
            if not res["submitted"]:
                _pause("Finish and submit the form in the browser window (select your record, solve any "
                       "captcha), then press Enter")
            if b.get("needs_phone_verification"):
                _pause("This site verifies by phone. Complete the call in the browser, then press Enter")
            if b.get("needs_email_verification") and mailbox:
                print(f"Watching your inbox for {b['name']}'s verification email (up to {args.wait} min)...")
                results = inbox.watch(mailbox, brokers, tracker, visit, {b["id"]}, minutes=args.wait,
                                      on_result=_print_result)
                if not any(r["ok"] for r in results):
                    print("  No verification email yet. Run `privacy-assistant confirm --watch 30` later.")
            answer = _pause("Anything left on this site? Finish it in the browser, then press Enter to "
                            "mark submitted (or type s to skip)")
            if answer.lower() != "s" and (tracker.get(b["id"]) or {"status": ""})["status"] != "submitted":
                tracker.set(b["id"], "submitted", note="auto: form submitted")
    finally:
        context.close()
        pw.stop()
    print("\nDone. Run `privacy-assistant due` in a week to verify removals.")


def cmd_confirm(args) -> None:
    from . import inbox
    mailbox = inbox.ImapMailbox.from_env()
    tracker = core.Tracker()
    brokers = core.load_brokers()
    only = {args.broker} if args.broker else None
    pw = context = None
    if args.no_browser:
        visit = inbox.urllib_visitor
    else:
        from . import autofill
        pw, context = autofill.launch(headless=args.headless)
        visit = autofill.browser_visitor(context)
    try:
        if args.watch:
            print(f"Watching inbox for {args.watch} min...")
            results = inbox.watch(mailbox, brokers, tracker, visit, only, minutes=args.watch,
                                  on_result=_print_result)
        else:
            domains = inbox.sender_domains(b for b in brokers if not only or b["id"] in only)
            results = inbox.process_inbox(brokers, tracker, mailbox.messages_since(args.days, domains),
                                          visit, only)
            for r in results:
                _print_result(r)
    finally:
        if context:
            context.close()
            pw.stop()
    print(f"{sum(r['ok'] for r in results)} confirmation link(s) opened.")


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

    s = sub.add_parser("auto", help="Fill and submit opt-out forms and click email confirmations for you")
    s.add_argument("target", nargs="?", default="found",
                   help="'found' (default), 'all' not-yet-submitted brokers, or a broker id")
    s.add_argument("--wait", type=float, default=5, help="Minutes to wait for each verification email")
    s.add_argument("--no-submit", action="store_true", help="Fill forms but let you click submit")
    s.add_argument("--no-inbox", action="store_true", help="Don't read your inbox")
    s.add_argument("--headless", action="store_true", help="Hide the browser (captchas will fail)")
    s.set_defaults(func=cmd_auto)

    s = sub.add_parser("confirm", help="Open verification links from broker emails in your inbox")
    s.add_argument("broker", nargs="?", help="Only this broker id")
    s.add_argument("--days", type=int, default=7, help="How far back to look")
    s.add_argument("--watch", type=float, metavar="MINUTES", help="Keep polling for new emails")
    s.add_argument("--no-browser", action="store_true", help="Open links with plain HTTP instead of a browser")
    s.add_argument("--headless", action="store_true")
    s.set_defaults(func=cmd_confirm)

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
