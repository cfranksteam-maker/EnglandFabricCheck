import json, re, sys, urllib.request, html as htmlmod
from urllib.parse import urlencode

UA = 'Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 Chrome/140 Safari/537.36'
SOURCES = [
    ('Interior Furniture Resources', 'https://www.interiorfurnitureresources.com/fabric/cover-type.aspx?brand=england&page={}'),
    ('Art Sample Furniture', 'https://www.artsample.com/fabric/cover-type.aspx?brand=england&page={}'),
    ('LA Waters Furniture', 'https://www.lawaters.com/fabric/cover-type.aspx?brand=england&page={}'),
    ('Haynes Brothers', 'https://www.haynesbrosfurniture.com/fabric/cover-type.aspx?brand=england&page={}'),
    ('Seaside Furniture', 'https://www.seasidefurniture.com/fabric/cover-type.aspx?brand=england&page={}'),
    ('Frazier and Son', 'https://www.frazierandsonfurniture.com/fabric/cover-type.aspx?brand=england&page={}'),
]

def fetch(url):
    req = urllib.request.Request(url, headers={
        'User-Agent': UA,
        'Accept': 'text/html,application/xhtml+xml',
        'Accept-Language': 'en-US,en;q=0.9',
        'Cache-Control': 'no-cache',
    })
    with urllib.request.urlopen(req, timeout=25) as r:
        return r.read().decode('utf-8', errors='replace')

def strip_html(s):
    s = re.sub(r'(?is)<script\b[^>]*>.*?</script>', ' ', s)
    s = re.sub(r'(?is)<style\b[^>]*>.*?</style>', ' ', s)
    s = re.sub(r'(?i)<(?:br|/div|/li|/p|/h[1-6]|/a|/span|/section|/article)[^>]*>', '\n', s)
    s = re.sub(r'<[^>]+>', ' ', s)
    s = htmlmod.unescape(s).replace('\xa0',' ')
    return re.sub(r'[ \t]+',' ',s)

def parse(text):
    flat = re.sub(r'\s+',' ',strip_html(text))
    # Dealer pages often show: NAME England COLLECTION CODE NAME View Item
    # We anchor on CODE + NAME + View Item and rely on the brand-filtered URL.
    pat = re.compile(r'\b(\d{4,6})\b\s+([A-Z][A-Z0-9 &\'./()\-]{2,80}?)\s+View Item\b', re.I)
    out = {}
    for code,name in pat.findall(flat):
        name = re.sub(r'\s+',' ',name).strip().upper()
        if 3 <= len(name) <= 80 and re.search('[A-Z]', name):
            out[code] = name
    return out

best_name = None
best_records = {}
errors = []
for source_name, template in SOURCES:
    records = {}
    try:
        # 30 pages is safely above the current England catalog size.
        for page in range(1, 31):
            raw = fetch(template.format(page))
            page_records = parse(raw)
            print(f'{source_name} page {page}: {len(page_records)} records', file=sys.stderr)
            before = len(records)
            records.update(page_records)
            # If we have already collected a full catalog and get two empty pages,
            # no need to keep requesting more.
            if page > 2 and len(page_records) == 0 and len(records) >= 400:
                break
        print(f'{source_name}: {len(records)} unique records', file=sys.stderr)
    except Exception as e:
        errors.append(f'{source_name}: {type(e).__name__}: {e}')
        print(errors[-1], file=sys.stderr)

    if len(records) > len(best_records):
        best_name = source_name
        best_records = records
    if len(records) >= 500:
        break

if len(best_records) < 450:
    print('\n'.join(errors), file=sys.stderr)
    raise SystemExit(f'Refusing catalog with only {len(best_records)} records from best source {best_name}')

payload = {
    'source': best_name,
    'count': len(best_records),
    'records': best_records,
}
with open('catalog.json','w',encoding='utf-8') as f:
    json.dump(payload,f,indent=2,sort_keys=True)
print(json.dumps({'source':best_name,'count':len(best_records),'sample_9528':best_records.get('9528')}))
