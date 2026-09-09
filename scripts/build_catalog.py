import json
import re
import sys
import urllib.request
import html as htmlmod
from datetime import datetime, timezone

BASE = 'https://englandfurniturestore.microdinc.com/fabric/all/cover-type.aspx?page={}'
UA = 'Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 Chrome/140 Safari/537.36'


def fetch(url):
    req = urllib.request.Request(url, headers={
        'User-Agent': UA,
        'Accept': 'text/html,application/xhtml+xml',
        'Accept-Language': 'en-US,en;q=0.9',
        'Referer': 'https://englandfurniturestore.microdinc.com/',
        'Cache-Control': 'no-cache',
    })
    with urllib.request.urlopen(req, timeout=30) as r:
        return r.read().decode('utf-8', errors='replace')


def strip_html(s):
    s = re.sub(r'(?is)<script\b[^>]*>.*?</script>', ' ', s)
    s = re.sub(r'(?is)<style\b[^>]*>.*?</style>', ' ', s)
    s = re.sub(r'(?is)<!--.*?-->', ' ', s)
    s = re.sub(r'(?i)<(?:br|/div|/li|/p|/h[1-6]|/a|/span|/section|/article|/button)[^>]*>', '\n', s)
    s = re.sub(r'<[^>]+>', ' ', s)
    s = htmlmod.unescape(s).replace('\xa0', ' ')
    return re.sub(r'[ \t]+', ' ', s)


def parse(text):
    flat = re.sub(r'\s+', ' ', strip_html(text))
    pat = re.compile(
        r'\b(\d{4,6})\b\s+([A-Z][A-Z0-9 &\'./()\-]{2,80}?)\s+View Item\b',
        re.I,
    )
    out = {}
    for code, name in pat.findall(flat):
        name = re.sub(r'\s+', ' ', name).strip().upper()
        if 3 <= len(name) <= 80 and re.search('[A-Z]', name):
            out[code] = name
    return out


def detect_total_items(text):
    plain = re.sub(r'\s+', ' ', strip_html(text))
    matches = re.findall(r'\b(\d{2,4})\s+Items\b', plain, re.I)
    return max((int(x) for x in matches), default=0)


records = {}
reported_total = 0
empty_after_data = 0

for page in range(1, 41):
    raw = fetch(BASE.format(page))
    if page == 1:
        reported_total = detect_total_items(raw)
    page_records = parse(raw)
    print(f'England official kiosk page {page}: {len(page_records)} records', file=sys.stderr)

    before = len(records)
    records.update(page_records)
    added = len(records) - before

    if added == 0 and len(records) >= 400:
        empty_after_data += 1
    else:
        empty_after_data = 0

    if empty_after_data >= 2:
        break
    if reported_total and len(records) >= reported_total:
        break

if len(records) < 450:
    raise SystemExit(f'Refusing England official kiosk catalog with only {len(records)} records')

if reported_total and len(records) < reported_total - 10:
    raise SystemExit(
        f'Refusing incomplete England catalog: parsed {len(records)} of reported {reported_total}'
    )

payload = {
    'source': 'England Furniture official in-store catalog',
    'source_url': 'https://englandfurniturestore.microdinc.com/fabric/all/cover-type.aspx',
    'generated_at': datetime.now(timezone.utc).isoformat().replace('+00:00', 'Z'),
    'reported_total': reported_total,
    'count': len(records),
    'records': records,
}

with open('catalog.json', 'w', encoding='utf-8') as f:
    json.dump(payload, f, indent=2, sort_keys=True)

print(json.dumps({
    'source': payload['source'],
    'reported_total': reported_total,
    'count': len(records),
    'sample_9528': records.get('9528'),
}))
