"""Week-11 eval: independent structured ground truth over the committed 504-item CSV.
Parsed with Python's csv module (independent of the app's Kotlin importer). Mirrors the app's
DOCUMENTED matching contract so the on-device run is scored against the intended answer, and also
reports the franchise-tag-only count to expose any over/under-inclusion.
"""
import csv, re
from collections import Counter

PATH = "seed-data/collectibles_2026-07-03.csv"
STOPWORDS = {  # copied from CollectibleRepositoryImpl.SEARCH_STOPWORDS
    "do","does","did","have","has","had","the","any","some","all","pop","pops","funko","funkos",
    "vinyl","figure","figures","collectible","collectibles","item","items","how","many","much","what",
    "whats","which","who","that","this","you","your","for","with","from","own","get","got","are","was",
    "were","and","but","most","valuable","worth","value","expensive","cost","costs","priciest","top",
    "best","highest","lowest","rare","rarest","cheap","cheapest","list","show","tell","about",
}

def salient_terms(q):
    return [t for t in re.split(r"[^a-z0-9]+", q.lower()) if len(t) >= 3 and t not in STOPWORDS]

rows = []
with open(PATH, newline="", encoding="utf-8") as f:
    for r in csv.DictReader(f):
        rows.append(r)

def cents(r):
    try: return round(float(r["Estimated Value"]) * 100)
    except: return 0
def qty(r):
    try: return int(r["Quantity"] or 1)
    except: return 1
def haystack(r):  # the app's 4 matched fields: name, brand, listName, series
    return " ".join([r.get("Name",""), r.get("Brand",""), r.get("List Name",""), r.get("Series","")]).lower()

def match_contract(subject):
    """App's documented matching: AND across salient terms, each LIKE %term% over the 4 fields."""
    terms = salient_terms(subject)
    if not terms: return []
    return [r for r in rows if all(t in haystack(r) for t in terms)]

def series_contains(frag):
    return [r for r in rows if frag.lower() in r.get("Series","").lower()]

print(f"TOTAL rows: {len(rows)}   (expected 504)")
print(f"TOTAL copies (sum qty): {sum(qty(r) for r in rows)}\n")

# --- NFT: Exclusive To == 'NFT Redeemable' (exact, case-insensitive) ---
nft = [r for r in rows if r.get("Exclusive To","").strip().lower() == "nft redeemable"]
print(f"NFT redeemable (Exclusive To == 'NFT Redeemable'): {len(nft)}  "
      f"(copies {sum(qty(r) for r in nft)}, value ${sum(cents(r)*qty(r) for r in nft)//100:,})")

# --- Franchise / subject counts: contract vs franchise-tag ---
print("\nSubject counts  [contract = app's 4-field AND-LIKE] vs [series-tag only]:")
for label, subject, tag in [
    ("Marvel","marvel","marvel"),
    ("Star Wars","star wars","star wars"),
    ("Game of Thrones","game of thrones","game of thrones"),
    ("Avatar","avatar","avatar"),
    ("horror","horror","horror"),
]:
    c = match_contract(subject); t = series_contains(tag)
    cval = sum(cents(r)*qty(r) for r in c)
    print(f"  {label:16} contract={len(c):3}  (copies {sum(qty(r) for r in c):3}, ${cval//100:>7,})   "
          f"series-tag={len(t):3}   terms={salient_terms(subject)}")

# --- Most valuable (app orders by estimatedValueCents DESC) ---
top = sorted(rows, key=cents, reverse=True)[:5]
print("\nMost valuable (by Estimated Value):")
for r in top:
    print(f"  ${cents(r)//100:>6,}  {r['Name']}  [{r.get('Exclusive To','')}]")

# --- Added in year (Date Added To Collectible) ---
def year_of(r):
    m = re.search(r"(20\d\d)", r.get("Date Added To Collectible",""))
    return int(m.group(1)) if m else None
years = Counter(year_of(r) for r in rows)
print("\nAdded-in-year (Date Added To Collectible):")
for y in sorted(k for k in years if k):
    print(f"  {y}: {years[y]}")
if years.get(None): print(f"  (no/failed date: {years[None]})")
