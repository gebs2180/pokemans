"""PokeTrace Pro pilot puller (docs/poketrace-pilot.md).

Pulls sold-price data for the baseline cards and keeps every response verbatim, outside the
repository: the repo is public and PokeTrace's terms forbid redistributing its data.

    python poketrace.py find        # candidates.csv -> PokeTrace IDs and current prices
    python poketrace.py shortlist   # the ~30 baseline cards -> manifest.csv
    python poketrace.py history     # daily per-tier history for every manifest card
    python poketrace.py snapshot    # current prices for every manifest card (weekly)
    python poketrace.py check --comps <comps.csv>   # day-1 checks: depth, PSA 5-7, cross-check

Environment: POKETRACE_API_KEY (required for pulls), POKETRACE_DATA_DIR (default
~/pokemans-data/poketrace), POKETRACE_BASE_URL (default https://api.poketrace.com/v1).
Standard library only. Rate-limited to PokeTrace Pro's 30 requests per 10 seconds, and
resumable: a page already saved is never fetched again.
"""

import argparse
import csv
import datetime as dt
import json
import os
import re
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

BASE_URL = os.environ.get("POKETRACE_BASE_URL", "https://api.poketrace.com/v1")
HERE = Path(__file__).resolve().parent
PAGE_LIMIT = 50
# Pro: 30 requests per 10 seconds. A little under, so a burst never trips it.
MIN_INTERVAL = 10.0 / 28
RAW_TIERS = ("NEAR_MINT", "LIGHTLY_PLAYED", "MODERATELY_PLAYED", "HEAVILY_PLAYED", "DAMAGED")
# Other graders' top tiers, kept for later; never priced into PSA (CLAUDE.md step 3).
EXTRA_TIERS = ("CGC_10", "BGS_9_5", "BGS_9.5")
# Variants that are a different card for us (edition, print run, reverse holo, stamps).
OTHER_VARIANTS = re.compile(r"1st|first edition|shadowless|reverse|staff|prerelease|stamp|error|jumbo", re.I)
PRICE_BANDS = ((25, 75), (75, 200), (200, 500))


def data_dir():
    return Path(os.environ.get("POKETRACE_DATA_DIR", Path.home() / "pokemans-data" / "poketrace"))


class Client:
    """GETs JSON from PokeTrace with the key, the rate limit and retries on 429 and 5xx."""

    def __init__(self, key, transport=None, sleep=time.sleep, clock=time.monotonic):
        self.key = key
        self.transport = transport or self._urlopen
        self.sleep = sleep
        self.clock = clock
        self.last = None
        self.requests = 0

    def url(self, path, params=None):
        query = urllib.parse.urlencode({k: v for k, v in (params or {}).items() if v not in (None, "")})
        return BASE_URL + path + ("?" + query if query else "")

    def get(self, path, params=None):
        url = self.url(path, params)
        for attempt in range(6):
            if self.last is not None:
                wait = MIN_INTERVAL - (self.clock() - self.last)
                if wait > 0:
                    self.sleep(wait)
            self.last = self.clock()
            self.requests += 1
            status, body, retry_after = self.transport(url, {"X-API-Key": self.key, "Accept": "application/json"})
            if status == 200:
                return url, json.loads(body)
            if status == 404:
                return url, None
            if status == 429 or status >= 500:
                self.sleep(float(retry_after) if retry_after else min(60, 2 ** attempt * 2))
                continue
            raise RuntimeError(f"{status} from {url}: {body[:300]}")
        raise RuntimeError(f"gave up on {url} after retries")

    @staticmethod
    def _urlopen(url, headers):
        request = urllib.request.Request(url, headers=headers)
        try:
            with urllib.request.urlopen(request, timeout=60) as r:
                return r.status, r.read().decode("utf-8"), r.headers.get("Retry-After")
        except urllib.error.HTTPError as e:
            return e.code, e.read().decode("utf-8", "replace"), e.headers.get("Retry-After")


def save(path, url, body):
    """Writes one response verbatim, with the request URL and when it was fetched."""
    path.parent.mkdir(parents=True, exist_ok=True)
    record = {"request": url, "fetchedAt": dt.datetime.now(dt.timezone.utc).isoformat(), "response": body}
    tmp = path.with_suffix(".tmp")
    tmp.write_text(json.dumps(record, indent=1, ensure_ascii=False), encoding="utf-8")
    tmp.replace(path)


def load(path):
    return json.loads(path.read_text(encoding="utf-8"))["response"]


def norm(s):
    return re.sub(r"[^a-z0-9]", "", (s or "").lower())


def number_key(n):
    """'004/102', '4' and '4/102' are all '4'; 'SV49/SV94' is 'sv49'."""
    head = (n or "").split("/")[0].strip().lower()
    return re.sub(r"^0+(?=.)", "", head)


def slug(*parts):
    return re.sub(r"[^a-z0-9]+", "-", " ".join(parts).lower()).strip("-")


def sales(tier_price):
    return (tier_price or {}).get("saleCount") or 0


def tier_prices(detail):
    """All tiers of a card detail, merged across sources: eBay first, then TCGplayer for raw."""
    merged = {}
    for source in ("ebay", "tcgplayer"):
        for tier, p in ((detail or {}).get("prices") or {}).get(source, {}).items():
            merged.setdefault(tier, p)
    return merged


def pick(candidate, results):
    """The search result that is this candidate's card, the other plausible ones, and a note."""
    want_set, want_name, want_number = norm(candidate["set"]), norm(candidate["name"]), number_key(candidate["number"])
    matches = []
    for card in results:
        set_name = norm((card.get("set") or {}).get("name"))
        if not set_name or not (want_set in set_name or set_name in want_set):
            continue
        if want_number and number_key(card.get("cardNumber")) != want_number:
            continue
        if not want_number and norm(card.get("name")) != want_name:
            continue
        if card.get("productType", "single") != "single":
            continue
        matches.append(card)
    if not matches:
        return None, [], "no match"
    plain = [c for c in matches if not OTHER_VARIANTS.search(c.get("variant") or "")] or matches

    def total_sales(c):
        return sum(sales(p) for tiers in (c.get("prices") or {}).values() for p in tiers.values())

    def top_price(c):
        return c.get("topPrice") or max((p.get("avg") or 0 for tiers in (c.get("prices") or {}).values() for p in tiers.values()),
                                        default=0)

    # With no number given (e.g. "the SIR"), the chase version is the most expensive; else the most traded.
    plain.sort(key=top_price if not want_number else total_sales, reverse=True)
    note = "" if len(plain) == 1 else f"picked 1 of {len(plain)}: check"
    if not want_number:
        note = (note + "; " if note else "") + "number not given, picked the most expensive"
    return plain[0], [c for c in matches if c is not plain[0]], note


def find(client, root, today):
    """Finds every candidate at PokeTrace and saves its current prices; writes candidates-found.csv."""
    rows = []
    with open(HERE / "candidates.csv", newline="", encoding="utf-8") as f:
        candidates = list(csv.DictReader(f))
    for c in candidates:
        search_file = root / "search" / f"{slug(c['name'], c['set'], c['number'])}.json"
        if search_file.exists():
            results = load(search_file)
        else:
            url, results = client.get("/cards", {"search": c["name"], "game": "pokemon", "market": "US", "limit": 100})
            save(search_file, url, results)
        card, others, note = pick(c, (results or {}).get("data") or [])
        row = dict(c, poketrace_id="", pt_name="", pt_set="", pt_number="", pt_variant="", alternatives="", note=note)
        if card:
            detail_file = root / "pulls" / today / "cards" / card["id"] / "detail.json"
            if detail_file.exists():
                detail = load(detail_file)
            else:
                url, detail = client.get(f"/cards/{card['id']}")
                save(detail_file, url, detail)
            d = (detail or {}).get("data") or {}
            row.update(poketrace_id=card["id"], pt_name=card.get("name"), pt_set=(card.get("set") or {}).get("name"),
                       pt_number=card.get("cardNumber"), pt_variant=card.get("variant"),
                       alternatives=" ".join(f"{o['id']}:{o.get('cardNumber')}:{o.get('variant')}" for o in others))
            row.update(metrics(d))
        rows.append(row)
        print(f"{c['group']:<11} {c['name']} ({c['set']}): {row['poketrace_id'] or '-'} {row['note']}")
    write_csv(root / "candidates-found.csv", rows)
    print(f"\n{sum(1 for r in rows if r['poketrace_id'])} of {len(rows)} found -> {root / 'candidates-found.csv'}")


def metrics(detail):
    """Current figures used to choose cards: raw NM price and sales, PSA sales by grade, the totals."""
    tiers = tier_prices(detail)
    nm = tiers.get("NEAR_MINT") or {}
    out = {"currency": detail.get("currency", ""), "raw_nm_avg": nm.get("avg", ""), "raw_nm_sales": sales(nm),
           "total_sales": detail.get("totalSaleCount", ""),
           "graded_options": " ".join(detail.get("gradedOptions") or []),
           "condition_options": " ".join(detail.get("conditionOptions") or [])}
    for g in range(1, 11):
        out[f"psa{g}_sales"] = sales(tiers.get(f"PSA_{g}"))
    out["psa9_avg"] = (tiers.get("PSA_9") or {}).get("avg", "")
    return out


def shortlist(root, per_group=5, must=("Charizard|Base Set",)):
    """
    Picks the baseline set from candidates-found.csv: raw NM between $25 and $500 (plus the cards
    in `must`), ranked by PSA 5-10 sales, one per price band first, then the busiest, per group.
    """
    with open(root / "candidates-found.csv", newline="", encoding="utf-8") as f:
        found = [r for r in csv.DictReader(f) if r["poketrace_id"]]

    def raw(r):
        try:
            return float(r["raw_nm_avg"])
        except (TypeError, ValueError):
            return None

    def score(r):
        return sum(int(r.get(f"psa{g}_sales") or 0) for g in range(5, 11)) + int(r.get("raw_nm_sales") or 0)

    chosen = []
    for group in dict.fromkeys(r["group"] for r in found):
        rows = [r for r in found if r["group"] == group]
        forced = [r for r in rows if f"{r['name']}|{r['set']}" in must]
        eligible = [r for r in rows if r not in forced and raw(r) is not None and 25 <= raw(r) <= 500 and score(r) > 0]
        eligible.sort(key=score, reverse=True)
        picked = list(forced)
        for low, high in PRICE_BANDS:
            band = [r for r in eligible if low <= raw(r) < high and r not in picked]
            if band and len(picked) < per_group:
                picked.append(band[0])
        picked += [r for r in eligible if r not in picked][: per_group - len(picked)]
        for r in picked:
            r["why"] = "must include" if r in forced else f"raw NM ${raw(r):.0f}, PSA 5-10 + raw sales {score(r)}"
        chosen += picked
        if len(picked) < 3:
            print(f"warning: {group} has {len(picked)} cards; an era step-down ratio needs 3")
    columns = ["poketrace_id", "group", "name", "set", "number", "released", "rarity", "pt_name", "pt_set", "pt_number",
               "pt_variant", "raw_nm_avg", "why"]
    write_csv(root / "manifest.csv", [{k: r.get(k, "") for k in columns} for r in chosen])
    print(f"{len(chosen)} cards -> {root / 'manifest.csv'} (edit it freely: history reads whatever is there)")


def manifest(root):
    with open(root / "manifest.csv", newline="", encoding="utf-8") as f:
        return [r for r in csv.DictReader(f) if r["poketrace_id"]]


def history_tiers(detail):
    graded = detail.get("gradedOptions") or []
    conditions = detail.get("conditionOptions") or []
    tiers = [t for t in graded if re.fullmatch(r"PSA_\d+", t)]
    tiers += [t for t in conditions if t in RAW_TIERS]
    tiers += [t for t in graded if t in EXTRA_TIERS]
    return list(dict.fromkeys(tiers))


def history(client, root, today, period="all"):
    """Every page of daily history, per tier, for every manifest card. Resumes where it stopped."""
    for card in manifest(root):
        cid = card["poketrace_id"]
        base = root / "pulls" / today / "cards" / cid
        detail_file = base / "detail.json"
        if not detail_file.exists():
            url, body = client.get(f"/cards/{cid}")
            save(detail_file, url, body)
        detail = (load(detail_file) or {}).get("data") or {}
        for tier in history_tiers(detail):
            page, cursor = 1, None
            while True:
                page_file = base / "history" / tier / f"{period}-p{page:03d}.json"
                if page_file.exists():
                    body = load(page_file)
                else:
                    url, body = client.get(f"/cards/{cid}/prices/{urllib.parse.quote(tier)}/history",
                                           {"period": period, "limit": PAGE_LIMIT, "cursor": cursor})
                    save(page_file, url, body)
                pagination = (body or {}).get("pagination") or {}
                if not pagination.get("hasMore") or not pagination.get("nextCursor"):
                    break
                cursor, page = pagination["nextCursor"], page + 1
        print(f"{card['name']} ({card['set']}): {len(history_tiers(detail))} tiers")
    print(f"{client.requests} requests")


def snapshot(client, root, today):
    for card in manifest(root):
        detail_file = root / "pulls" / today / "cards" / card["poketrace_id"] / "detail.json"
        if not detail_file.exists():
            url, body = client.get(f"/cards/{card['poketrace_id']}")
            save(detail_file, url, body)
    print(f"{client.requests} requests")


def history_rows(root, cid):
    """Latest pull's daily rows per (tier, source, date) for one card."""
    rows = {}
    for pull in sorted((root / "pulls").glob("*")):
        for page in sorted((pull / "cards" / cid / "history").glob("*/*.json")):
            tier = page.parent.name
            for r in (load(page) or {}).get("data") or []:
                rows[(tier, r.get("source"), r.get("date"))] = r
    return rows


def check(root, comps_csv=None, today=None):
    """
    Day-1 checks before relying on the data: how far back each tier goes, whether PSA 5-7 trade,
    whether the rows are daily sales (not rolling windows), and whether sales we know about show up.
    """
    today = dt.date.fromisoformat(today) if today else dt.date.today()
    since = (today - dt.timedelta(days=90)).isoformat()
    lines = ["# PokeTrace day-1 check", "", "| Card | Tier | First day | Last day | Days with sales | Sales, last 90 days |",
             "|---|---|---|---|---|---|"]
    repeats = total = 0
    by_card = {}
    for card in manifest(root):
        rows = history_rows(root, card["poketrace_id"])
        by_card[card["poketrace_id"]] = rows
        for tier in sorted({t for t, _, _ in rows}, key=tier_order):
            days = sorted((d, r) for (t, s, d), r in rows.items() if t == tier and d)
            sold = [(d, r) for d, r in days if (r.get("saleCount") or 0) > 0]
            recent = sum(r.get("saleCount") or 0 for d, r in sold if d >= since)
            lines.append(f"| {card['name']} ({card['set']}) | {tier} | {days[0][0] if days else ''} | "
                         f"{days[-1][0] if days else ''} | {len(sold)} | {recent} |")
            for (d0, a), (d1, b) in zip(sold, sold[1:]):
                total += 1
                if a.get("saleCount") == b.get("saleCount") and a.get("avg") == b.get("avg"):
                    repeats += 1
    share = repeats / total if total else 0
    lines += ["", f"**Daily sales or rolling windows?** {repeats} of {total} consecutive sale days repeat the previous "
                  f"day's count and average exactly ({share:.0%}). "
              + ("Rows look like daily sales." if share < 0.2 else "**Rows look like rolling windows: stop and ask PokeTrace "
                                                                    "before importing (docs/poketrace-pilot.md).**")]
    if comps_csv:
        lines += ["", "## Cross-check against known sales", "", "| Card | Grade | Sold | Price | PokeTrace that day | In range |",
                  "|---|---|---|---|---|---|"]
        found = known = 0
        with open(comps_csv, newline="", encoding="utf-8") as f:
            comps = list(csv.DictReader(f))
        for comp in comps:
            card = next((c for c in manifest(root) if norm(c["name"]) == norm(comp["card"])
                         and number_key(c["number"] or c["pt_number"]) == number_key(comp["number"])), None)
            if not card or comp.get("grader") != "PSA":
                continue
            known += 1
            r = by_card[card["poketrace_id"]].get((f"PSA_{comp['grade']}", "ebay", comp["sold_on"]))
            price = float(comp["price"])
            ok = r is not None and (r.get("low") or 0) - 0.01 <= price <= (r.get("high") or 0) + 0.01
            found += ok
            seen = "none" if r is None else f"{r.get('saleCount')} sold, ${r.get('low')}-${r.get('high')}"
            lines.append(f"| {comp['card']} | PSA {comp['grade']} | {comp['sold_on']} | ${price:,.2f} | {seen} | {'yes' if ok else 'no'} |")
        lines += ["", f"**{found} of {known} known sales fall inside PokeTrace's range for that day.**"]
    report = "\n".join(lines) + "\n"
    out = root / "check.md"
    out.write_text(report, encoding="utf-8")
    print(report)
    print(f"-> {out}")


def tier_order(tier):
    m = re.fullmatch(r"PSA_(\d+)", tier)
    return (0, -int(m.group(1))) if m else (1, tier)


def write_csv(path, rows):
    path.parent.mkdir(parents=True, exist_ok=True)
    columns = list(dict.fromkeys(k for r in rows for k in r))
    with open(path, "w", newline="", encoding="utf-8") as f:
        w = csv.DictWriter(f, fieldnames=columns)
        w.writeheader()
        w.writerows(rows)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("command", choices=["find", "shortlist", "history", "snapshot", "check"])
    parser.add_argument("--pull-date", default=dt.date.today().isoformat(),
                        help="folder to write into; pass an earlier date to resume that pull")
    parser.add_argument("--period", default="all", choices=["7d", "30d", "90d", "1y", "all"])
    parser.add_argument("--comps", help="check: a comps CSV (comps.csv columns) to cross-check")
    args = parser.parse_args(argv)
    root = data_dir()
    if args.command == "shortlist":
        return shortlist(root)
    if args.command == "check":
        return check(root, args.comps)
    key = os.environ.get("POKETRACE_API_KEY")
    if not key:
        sys.exit("Set POKETRACE_API_KEY (PokeTrace dashboard) first.")
    client = Client(key)
    if args.command == "find":
        find(client, root, args.pull_date)
    elif args.command == "history":
        history(client, root, args.pull_date, args.period)
    else:
        snapshot(client, root, args.pull_date)


if __name__ == "__main__":
    main()
