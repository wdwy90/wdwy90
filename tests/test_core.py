import os
import tempfile
import unittest
from datetime import timedelta

from privacy_assistant import core
from privacy_assistant.cli import main


class CoreTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        os.environ["PRIVACY_ASSISTANT_HOME"] = self.tmp.name
        self.profile = core.Profile(
            first_name="Jane", last_name="Doe", emails=["jane@example.com"],
            addresses=[core.Address("1 Main St", "San Jose", "CA", "95110")],
        )
        self.tracker = core.Tracker()
        self.brokers = core.load_brokers()

    def tearDown(self):
        self.tracker.db.close()
        self.tmp.cleanup()

    def test_broker_catalog_is_valid(self):
        ids = [b["id"] for b in self.brokers]
        self.assertEqual(len(ids), len(set(ids)))
        for b in self.brokers:
            self.assertIn(b["method"], ("web", "email"))
            self.assertTrue(b["optout_url"].startswith("https://"))
            if b["method"] == "email":
                self.assertIn("@", b["email"])
            core.search_url(b, self.profile)  # all placeholders must resolve

    def test_search_url(self):
        spokeo = core.get_broker("spokeo", self.brokers)
        self.assertEqual(core.search_url(spokeo, self.profile), "https://www.spokeo.com/Jane-Doe/California")
        fps = core.get_broker("fastpeoplesearch", self.brokers)
        self.assertEqual(core.search_url(fps, self.profile),
                         "https://www.fastpeoplesearch.com/name/Jane-Doe_San-Jose-CA")

    def test_next_action_lifecycle(self):
        b = core.get_broker("spokeo", self.brokers)
        self.assertEqual(core.next_action(b, None), "Check whether you're listed")
        start = core.now()
        self.tracker.set("spokeo", "found", listing_url="https://x")
        self.assertEqual(core.next_action(b, self.tracker.get("spokeo")), "Submit opt-out")
        self.tracker.set("spokeo", "submitted", at=start)
        row = self.tracker.get("spokeo")
        self.assertEqual(row["listing_url"], "https://x")
        self.assertIsNone(core.next_action(b, row, start + timedelta(days=1)))
        self.assertIn("Verify", core.next_action(b, row, start + timedelta(days=11)))
        self.tracker.set("spokeo", "removed", at=start)
        row = self.tracker.get("spokeo")
        self.assertIsNone(core.next_action(b, row, start + timedelta(days=30)))
        self.assertIn("Re-scan", core.next_action(b, row, start + timedelta(days=91)))
        self.assertEqual(len(self.tracker.history("spokeo")), 3)

    def test_deletion_email(self):
        msg = core.deletion_email(core.get_broker("mylife", self.brokers), self.profile, "https://l")
        self.assertEqual(msg["To"], "privacy@mylife.com")
        body = msg.get_content()
        self.assertIn("Jane Doe", body)
        self.assertIn("1 Main St, San Jose, CA 95110", body)
        self.assertIn("https://l", body)

    def test_report_and_cli(self):
        core.save_profile(self.profile)
        self.assertEqual(main(["mark", "spokeo", "found"]), 0)
        self.assertEqual(main(["optout", "mylife"]), 0)
        self.assertTrue(any((core.data_dir() / "outbox").iterdir()))
        out = os.path.join(self.tmp.name, "r.html")
        main(["report", "-o", out])
        with open(out) as f:
            html = f.read()
        self.assertIn("Jane Doe", html)
        self.assertIn("pill found", html)


if __name__ == "__main__":
    unittest.main()
