# privacy-assistant

A self-hosted, DeleteMe-style tool for removing your personal information from data brokers and
people-search sites. It keeps a catalog of 22 major brokers (covering 25+ sites), builds search links
for your name, walks you through each opt-out or emails a deletion request for you, tracks status,
and tells you when to re-check, since brokers often re-list people after a few months.

Everything stays on your machine (`~/.privacy-assistant/`, or `$PRIVACY_ASSISTANT_HOME`). No
dependencies beyond Python 3.9+.

## Install

```bash
pip install -e ".[auto]"          # the [auto] extra adds browser automation
playwright install chromium
```

## Workflow

```bash
privacy-assistant init                    # enter names, emails, phones, current + past addresses
privacy-assistant scan --open             # opens a search for you on each broker
privacy-assistant mark spokeo found --url https://www.spokeo.com/Jane-Doe/...
privacy-assistant mark whitepages not_found
privacy-assistant optout found --open     # step-by-step for every broker you're listed on
privacy-assistant mark spokeo submitted   # after you finish a web form
privacy-assistant due                     # what needs attention today
privacy-assistant report --open           # HTML dashboard with status + links
```

Statuses: `unchecked` → `found` → `submitted` → `removed` (or `not_found`).

The `due` command flags:
- brokers you haven't checked yet
- brokers you're listed on but haven't submitted
- submissions past their processing window, so you can confirm removal
- removed or not-found brokers past their re-check interval (60–365 days)

Run `due` weekly, or schedule it with cron.

## Hands-off mode: `auto` and `confirm`

`auto` does the online work for you. It opens a visible browser, fills in and submits each opt-out
form, and watches your inbox for the broker's verification email. When the email arrives, it opens
the confirmation link in the same browser session, which several brokers require, and clicks the
confirm button if the page has one.

```bash
# one-time: let it read your inbox (Gmail: create an app password; IMAP must be on)
export PA_IMAP_USER=you@gmail.com PA_IMAP_PASSWORD=<app password>   # PA_IMAP_HOST defaults to imap.gmail.com
export PA_SMTP_HOST=smtp.gmail.com PA_SMTP_USER=you@gmail.com PA_SMTP_PASSWORD=<app password>

privacy-assistant auto            # every broker marked 'found'
privacy-assistant auto all        # every broker not yet submitted (useful for search-first sites)
privacy-assistant auto spokeo     # one broker
```

For each broker it:
1. Opens the opt-out page and fills in your email, name, city/state, listing URL, and so on. It
   skips the site's own search bar and ticks only "I agree / I certify" checkboxes, never marketing
   opt-ins.
2. Submits the form if no captcha is showing.
3. For sites that verify by email, checks your inbox and spam folder every 30 seconds (up to
   `--wait` minutes, default 5) and opens the confirmation link.
4. Marks the broker `submitted`.

To confirm links later, or unattended from cron:

```bash
privacy-assistant confirm                   # open any pending verification links from the last 7 days
privacy-assistant confirm --watch 30        # keep checking for 30 minutes
privacy-assistant confirm --headless        # no window (fine for most confirmation links)
```

**What it can't do for you:**
- **Captchas and Cloudflare checks.** It pauses and asks you to solve them in the window; it doesn't
  use captcha-solving services or try to hide that it's automated.
- **Phone verification** (Whitepages). It pauses while you take the call.
- **Picking your record from search results** on sites like BeenVerified. Look-alike records are
  common, so the tool leaves that choice to you and continues once you press Enter.

Safety checks on inbox automation:
- Only reads mail from broker domains.
- Skips any email that fails SPF/DMARC checks, which usually means a forged sender.
- Only follows links that point to the broker's own site or its email provider's click tracker.
- Never follows unsubscribe or policy links, and never opens the same email twice.

The browser profile is kept in `~/.privacy-assistant/browser-profile` so cookies persist between
runs, which means fewer bot checks. Tip: create a separate email alias for opt-outs, since brokers
keep the address you give them.

### Email-based requests

For brokers that take email requests, `optout <id>` writes a deletion/opt-out request citing CCPA/CPRA
and other state privacy laws to `~/.privacy-assistant/outbox/*.eml` so you can review it. To send it
directly and mark the broker submitted:

```bash
export PA_SMTP_HOST=smtp.gmail.com PA_SMTP_USER=you@gmail.com PA_SMTP_PASSWORD=<app password>
privacy-assistant optout mylife --send
```

## How this compares to DeleteMe

| | DeleteMe | privacy-assistant |
|---|---|---|
| Finds listings | Automated, by their staff | You open the generated search links and confirm |
| Submits opt-outs | Done for you | `auto` fills and submits forms; email requests are sent for you |
| Email verification links | Handled by them | `auto` / `confirm` open them for you |
| Captchas and phone verification | Handled by them | You solve them when the tool pauses |
| Re-scans | Quarterly | `due` tells you when |
| Broker coverage | 750+ | 22 high-impact brokers (edit `brokers.json` to add more) |
| Cost and data custody | About $129/yr; they hold your data | Free; your data stays local |

Most people-search sites use Cloudflare or captchas to block bots. The tool doesn't try to get
around them. It stops so you can solve the check, then carries on.

**California residents:** also file a single request through the state's Delete Request and Opt-out
Platform (DROP) under the California Delete Act. It covers every registered data broker at once.

## Keeping the broker list current

Opt-out URLs change. `privacy_assistant/brokers.json` is plain data: fix a broken link or add a
broker by copying an existing entry. Search URL placeholders are `{first} {last} {city} {state}
{state_full}`.

## Tests

```bash
python -m unittest discover -s tests   # browser tests run when Playwright is installed
```
