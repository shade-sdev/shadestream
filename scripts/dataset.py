import gzip, csv, json, urllib.request, io, os, hashlib
from datetime import datetime

IMDB_BASE      = "https://datasets.imdbws.com"
CURRENT_YEAR   = datetime.now().year
SCHEMA_VERSION = "1.0"
ITEMS_PER_PAGE = 2000
OUT_DIR        = "catalog"

BASE_URL = "https://raw.githubusercontent.com/shadestream/shadestream/master/catalog"

GENRES = [
    "Action", "Comedy", "Drama", "Horror", "Thriller",
    "Romance", "Sci-Fi", "Animation", "Documentary",
    "Crime", "Fantasy", "Adventure", "Mystery", "Biography",
]

MIN_VOTES        = 100
MIN_VOTES_RECENT = 10

def fetch_tsv(filename):
    print(f"  Fetching {filename}...")
    with urllib.request.urlopen(f"{IMDB_BASE}/{filename}") as r:
        with gzip.open(r) as f:
            reader = csv.DictReader(io.TextIOWrapper(f, encoding="utf-8"), delimiter="\t")
            return list(reader)

def sha256_of_file(path: str) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(65536), b""):
            h.update(chunk)
    return h.hexdigest()

def write_json(path: str, data) -> str:
    """Write JSON and return its sha256."""
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8") as f:
        json.dump(data, f, separators=(",", ":"), ensure_ascii=False)
    return sha256_of_file(path)

def clean_item(item: dict) -> dict:
    """Strip internal sorting fields before writing."""
    copy = dict(item)
    copy.pop("year",  None)
    copy.pop("votes", None)
    return copy

def sort_popular(items):
    return sorted(items, key=lambda x: x["votes"], reverse=True)

def sort_top_rated(items, min_votes=1_000):
    return sorted(
        [x for x in items if x["votes"] >= min_votes],
        key=lambda x: float(x["imdbRating"]),
        reverse=True,
    )

def sort_latest(items):
    return sorted(
        [x for x in items if x["year"] is not None],
        key=lambda x: (x["year"], x["votes"]),
        reverse=True,
    )

def sort_trending(items):
    recent = [x for x in items if x["year"] is not None and x["year"] >= CURRENT_YEAR - 2]
    return sorted(recent, key=lambda x: x["votes"], reverse=True)

def filter_genre(items, genre):
    return sorted(
        [x for x in items if genre in x["genres"]],
        key=lambda x: x["votes"],
        reverse=True,
    )

def write_catalog(catalog_id: str, sorted_items: list) -> dict:
    """
    Splits sorted_items into pages and writes:
      catalog/{catalog_id}/meta.json
      catalog/{catalog_id}/page_1.json
      catalog/{catalog_id}/page_2.json
      ...

    Returns a catalog descriptor for index.json.
    """
    total_items = len(sorted_items)
    total_pages = max(1, -(-total_items // ITEMS_PER_PAGE))
    catalog_dir = os.path.join(OUT_DIR, catalog_id)
    generated   = datetime.utcnow().strftime("%Y-%m-%dT%H:%M:%SZ")
    page_checksums = []

    for page_num in range(1, total_pages + 1):
        start  = (page_num - 1) * ITEMS_PER_PAGE
        end    = start + ITEMS_PER_PAGE
        chunk  = [clean_item(x) for x in sorted_items[start:end]]

        prev_url = f"{BASE_URL}/{catalog_id}/page_{page_num - 1}.json" if page_num > 1       else None
        next_url = f"{BASE_URL}/{catalog_id}/page_{page_num + 1}.json" if page_num < total_pages else None

        page_doc = {
            "schema":       SCHEMA_VERSION,
            "catalog":      catalog_id,
            "generatedAt":  generated,
            "page":         page_num,
            "totalPages":   total_pages,
            "totalItems":   total_items,
            "itemsPerPage": ITEMS_PER_PAGE,
            "itemsOnPage":  len(chunk),
            "hasNext":      page_num < total_pages,
            "hasPrev":      page_num > 1,
            "nextUrl":      next_url,
            "prevUrl":      prev_url,
            "items":        chunk,
        }

        page_path     = os.path.join(catalog_dir, f"page_{page_num}.json")
        checksum      = write_json(page_path, page_doc)
        page_checksums.append({
            "page":     page_num,
            "url":      f"{BASE_URL}/{catalog_id}/page_{page_num}.json",
            "items":    len(chunk),
            "sha256":   checksum,
        })

    meta = {
        "schema":       SCHEMA_VERSION,
        "catalog":      catalog_id,
        "generatedAt":  generated,
        "totalItems":   total_items,
        "totalPages":   total_pages,
        "itemsPerPage": ITEMS_PER_PAGE,
        "firstPageUrl": f"{BASE_URL}/{catalog_id}/page_1.json",
        "pages":        page_checksums,
    }
    meta_path = os.path.join(catalog_dir, "meta.json")
    write_json(meta_path, meta)

    print(f"  [{catalog_id}] {total_items} items → {total_pages} page(s)")
    return {
        "id":          catalog_id,
        "totalItems":  total_items,
        "totalPages":  total_pages,
        "metaUrl":     f"{BASE_URL}/{catalog_id}/meta.json",
        "firstPageUrl":f"{BASE_URL}/{catalog_id}/page_1.json",
    }

def write_search_index(movies: list, series: list):
    search_dir = os.path.join(OUT_DIR, "search")
    os.makedirs(search_dir, exist_ok=True)
    generated  = datetime.utcnow().strftime("%Y-%m-%dT%H:%M:%SZ")

    buckets: dict[str, list] = {c: [] for c in "abcdefghijklmnopqrstuvwxyz"}
    buckets["other"] = []

    for item in movies + series:
        first = item["name"][0].lower() if item["name"] else ""
        key   = first if first in buckets else "other"
        buckets[key].append([
            item["id"],
            item["name"],
            item.get("releaseInfo", ""),
            item["type"],
        ])

    bucket_index = []
    for key, entries in buckets.items():
        entries.sort(key=lambda x: x[1].lower())
        doc = {
            "schema":      SCHEMA_VERSION,
            "bucket":      key,
            "generatedAt": generated,
            "totalItems":  len(entries),
            # columns: [imdbId, name, year, type]
            "columns":     ["id", "name", "year", "type"],
            "items":       entries,
        }
        path     = os.path.join(search_dir, f"{key}.json")
        checksum = write_json(path, doc)
        bucket_index.append({
            "bucket":  key,
            "items":   len(entries),
            "url":     f"{BASE_URL}/search/{key}.json",
            "sha256":  checksum,
        })
        print(f"  [search/{key}] {len(entries)} entries")

    write_json(os.path.join(search_dir, "index.json"), {
        "schema":      SCHEMA_VERSION,
        "generatedAt": generated,
        "buckets":     bucket_index,
    })

print("Loading ratings...")
ratings = {}
for row in fetch_tsv("title.ratings.tsv.gz"):
    ratings[row["tconst"]] = (row["averageRating"], int(row["numVotes"]))

print("Loading titles...")
movies: list = []
series: list = []

for row in fetch_tsv("title.basics.tsv.gz"):
    tid = row["tconst"]
    if tid not in ratings:
        continue
    rating, votes = ratings[tid]

    raw_year = row["startYear"]
    year     = int(raw_year) if raw_year != "\\N" and raw_year.isdigit() else None
    genres   = row["genres"]  if row["genres"]    != "\\N" else ""

    is_recent = year is not None and year >= CURRENT_YEAR - 1
    if votes < (MIN_VOTES_RECENT if is_recent else MIN_VOTES):
        continue

    entry = {
        "id":          tid,
        "name":        row["primaryTitle"],
        "releaseInfo": str(year) if year else "",
        "year":        year,
        "imdbRating":  rating,
        "genres":      genres.split(",") if genres else [],
        "poster":      f"https://images.metahub.space/poster/small/{tid}/img",
        "votes":       votes,
        "type":        None,
    }

    if row["titleType"] == "movie":
        entry["type"] = "movie"
        movies.append(entry)
    elif row["titleType"] in ("tvSeries", "tvMiniSeries"):
        entry["type"] = "series"
        series.append(entry)

print(f"Found {len(movies)} movies, {len(series)} series\n")

os.makedirs(OUT_DIR, exist_ok=True)
generated_at = datetime.utcnow().strftime("%Y-%m-%dT%H:%M:%SZ")

print("Writing movie catalogs...")
movie_catalogs = [
    write_catalog("movie_popular",   sort_popular(movies)),
    write_catalog("movie_top_rated", sort_top_rated(movies)),
    write_catalog("movie_latest",    sort_latest(movies)),
    write_catalog("movie_trending",  sort_trending(movies)),
    *[write_catalog(f"movie_{g.lower()}", filter_genre(movies, g)) for g in GENRES],
]

print("\nWriting series catalogs...")
series_catalogs = [
    write_catalog("series_popular",   sort_popular(series)),
    write_catalog("series_top_rated", sort_top_rated(series)),
    write_catalog("series_latest",    sort_latest(series)),
    write_catalog("series_trending",  sort_trending(series)),
    *[write_catalog(f"series_{g.lower()}", filter_genre(series, g)) for g in GENRES],
]

print("\nWriting search index...")
write_search_index(movies, series)

print("\nWriting master index...")
index = {
    "schema":        SCHEMA_VERSION,
    "generatedAt":   generated_at,
    "itemsPerPage":  ITEMS_PER_PAGE,
    "baseUrl":       BASE_URL,
    "searchIndexUrl":f"{BASE_URL}/search/index.json",
    "movie":  movie_catalogs,
    "series": series_catalogs,
    "sections": [
        {"type": "movie",  "catalogId": "movie_popular",   "label": "Popular Movies"},
        {"type": "movie",  "catalogId": "movie_trending",  "label": "Trending Movies"},
        {"type": "movie",  "catalogId": "movie_latest",    "label": "Latest Movies"},
        {"type": "movie",  "catalogId": "movie_top_rated", "label": "Top Rated Movies"},
        {"type": "series", "catalogId": "series_popular",  "label": "Popular Series"},
        {"type": "series", "catalogId": "series_trending", "label": "Trending Series"},
        {"type": "series", "catalogId": "series_latest",   "label": "Latest Series"},
        {"type": "series", "catalogId": "series_top_rated","label": "Top Rated Series"},
        *[{"type": "movie",  "catalogId": f"movie_{g.lower()}",  "label": g, "genre": g} for g in GENRES],
        *[{"type": "series", "catalogId": f"series_{g.lower()}", "label": g, "genre": g} for g in GENRES],
    ],
}

write_json(os.path.join(OUT_DIR, "index.json"), index)
print("Written catalog/index.json")
print("\nDone.")