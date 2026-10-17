"""Generate collection-log page candidates from pinned native CS2; this is not item eligibility."""

import argparse
import hashlib
import json
from pathlib import Path
import re
from urllib.parse import quote
from urllib.request import urlopen


ROOT = Path(__file__).resolve().parents[1]
REVISION = "736e78d4c4260ba2a9d8ce3d2d665a98185de42e"
SOURCE_SHA256 = "902a7650bf276865ca841998197a75dd9a52c1e67a7a90ecc4fdbd93884c13e0"
SOURCE = f"https://raw.githubusercontent.com/runelite/cs2-scripts/{REVISION}/scripts/" + quote("[proc,collection_category_count].cs2")
ALIASES = {
    "ARTIO": "callisto_and_artio", "CALLISTO": "callisto_and_artio",
    "CALVARION": "vetion_and_calvarion", "VETION": "vetion_and_calvarion",
    "DAGANNOTH_PRIME": "dagannoth_kings", "DAGANNOTH_REX": "dagannoth_kings", "DAGANNOTH_SUPREME": "dagannoth_kings",
    "CHAMBERS_OF_XERIC_CHALLENGE_MODE": "chambers_of_xeric",
    "THEATRE_OF_BLOOD_HARD_MODE": "theatre_of_blood",
    "TOMBS_OF_AMASCUT_EXPERT": "tombs_of_amascut",
    "NIGHTMARE": "the_nightmare", "PHOSANIS_NIGHTMARE": "the_nightmare",
    "THE_CORRUPTED_GAUNTLET": "the_gauntlet",
    "SPINDEL": "venenatis_and_spindel", "VENENATIS": "venenatis_and_spindel",
    "LUNAR_CHESTS": "moons_of_peril", "SOL_HEREDIT": "fortis_colosseum",
    "TZKAL_ZUK": "the_inferno", "TZTOK_JAD": "the_fight_caves",
    "BRUTUS": "struct_6413", "DERANGED_ARCHAEOLOGIST": "struct_4867",
    "DOOM_OF_MOKHAIOTL": "struct_5029", "MAD_ANGEL": "struct_1203", "MAGGOT_KING": "struct_1189",
    "SHELLBANE_GRYPHON": "struct_6337", "THE_ROYAL_TITANS": "struct_1743", "YAMA": "struct_4736",
}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source-file", type=Path, help="Use an already downloaded source at the pinned revision")
    parser.add_argument("--boss-metric", action="append", default=[], help="Include a new audited HiScores boss metric, e.g. NEW_BOSS")
    args = parser.parse_args()
    source_bytes = args.source_file.read_bytes() if args.source_file else urlopen(SOURCE, timeout=30).read()
    if hashlib.sha256(source_bytes).hexdigest() != SOURCE_SHA256:
        raise ValueError("CS2 content differs from the audited revision; review the source and update its pin explicitly")
    source = source_bytes.decode("utf-8")
    pages = {}
    for label in re.findall(r"case ([a-z0-9_]+)\s*:", source):
        if label == "default":
            continue
        key = label if label.startswith("struct_") else label.rsplit("_", 1)[0]
        pages[key] = int(label.rsplit("_", 1)[1])
    catalogue = json.loads((ROOT / "src/main/resources/com/mygoals/catalogue/uniques.json").read_text(encoding="utf-8-sig"))
    bosses = list(dict.fromkeys([entry["metricId"] for entry in catalogue["bosses"]] + args.boss_metric))
    if not all(re.fullmatch(r"[A-Z][A-Z0-9_]+", metric) for metric in bosses):
        raise ValueError("Use canonical HiScores enum names for boss metrics")
    entries = []
    for metric in bosses:
        page = pages.get(ALIASES.get(metric, metric.lower()))
        entries.append({
            "metricId": metric, "candidatePageStructId": page,
            "status": "CS2_PAGE_CANDIDATE" if page is not None else "UNRESOLVED",
            "eligibleCanonicalItemKeys": None,
            "note": "Validate page and per-boss eligible subset in current cache" if page is not None
                    else "Reward eligibility needs a dedicated audit; no standalone page established",
        })
    result = {
        "purpose": "Source candidates only; not a runtime catalogue or a support claim",
        "cs2Revision": REVISION, "source": SOURCE,
        "sourceSha256": hashlib.sha256(source_bytes).hexdigest(),
        "runtimeValidation": "PENDING", "entries": entries,
    }
    output = ROOT / "src/test/resources/m0/boss-page-candidates.json"
    output.write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
    print(f"{len(entries)} boss entries; {sum(e['candidatePageStructId'] is not None for e in entries)} page candidates; "
          f"{sum(e['candidatePageStructId'] is None for e in entries)} unresolved")


if __name__ == "__main__":
    main()
