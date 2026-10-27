"""Offline tests for poketrace.py against hand-made responses: python -m unittest test_poketrace"""

import csv
import json
import os
import tempfile
import unittest
import urllib.parse
from pathlib import Path

import poketrace

CHARIZARD = "11111111-1111-4111-8111-111111111111"
CHARIZARD_1ST = "22222222-2222-4222-8222-222222222222"
UMBREON = "33333333-3333-4333-8333-333333333333"


def card(cid, name, set_name, number, variant="Holofoil", prices=None):
    return {"id": cid, "name": name, "cardNumber": number, "set": {"slug": "x", "name": set_name}, "variant": variant,
            "productType": "single", "game": "pokemon", "market": "US", "currency": "USD", "prices": prices or {}}


def detail(c, graded, conditions, prices):
    d = dict(c, gradedOptions=graded, conditionOptions=conditions, prices=prices, totalSaleCount=120)
    return {"data": d}


class FakeApi:
    """Answers like PokeTrace: card search, card detail, and two pages of PSA_9 history."""

    def __init__(self):
        self.calls = []
        self.throttled = 1

    def __call__(self, url, headers):
        assert headers["X-API-Key"] == "test-key"
        self.calls.append(url)
        parsed = urllib.parse.urlparse(url)
        path, query = parsed.path.replace("/v1", ""), urllib.parse.parse_qs(parsed.query)
        if path == "/cards" and self.throttled:
            self.throttled -= 1
            return 429, "slow down", "0"
        if path == "/cards":
            name = query["search"][0]
            results = {
                "Charizard": [card(CHARIZARD, "Charizard", "Base Set", "4/102",
                                   prices={"ebay": {"PSA_9": {"avg": 3600, "saleCount": 12}}}),
                              card(CHARIZARD_1ST, "Charizard", "Base Set", "4/102", variant="1st Edition Holofoil"),
                              card("44444444-4444-4444-8444-444444444444", "Charizard", "Evolutions", "11/108")],
                "Umbreon VMAX": [card(UMBREON, "Umbreon VMAX", "Evolving Skies", "215/203")],
            }.get(name, [])
            return 200, json.dumps({"data": results, "pagination": {"hasMore": False}}), None
        if path == f"/cards/{CHARIZARD}":
            return 200, json.dumps(detail(card(CHARIZARD, "Charizard", "Base Set", "4/102"), ["PSA_9", "PSA_7", "CGC_10", "BGS_10"],
                                          ["NEAR_MINT", "SEALED"],
                                          {"ebay": {"PSA_9": {"avg": 3600, "saleCount": 12}, "PSA_7": {"avg": 700, "saleCount": 20}},
                                           "tcgplayer": {"NEAR_MINT": {"avg": 420, "saleCount": 30}}})), None
        if path == f"/cards/{UMBREON}":
            return 200, json.dumps(detail(card(UMBREON, "Umbreon VMAX", "Evolving Skies", "215/203"), ["PSA_9"], [],
                                          {"ebay": {"PSA_9": {"avg": 2000, "saleCount": 15}},
                                           "tcgplayer": {"NEAR_MINT": {"avg": 1400, "saleCount": 40}}})), None
        if path.endswith("/history"):
            tier = path.split("/")[-2]
            if query.get("cursor") == ["page2"]:
                rows = [{"date": "2026-10-19", "source": "ebay", "avg": 1950, "low": 1875, "high": 2025, "saleCount": 2},
                        {"date": "2026-10-20", "source": "ebay", "avg": 2050, "low": 2050, "high": 2050, "saleCount": 1}]
                return 200, json.dumps({"data": rows, "pagination": {"hasMore": False}}), None
            rows = [{"date": "2026-10-01", "source": "ebay", "avg": 3500, "low": 3400, "high": 3600, "saleCount": 2,
                     "tier": tier}]
            return 200, json.dumps({"data": rows, "pagination": {"hasMore": True, "nextCursor": "page2"}}), None
        return 404, "not found", None


class PullerTest(unittest.TestCase):

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = Path(self.tmp.name)
        self.api = FakeApi()
        self.sleeps = []
        self.client = poketrace.Client("test-key", transport=self.api, sleep=self.sleeps.append, clock=lambda: 0.0)
        self.candidates = self.root / "candidates.csv"
        self.candidates.write_text("group,name,set,number,released,rarity\n"
                                   "WOTC,Charizard,Base Set,4,1999-01-09,Rare Holo\n"
                                   "SWSH,Umbreon VMAX,Evolving Skies,215,2021-08-27,Rare Secret\n"
                                   "SV,Nobody,Nowhere,,2023-01-01,Common\n", encoding="utf-8")
        self.here = poketrace.HERE
        poketrace.HERE = self.root

    def tearDown(self):
        poketrace.HERE = self.here
        self.tmp.cleanup()

    def rows(self, name):
        with open(self.root / name, newline="", encoding="utf-8") as f:
            return list(csv.DictReader(f))

    def test_find_picks_the_unlimited_card_and_keeps_every_response(self):
        poketrace.find(self.client, self.root, "2026-10-27")
        found = {r["name"]: r for r in self.rows("candidates-found.csv")}
        self.assertEqual(found["Charizard"]["poketrace_id"], CHARIZARD)  # not the 1st Edition, not Evolutions
        self.assertIn(CHARIZARD_1ST, found["Charizard"]["alternatives"])
        self.assertEqual(found["Charizard"]["raw_nm_avg"], "420")
        self.assertEqual(found["Charizard"]["psa7_sales"], "20")
        self.assertEqual(found["Nobody"]["note"], "no match")
        saved = json.loads((self.root / "pulls/2026-10-27/cards" / CHARIZARD / "detail.json").read_text(encoding="utf-8"))
        self.assertIn("/cards/" + CHARIZARD, saved["request"])
        self.assertIn("fetchedAt", saved)
        # One 429 was retried; the rate limit spaced every request after the first.
        self.assertEqual(self.api.calls.count(self.api.calls[0]), 2)
        self.assertTrue(all(s >= 0 for s in self.sleeps))

        # Running again fetches nothing: everything is on disk.
        before = len(self.api.calls)
        poketrace.find(self.client, self.root, "2026-10-27")
        self.assertEqual(len(self.api.calls), before)

    def test_shortlist_history_and_check(self):
        poketrace.find(self.client, self.root, "2026-10-27")
        poketrace.shortlist(self.root)
        chosen = {r["name"] for r in self.rows("manifest.csv")}
        self.assertEqual(chosen, {"Charizard"})  # forced in; Umbreon's raw $1,400 is over $500

        # Add Umbreon by hand, as the plan allows, then pull history.
        with open(self.root / "manifest.csv", "a", newline="", encoding="utf-8") as f:
            csv.writer(f).writerow([UMBREON, "SWSH", "Umbreon VMAX", "Evolving Skies", "215", "2021-08-27", "Rare Secret",
                                    "Umbreon VMAX", "Evolving Skies", "215/203", "Holofoil", "1400", "by hand"])
        poketrace.history(self.client, self.root, "2026-10-27")
        tiers = sorted(p.name for p in (self.root / "pulls/2026-10-27/cards" / CHARIZARD / "history").iterdir())
        self.assertEqual(tiers, ["CGC_10", "NEAR_MINT", "PSA_7", "PSA_9"])  # BGS_10 and SEALED aren't pulled
        pages = sorted(p.name for p in (self.root / "pulls/2026-10-27/cards" / UMBREON / "history/PSA_9").iterdir())
        self.assertEqual(pages, ["all-p001.json", "all-p002.json"])

        # Resuming fetches nothing more.
        before = len(self.api.calls)
        poketrace.history(self.client, self.root, "2026-10-27")
        self.assertEqual(len(self.api.calls), before)

        comps = self.root / "comps.csv"
        comps.write_text("card,set,number,grader,grade,price,sold_on,listing_type,marketplace,url\n"
                         "Umbreon VMAX,Evolving Skies,215/203,PSA,9,2050.0,2026-10-20,UNKNOWN,eBay,\n"
                         "Umbreon VMAX,Evolving Skies,215/203,PSA,9,1875.0,2026-10-19,UNKNOWN,eBay,\n"
                         "Umbreon VMAX,Evolving Skies,215/203,PSA,10,3800.0,2026-10-20,UNKNOWN,eBay,\n", encoding="utf-8")
        poketrace.check(self.root, comps, today="2026-10-27")
        report = (self.root / "check.md").read_text(encoding="utf-8")
        self.assertIn("**2 of 3 known sales fall inside PokeTrace's range for that day.**", report)
        self.assertIn("Rows look like daily sales.", report)
        self.assertIn("| Umbreon VMAX (Evolving Skies) | PSA_9 | 2026-10-01 | 2026-10-20 | 3 | 5 |", report)

    def test_number_key(self):
        self.assertEqual(poketrace.number_key("004/102"), "4")
        self.assertEqual(poketrace.number_key("SV49/SV94"), "sv49")
        self.assertEqual(poketrace.number_key("0"), "0")


if __name__ == "__main__":
    unittest.main()
