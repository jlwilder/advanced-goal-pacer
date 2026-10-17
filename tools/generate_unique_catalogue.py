"""Build public, versioned collection definitions; never accepts player quantities."""
import argparse
import hashlib
import json
from pathlib import Path
import re

def require(condition, message):
    if not condition:
        raise ValueError(message)


def revision(value):
    if not re.fullmatch(r'[a-fA-F0-9]{40}', value):
        raise argparse.ArgumentTypeError('Use a full 40-character source commit SHA')
    return value.lower()


def positive_integer(value):
    number = int(value)
    if number < 1:
        raise argparse.ArgumentTypeError('Use a positive catalogue revision')
    return number


def main():
    root = Path(__file__).resolve().parent.parent
    parser = argparse.ArgumentParser()
    parser.add_argument('--cache-export', type=Path, required=True)
    parser.add_argument('--item-definitions', type=Path, required=True)
    parser.add_argument('--source-runelite-revision', required=True, type=revision)
    parser.add_argument('--source-cs2-revision', required=True, type=revision)
    parser.add_argument('--catalogue-revision', required=True, type=positive_integer)
    parser.add_argument('--candidates', type=Path, default=root / 'src/test/resources/m0/boss-page-candidates.json')
    parser.add_argument('--output', type=Path, default=root / 'src/main/resources/com/mygoals/catalogue/uniques.json')
    args = parser.parse_args()
    cache = json.loads(args.cache_export.read_text(encoding='utf-8-sig'))
    require(set(cache) == {'kind', 'runeLiteVersion', 'gameRevision', 'missingCandidateMetrics', 'pages'}, 'Unexpected cache export fields')
    require(cache['kind'] == 'candidate-cache-definitions-not-verified-eligibility', 'Expected public cache definitions')
    require(not cache['missingCandidateMetrics'], 'Resolve missing boss candidates before generating')
    require(isinstance(cache['gameRevision'], int) and cache['gameRevision'] > 0, 'Invalid game revision')
    source = args.item_definitions.read_text(encoding='utf-8-sig')
    labels = {int(value): name for name, value in re.findall(r'\t/\*\*\s*\n\t \* ([^\n]+)\s*\n\t \*/\s*\n\tpublic static final int \w+ = (\d+);', source)}
    symbols = {int(value): name for name, value in re.findall(r'^\tpublic static final int (\w+) = (\d+);', source, re.M)}
    pages = {page['structId']: page for page in cache['pages']}
    candidate_document = json.loads(args.candidates.read_text(encoding='utf-8-sig'))
    require(candidate_document.get('cs2Revision', args.source_cs2_revision) == args.source_cs2_revision,
            'Candidate CS2 provenance does not match the supplied source commit')
    candidates = candidate_document['entries']
    exclude = {
        'THE_GAUNTLET': [23859],
        'NIGHTMARE': [25837, 25838],
        'CHAMBERS_OF_XERIC': [22386, 24670, 22388, 22390, 22392, 22394, 22396],
        'THEATRE_OF_BLOOD': [25746, 25742, 25744],
        'TOMBS_OF_AMASCUT': [27255, 27248, 27372, 27377, 27378, 27379, 27380, 27381],
    }
    only = {
        'DAGANNOTH_PRIME': [12644, 6731, 6739, 6562],
        'DAGANNOTH_REX': [12645, 6737, 6735, 6739],
        'DAGANNOTH_SUPREME': [12643, 6733, 6739, 6724],
    }
    # Conservative unlock-target policy, not a claim about native counter caps or
    # whether replacement copies are obtainable. Repeatable CoX kits/dust must allow totals > 1.
    single_targets = {23859, 22388, 22390, 22392, 22394, 22396,
                      22494, 22496, 22498, 22500, 22502, 25746, 25837, 25838,
                      25592, 25594, 25596, 25598, 30926,
                      27255, 27248, 27372, 27257, 27259, 27261, 27263, 27265,
                      27377, 27378, 27379, 27380, 27381}
    large_targets = {12934, 21726, 25588, 20718, 25578, 30068, 30640, 26231, 27616, 28924, 28991}
    entries, used_pages = [], set()
    for candidate in candidates:
        metric, page_id = candidate['metricId'], candidate['candidatePageStructId']
        if metric == 'MIMIC':
            page_ids = [511, 512, 2871, 4874]
            item_ids = [23185] + [i for i in pages[2871]['itemIds'] if labels[i].lower().startswith('3rd age ')] + [30926]
        else:
            page_ids = [page_id]
            all_ids = pages[page_id]['itemIds']
            item_ids = only.get(metric, [i for i in all_ids if i not in exclude.get(metric, [])])
            require(set(item_ids).issubset(all_ids), f'Eligibility items missing from {metric}')
        require(item_ids and len(item_ids) == len(set(item_ids)), f'Empty or duplicate items for {metric}')
        used_pages.update(page_ids)
        entries.append({'metricId': metric, 'pageIds': page_ids, 'itemIds': item_ids})
    item_ids = sorted({i for entry in entries for i in entry['itemIds']})
    items = []
    for item in item_ids:
        symbol = symbols[item]
        pet = 'PET' in symbol and not any(part in symbol for part in ['MORPH', 'DUST', 'INSURANCE'])
        items.append({'id': item, 'name': labels[item], 'pet': pet,
                      'targetLimit': 1 if item in single_targets else 65535 if item in large_targets else 250,
                      'pageIds': [p for p in sorted(used_pages) if item in pages[p]['itemIds']]})
    output = {
        'version': f"game-{cache['gameRevision']}-uniques-{args.catalogue_revision}", 'gameRevision': cache['gameRevision'],
        'runeLiteVersion': cache['runeLiteVersion'],
        'cacheDefinitionSha256': hashlib.sha256(args.cache_export.read_bytes()).hexdigest(),
        'sourceRuneLiteRevision': args.source_runelite_revision,
        'sourceCs2Revision': args.source_cs2_revision,
        'reviewStatus': 'Static cache and mode mappings reviewed; live acquisition uses explicit source adapters and collection-log verification.',
        'targetPolicy': 'Conservative plugin target limits; not a claim about native collection-log counter caps.',
        'wikiCrossCheck': {
            'status': 'PARTIAL_INDEXED_REVIEW; direct full-page access blocked',
            'indexes': ['https://oldschool.runescape.wiki/w/Boss', 'https://oldschool.runescape.wiki/w/Raids'],
            'repeatableCoxRewardEvidence': 'https://oldschool.runescape.wiki/w/Update:Chambers_of_Xeric_Improvements',
            'note': 'The May 2020 update permits repeat metamorphic dust and one ancestral kit per recoloured piece. Neither is limited to one target.'
        },
        'bosses': entries,
        'pages': [{k: pages[p][k] for k in ['structId', 'name', 'itemEnumId', 'itemIds']} for p in sorted(used_pages)],
        'items': items,
    }
    target = args.output
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_bytes((json.dumps(output, indent=2, ensure_ascii=False) + '\n').encode('utf-8'))
    print(f'{len(entries)} bosses, {len(used_pages)} pages, {len(items)} selectable items -> {target}')


if __name__ == '__main__':
    main()
