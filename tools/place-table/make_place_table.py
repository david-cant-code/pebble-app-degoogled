"""Builds Gravel's built-in town list from a GeoNames dump.

GeoNames (https://www.geonames.org) data is licensed CC BY 4.0. This script trims
it to what the weather screens use: each place's name, position, country, region
and population, plus the region and country names and a folding table for
search. Historical, abandoned and destroyed places (feature codes PPLH, PPLQ,
PPLW) are left out. Places of 100,000 or more keep their Latin-script alternate
names, leaving out all-lowercase ones, ones with a digit, ones over 40
characters, all-capital ones of three characters or fewer and ones that,
lowercased and without accents, equal the name or an alternate already kept.
GeoNames' download directory held no dated copies of its dumps when checked
on 2026-11-01, so the download a list was built from is recorded by date and
SHA-256 in each output file's header; a refresh is a new download, a new run,
and a new commit.

Usage (standard library only; fold.tsv follows the running Python's Unicode
version, which its header records, so a refresh on another version can change it):
    curl -O https://download.geonames.org/export/dump/cities1000.zip
    curl -O https://download.geonames.org/export/dump/admin1CodesASCII.txt
    curl -O https://download.geonames.org/export/dump/countryInfo.txt
    python3 -I make_place_table.py --downloaded 2026-11-01T22:57Z \\
        --cities cities1000.zip --regions admin1CodesASCII.txt \\
        --countries countryInfo.txt \\
        --out ../../pebble/src/commonMain/composeResources/files/places
"""
import argparse
import hashlib
import os
import sys
import unicodedata
import zipfile


def fail(message):
    sys.exit(f"make_place_table: {message}")

ALTERNATES_MIN_POPULATION = 100_000
GONE_FEATURE_CODES = {"PPLH", "PPLQ", "PPLW"}

# Codes and names people type that GeoNames does not use: for countries, beside the ISO 3166
# alpha-2 and alpha-3 codes from countryInfo, a few codes, common English names, and former names
# and spellings; and Canadian and Australian postal abbreviations (GeoNames numbers those regions),
# keyed by GeoNames' region name so a renumbering cannot attach them elsewhere.
COUNTRY_ALIASES = {"GB": ["UK", "Great Britain", "Britain"], "AE": ["UAE"], "US": ["America"],
                   "NL": ["Netherlands", "Holland"], "KR": ["Korea"], "CZ": ["Czech Republic"],
                   "CD": ["Congo", "DRC", "DR Congo"], "CG": ["Congo"], "MM": ["Burma"],
                   "CV": ["Cape Verde"], "MK": ["Macedonia"], "SZ": ["Swaziland"], "TL": ["East Timor"],
                   "TR": ["Turkiye"], "VN": ["Viet Nam"]}
REGION_ABBREVIATIONS = {
    "CA": {"Alberta": "AB", "British Columbia": "BC", "Manitoba": "MB", "New Brunswick": "NB",
           "Newfoundland and Labrador": "NL", "Nova Scotia": "NS", "Northwest Territories": "NT",
           "Nunavut": "NU", "Ontario": "ON", "Prince Edward Island": "PE", "Quebec": "QC",
           "Saskatchewan": "SK", "Yukon": "YT"},
    "AU": {"Australian Capital Territory": "ACT", "New South Wales": "NSW", "Northern Territory": "NT",
           "Queensland": "QLD", "South Australia": "SA", "Tasmania": "TAS", "Victoria": "VIC",
           "Western Australia": "WA"},
}
MAX_ALTERNATE_LENGTH = 40

# Letters NFKD leaves whole; each folds to what a plain keyboard types for it.
SPECIAL_FOLDS = {
    "ß": "ss", "ẞ": "ss", "æ": "ae", "Æ": "ae", "œ": "oe", "Œ": "oe", "ø": "o", "Ø": "o",
    "đ": "d", "Đ": "d", "ð": "d", "Ð": "d", "þ": "th", "Þ": "th", "ł": "l", "Ł": "l",
    "ħ": "h", "Ħ": "h", "ı": "i", "ŀ": "l", "Ŀ": "l", "ŋ": "n", "Ŋ": "n", "ĸ": "k",
    "ʼ": "'", "’": "'", "‘": "'", "ʻ": "'", "ʿ": "'", "ʾ": "'", "´": "'", "′": "'",
}

# The Latin blocks a user may type from, folded even where no name uses the letter.
TYPED_RANGES = [(0x00B4, 0x00B4), (0x00C0, 0x024F), (0x1E00, 0x1EFF), (0x2018, 0x2019), (0x2032, 0x2032)]


def sha256(path):
    digest = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            digest.update(chunk)
    return digest.hexdigest()


def fold_char(c):
    if c in SPECIAL_FOLDS:
        return SPECIAL_FOLDS[c]
    stripped = "".join(d for d in unicodedata.normalize("NFKD", c) if unicodedata.category(d) != "Mn")
    return "".join(SPECIAL_FOLDS.get(d, d) for d in stripped).lower()


def fold(text):
    return "".join(fold_char(c) for c in text)


def clean(field):
    # Tabs, newlines and the alternates separator are structure in the output.
    return " ".join(field.replace("|", " ").split())


def is_latin(text):
    return all(ord(c) < 0x0250 or 0x1E00 <= ord(c) <= 0x1EFF or c in " '-.’" for c in text)


def read_cities(path):
    with zipfile.ZipFile(path) as archive:
        with archive.open("cities1000.txt") as raw:
            for line in raw:
                columns = line.decode("utf-8").rstrip("\n").split("\t")
                if columns[7] in GONE_FEATURE_CODES:
                    continue
                yield {
                    "name": clean(columns[1]),
                    "alternates": columns[3],
                    "lat": float(columns[4]),
                    "lon": float(columns[5]),
                    "cc": columns[8],
                    "admin1": columns[10],
                    "population": int(columns[14] or 0),
                }


def alternates_for(city):
    if city["population"] < ALTERNATES_MIN_POPULATION:
        return []
    seen = {fold(city["name"])}
    kept = []
    for alternate in city["alternates"].split(","):
        alternate = clean(alternate)
        key = fold(alternate)
        if (not alternate or key in seen or len(alternate) > MAX_ALTERNATE_LENGTH
                or not is_latin(alternate) or any(c.isdigit() for c in alternate)
                or (len(alternate) <= 3 and alternate.isupper()) or alternate == alternate.lower()):
            continue
        seen.add(key)
        kept.append(alternate)
    return kept


def coordinate(value):
    text = f"{value:.3f}".rstrip("0").rstrip(".")
    return "0" if text in ("-0", "") else text


def header(title, sources, downloaded):
    lines = [
        f"# {title}",
        "# Data: GeoNames (https://www.geonames.org), CC BY 4.0 (https://creativecommons.org/licenses/by/4.0/).",
        "# Trimmed by tools/place-table/make_place_table.py; see that script for the columns kept.",
        f"# Downloaded {downloaded} from https://download.geonames.org/export/dump/:",
    ]
    lines += [f"#   {os.path.basename(path)} sha256 {sha256(path)}" for path in sources]
    return "\n".join(lines) + "\n"


def write(path, text):
    with open(path, "w", encoding="utf-8", newline="\n") as f:
        f.write(text)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--downloaded", required=True, help="UTC time of the download, e.g. 2026-11-01T22:57Z")
    parser.add_argument("--cities", required=True)
    parser.add_argument("--regions", required=True)
    parser.add_argument("--countries", required=True)
    parser.add_argument("--out", required=True)
    args = parser.parse_args()
    os.makedirs(args.out, exist_ok=True)

    cities = sorted(read_cities(args.cities), key=lambda c: (c["cc"], c["admin1"], c["name"], c["lat"], c["lon"]))
    country_codes = set()
    with open(args.countries, encoding="utf-8") as f:
        for line in f:
            if not line.startswith("#"):
                country_codes.add(line.split("\t")[0])
    rows = []
    typed = set()
    for city in cities:
        # The app reads '#' lines as comments and needs every place's country.
        if city["name"].startswith("#"):
            fail(f"a name starts with '#': {city['name']}")
        if city["cc"] not in country_codes:
            fail(f"{city['name']}: country {city['cc']} is not in countryInfo")
        alternates = alternates_for(city)
        typed.update(city["name"])
        for alternate in alternates:
            typed.update(alternate)
        # Population in thousands, rounded: it orders search results and picks the Current
        # Location town.
        row = [city["name"], coordinate(city["lat"]), coordinate(city["lon"]), city["cc"], city["admin1"],
               str(round(city["population"] / 1000))]
        if alternates:
            row.append("|".join(alternates))
        rows.append("\t".join(row))
    places = header(
        "Towns: name, latitude, longitude, country code, region code, population in thousands, "
        f"Latin-script alternate names (places of {ALTERNATES_MIN_POPULATION:,}+ only, '|'-separated)",
        [args.cities], args.downloaded,
    ) + "\n".join(rows) + "\n"
    write(os.path.join(args.out, "places.tsv"), places)

    regions = []
    abbreviated = set()
    with open(args.regions, encoding="utf-8") as f:
        for line in f:
            columns = line.rstrip("\n").split("\t")
            if len(columns) >= 2 and "." in columns[0]:
                name = clean(columns[1])
                abbreviation = REGION_ABBREVIATIONS.get(columns[0].split(".")[0], {}).get(name)
                if abbreviation:
                    abbreviated.add((columns[0].split(".")[0], name))
                regions.append(f"{columns[0]}\t{name}" + (f"\t{abbreviation}" if abbreviation else ""))
    missing = {(cc, name) for cc, names in REGION_ABBREVIATIONS.items() for name in names} - abbreviated
    if missing:
        fail(f"region abbreviations without a GeoNames region: {sorted(missing)}")
    write(os.path.join(args.out, "regions.tsv"),
          header("Regions: country code.region code, name, postal abbreviation (Canada and Australia)",
                 [args.regions], args.downloaded)
          + "\n".join(sorted(regions)) + "\n")

    countries = []
    with open(args.countries, encoding="utf-8") as f:
        for line in f:
            if line.startswith("#"):
                continue
            columns = line.rstrip("\n").split("\t")
            if len(columns) > 4 and len(columns[0]) == 2:
                codes = "|".join([columns[1]] + COUNTRY_ALIASES.get(columns[0], []))
                countries.append(f"{columns[0]}\t{clean(columns[4])}\t{codes}")
    write(os.path.join(args.out, "countries.tsv"),
          header("Countries: country code, name, other codes ('|'-separated: ISO alpha-3, then aliases)",
                 [args.countries], args.downloaded)
          + "\n".join(sorted(countries)) + "\n")

    for start, end in TYPED_RANGES:
        typed.update(chr(code) for code in range(start, end + 1))
    typed.update(SPECIAL_FOLDS)
    folds = []
    for c in sorted(typed):
        # Combining marks are dropped by the app's fold itself (CharCategory.NON_SPACING_MARK).
        if ord(c) < 0x80 or unicodedata.category(c).startswith(("C", "Z")) or unicodedata.category(c) == "Mn":
            continue
        folded = fold_char(c)
        if not folded:
            fail(f"U+{ord(c):04X} folds to nothing")
        # The app reads one UTF-16 unit per fold key.
        if ord(c) > 0xFFFF and folded != c:
            fail(f"U+{ord(c):04X} is outside the Basic Multilingual Plane")
        if folded != c:
            folds.append(f"{c}\t{folded}")
    write(os.path.join(args.out, "fold.tsv"),
          header("Search folding: character, what it folds to (lowercase, accents removed); characters "
                 "not listed fold to their lowercase, combining marks are dropped. Folded with Python's "
                 f"unicodedata, Unicode {unicodedata.unidata_version}, for the characters these names use, the Latin letters and apostrophe look-alikes",
                 [args.cities], args.downloaded)
          + "\n".join(folds) + "\n")
    return 0


if __name__ == "__main__":
    sys.exit(main())
