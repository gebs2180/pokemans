"""Writes a hand-made PokeTrace pilot folder with made-up cards and prices, laid out exactly as
poketrace.py saves real pulls. Used by the backend tests (PokeTraceDailySourceTest) and to try
OfferSanityRunner without a key:

    python make_sample.py ../../backend/app/src/test/resources/poketrace-sample --end 2026-10-24

Nothing in it is real PokeTrace data: card names start with "Sample" and ids are made up.
"""

import argparse
import datetime as dt
import json
import random
from pathlib import Path

# name, set, number, released, PSA 10 price, sales per grade over 90 days, grades sold
CARDS = [
    ("5a000000-0000-4000-8000-00000000000a", "Sample Deep Umbreon", "Sample Skies", "1/100", "2021-08-27", 900, 14, range(5, 11)),
    ("5a000000-0000-4000-8000-00000000000b", "Sample Deep Pikachu", "Sample Skies", "2/100", "2021-08-27", 300, 12, range(5, 11)),
    ("5a000000-0000-4000-8000-00000000000c", "Sample Deep Lugia", "Sample Tempest", "3/100", "2022-11-11", 600, 11, range(5, 11)),
    ("5a000000-0000-4000-8000-00000000000d", "Sample Thin Mew", "Sample Tempest", "4/100", "2022-11-11", 500, 2, (9, 10)),
]
RATIO = {10: 1.0, 9: 0.55, 8: 0.42, 7: 0.32, 6: 0.24, 5: 0.18}
RAW = {"NEAR_MINT": 0.30, "LIGHTLY_PLAYED": 0.21, "DAMAGED": 0.06}


def rows(rng, end, top, per_tier, share, days=90):
    """Daily rows: per_tier sales scattered over the window, grouped by day, low/high/avg."""
    by_day = {}
    for _ in range(per_tier):
        day = end - dt.timedelta(days=rng.randrange(days))
        by_day.setdefault(day, []).append(round(top * share * rng.uniform(0.9, 1.1), 2))
    out = []
    for day in sorted(by_day):
        p = by_day[day]
        out.append({"date": day.isoformat(), "source": "ebay", "avg": round(sum(p) / len(p), 2), "low": min(p), "high": max(p),
                    "saleCount": len(p), "approxSaleCount": False})
    return out


def write(path, url, body):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps({"request": url, "fetchedAt": "2026-10-24T12:00:00+00:00", "response": body}, indent=1),
                    encoding="utf-8")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("out")
    parser.add_argument("--end", default=dt.date.today().isoformat())
    args = parser.parse_args()
    out, end = Path(args.out), dt.date.fromisoformat(args.end)
    rng = random.Random(20261024)
    pull = out / "pulls" / end.isoformat()
    lines = ["poketrace_id,group,name,set,number,released,rarity,pt_name,pt_set,pt_number,pt_variant,raw_nm_avg,why"]
    for cid, name, set_name, number, released, top, per_tier, grades in CARDS:
        tiers = {f"PSA_{g}": rows(rng, end, top, per_tier, RATIO[g]) for g in grades}
        if per_tier > 2:
            tiers.update({t: rows(rng, end, top, per_tier, s) for t, s in RAW.items()})
            # Kept apart from PSA (step 3), and never mixed into it.
            tiers["CGC_10"] = rows(rng, end, top * 0.8, 3, 1.0)
        base = pull / "cards" / cid
        detail = {"data": {"id": cid, "name": name, "cardNumber": number, "set": {"slug": "sample", "name": set_name},
                           "variant": "Holofoil", "currency": "USD", "market": "US", "game": "pokemon",
                           "gradedOptions": [t for t in tiers if "_" in t and t[0:3] in ("PSA", "CGC")],
                           "conditionOptions": [t for t in tiers if t in RAW], "prices": {}, "totalSaleCount": per_tier * len(tiers)}}
        write(base / "detail.json", f"https://api.poketrace.com/v1/cards/{cid}", detail)
        for tier, data in tiers.items():
            if tier == "PSA_9" and data:
                # Rows the source must ignore: EUR listings, and a TCGplayer row on a graded tier.
                data = data + [{"date": data[-1]["date"], "source": "cardmarket_unsold", "avg": 1, "low": 1, "high": 1, "saleCount": 9},
                               {"date": data[-1]["date"], "source": "tcgplayer", "avg": 1, "low": 1, "high": 1, "saleCount": 9}]
            half = len(data) // 2 or 1
            pages = [data[:half], data[half:]] if len(data) > 1 else [data]
            for i, page in enumerate(pages, 1):
                more = i < len(pages)
                write(base / "history" / tier / f"all-p{i:03d}.json",
                      f"https://api.poketrace.com/v1/cards/{cid}/prices/{tier}/history?period=all&limit=50",
                      {"data": page, "pagination": {"hasMore": more, "nextCursor": f"c{i + 1}" if more else None}})
        lines.append(f"{cid},SWSH,{name},{set_name},{number.split('/')[0]},{released},Rare Secret,{name},{set_name},{number},"
                     f"Holofoil,{round(top * RAW['NEAR_MINT'], 2)},sample")
    (out / "manifest.csv").write_text("\n".join(lines) + "\n", encoding="utf-8")
    print(f"sample written to {out}")


if __name__ == "__main__":
    main()
