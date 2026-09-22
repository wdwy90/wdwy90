import functools
import importlib.util
import http.server
import os
import tempfile
import threading
import unittest
from email.message import EmailMessage
from pathlib import Path

from privacy_assistant import core, inbox

FIXTURES = Path(__file__).with_name("fixtures")
SPOKEO = {"id": "spokeo", "name": "Spokeo", "method": "web",
          "search_url": "https://www.spokeo.com/{first}-{last}/{state_full}",
          "optout_url": "https://www.spokeo.com/optout"}


def broker_email(sender, html, auth=None, msg_id="<1@x>"):
    msg = EmailMessage()
    msg["From"] = sender
    msg["Subject"] = "Please confirm your opt-out"
    msg["Message-ID"] = msg_id
    if auth:
        msg["Authentication-Results"] = auth
    msg.set_content("plain version")
    msg.add_alternative(html, subtype="html")
    return msg


class InboxTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        os.environ["PRIVACY_ASSISTANT_HOME"] = self.tmp.name
        self.tracker = core.Tracker()

    def tearDown(self):
        self.tracker.db.close()
        self.tmp.cleanup()

    def test_picks_confirmation_link_on_broker_domain(self):
        msg = broker_email("Spokeo <noreply@notify.spokeo.com>", """
            <a href="https://www.spokeo.com/privacy-policy">Privacy policy</a>
            <a href="https://evil.example.com/confirm?t=1">Confirm</a>
            <a href="https://www.spokeo.com/unsubscribe?x=1">Unsubscribe</a>
            <a href="https://www.spokeo.com/optout/confirm?token=abc">Click here to confirm</a>""")
        self.assertEqual(inbox.match_broker(msg, [SPOKEO])["id"], "spokeo")
        self.assertEqual(inbox.confirmation_link(msg, SPOKEO), "https://www.spokeo.com/optout/confirm?token=abc")

    def test_tracking_wrapped_link(self):
        msg = broker_email("noreply@spokeo.com",
                           '<a href="https://u123.ct.sendgrid.net/ls/click?upn=zz">Verify my request</a>')
        self.assertEqual(inbox.confirmation_link(msg, SPOKEO), "https://u123.ct.sendgrid.net/ls/click?upn=zz")

    def test_unrelated_sender_ignored(self):
        msg = broker_email("promo@shop.example.com", '<a href="https://www.spokeo.com/optout/confirm">x</a>')
        self.assertIsNone(inbox.match_broker(msg, [SPOKEO]))

    def test_process_inbox_marks_submitted_once_and_skips_spoofed(self):
        good = broker_email("noreply@spokeo.com",
                            '<a href="https://www.spokeo.com/optout/confirm?token=abc">Confirm</a>',
                            auth="mx.google.com; spf=pass; dmarc=pass")
        spoofed = broker_email("noreply@spokeo.com",
                               '<a href="https://www.spokeo.com/optout/confirm?token=zzz">Confirm</a>',
                               auth="mx.google.com; spf=fail; dmarc=fail", msg_id="<2@x>")
        visited = []
        visit = lambda url: (visited.append(url) or True, "HTTP 200")
        results = inbox.process_inbox([SPOKEO], self.tracker, [good, spoofed], visit)
        self.assertEqual(visited, ["https://www.spokeo.com/optout/confirm?token=abc"])
        self.assertEqual(len(results), 1)
        self.assertEqual(self.tracker.get("spokeo")["status"], "submitted")
        self.assertEqual(inbox.process_inbox([SPOKEO], self.tracker, [good], visit), [])

    def test_watch_stops_when_confirmed(self):
        msg = broker_email("noreply@spokeo.com", '<a href="https://www.spokeo.com/optout/confirm?t=1">Confirm</a>')
        batches = [[], [msg]]

        class FakeMailbox:
            def messages_since(self, days, domains=None):
                return batches.pop(0) if batches else []

        sleeps = []
        results = inbox.watch(FakeMailbox(), [SPOKEO], self.tracker, lambda u: (True, "ok"),
                              {"spokeo"}, minutes=5, poll_seconds=1, sleep=sleeps.append)
        self.assertEqual(len(results), 1)
        self.assertEqual(len(sleeps), 1)


HAVE_PLAYWRIGHT = importlib.util.find_spec("playwright") is not None


@unittest.skipUnless(HAVE_PLAYWRIGHT, "playwright not installed")
class BrowserTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        class Quiet(http.server.SimpleHTTPRequestHandler):
            def log_message(self, *args):
                pass

        handler = functools.partial(Quiet, directory=str(FIXTURES))
        cls.server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), handler)
        threading.Thread(target=cls.server.serve_forever, daemon=True).start()
        cls.base = f"http://127.0.0.1:{cls.server.server_address[1]}"
        cls.tmp = tempfile.TemporaryDirectory()
        os.environ["PRIVACY_ASSISTANT_HOME"] = cls.tmp.name
        from privacy_assistant import autofill
        cls.autofill = autofill
        cls.pw, cls.context = autofill.launch(headless=True)
        cls.profile = core.Profile(first_name="Jane", last_name="Doe", emails=["jane@example.com"],
                                   addresses=[core.Address("1 Main St", "Austin", "TX", "78701")])

    @classmethod
    def tearDownClass(cls):
        cls.context.close()
        cls.pw.stop()
        cls.server.shutdown()
        cls.tmp.cleanup()

    def broker(self, page, **extra):
        return {"id": "t", "name": "T", "method": "web", "optout_url": f"{self.base}/{page}", **extra}

    def test_classify_field(self):
        c = self.autofill.classify_field
        self.assertEqual(c("", "email"), "email")
        self.assertEqual(c("First Name fname"), "first_name")
        self.assertEqual(c("lname Last Name"), "last_name")
        self.assertEqual(c("Your e-mail"), "email")
        self.assertEqual(c("profile_url"), "listing_url")
        self.assertIsNone(c("q", "search"))
        self.assertIsNone(c("coupon code"))

    def test_spokeo_layout_avoids_header_search(self):
        pause = lambda m: self.fail(f"unexpected pause: {m}")
        res = self.autofill.submit_optout(self.context, self.broker("spokeo_like.html"), self.profile,
                                          "https://www.spokeo.com/Jane-Doe/p123", pause)
        page = res["page"]
        self.assertTrue(res["submitted"])
        self.assertEqual(page.title(), "Sent")
        self.assertEqual(page.locator("input[name=q]").input_value(), "")
        self.assertIn("jane@example.com", page.evaluate("document.body.dataset.sent"))
        self.assertIn("p123", page.evaluate("document.body.dataset.sent"))
        page.close()

    def test_generic_form_with_labels_select_and_consent(self):
        res = self.autofill.submit_optout(self.context, self.broker("generic.html"), self.profile, None,
                                          lambda m: "")
        page = res["page"]
        self.assertEqual(page.locator("#fn").input_value(), "Jane")
        self.assertEqual(page.locator("#ln").input_value(), "Doe")
        self.assertEqual(page.locator("[name=city]").input_value(), "Austin")
        self.assertEqual(page.locator("[name=state]").input_value(), "TX")
        self.assertEqual(page.locator("[name=contact_email]").input_value(), "jane@example.com")
        self.assertTrue(page.locator("[name=agree]").is_checked())
        self.assertFalse(page.locator("[name=news]").is_checked())
        self.assertEqual(page.title(), "Sent")
        page.close()

    def test_captcha_blocks_auto_submit_and_asks_user(self):
        prompts = []
        res = self.autofill.submit_optout(self.context, self.broker("captcha.html"), self.profile, None,
                                          lambda m: prompts.append(m) or "")
        self.assertTrue(prompts and "captcha" in prompts[0].lower())
        self.assertFalse(res["submitted"])
        self.assertEqual(res["page"].locator("[name=email]").input_value(), "jane@example.com")
        self.assertNotEqual(res["page"].title(), "Sent")
        res["page"].close()

    def test_visitor_clicks_confirm_button(self):
        ok, detail = self.autofill.browser_visitor(self.context)(f"{self.base}/confirm.html")
        self.assertTrue(ok, detail)
        page = self.context.pages[-1]
        self.assertEqual(page.title(), "Confirmed")


if __name__ == "__main__":
    unittest.main()
