# privacy-assistant

A self-hosted, DeleteMe-style tool for removing your personal information from data brokers and
people-search sites. It keeps a catalog of 22 major brokers (covering 25+ sites), builds search links
for your name, walks you through each opt-out or emails a deletion request for you, tracks status,
and tells you when to re-check, since brokers often re-list people after a few months.

Everything stays on your machine (`~/.privacy-assistant/`, or `$PRIVACY_ASSISTANT_HOME`). No
dependencies beyond Python 3.9+.

## Install

```bash
pip install -e .          # or run without installing: python -m privacy_assistant ...
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
| Submits opt-outs | Done for you | Guided steps; email requests can be sent automatically |
| Captchas and phone/email verification | Handled by them | You handle them (usually a click or a call) |
| Re-scans | Quarterly | `due` tells you when |
| Broker coverage | 750+ | 22 high-impact brokers (edit `brokers.json` to add more) |
| Cost and data custody | About $129/yr; they hold your data | Free; your data stays local |

Most people-search sites block automated scraping with Cloudflare and captchas, and many require a
verification click or call. The tool doesn't try to bypass those. You do those steps yourself;
it handles the tracking and paperwork.

**California residents:** also file a single request through the state's Delete Request and Opt-out
Platform (DROP) under the California Delete Act. It covers every registered data broker at once.

## Keeping the broker list current

Opt-out URLs change. `privacy_assistant/brokers.json` is plain data: fix a broken link or add a
broker by copying an existing entry. Search URL placeholders are `{first} {last} {city} {state}
{state_full}`.

## Tests

```bash
python -m unittest discover -s tests
```
