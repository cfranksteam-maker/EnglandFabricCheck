import json, re, sys, urllib.request, html as htmlmod

BASE = 'https://www.englandfurniture.com/fabric/cover-type.aspx?page={}'
UA = 'Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 Chrome/140 Safari/537.36'

def fetch(url):
    req = urllib.request.Request(url, headers={'User-Agent': UA, 'Accept': 'text/html,application/xhtml+xml'})
    with urllib.request.urlopen(req, timeout=25) as r:
        return r.read().decode('utf-8', errors='replace')

def strip_html(s):
    s = re.sub(r'(?is)<script\b[^>]*>.*?</script>', ' ', s)
    s = re.sub(r'(?is)<style\b[^>]*>.*?</style>', ' ', s)
    s = re.sub(r'(?i)<(?:br|/div|/li|/p|/h[1-6]|/a|/span|/section|/article)[^>]*>', '\n', s)
    s = re.sub(r'<[^>]+>', ' ', s)
    s = htmlmod.unescape(s).replace('\xa0',' ')
    s = re.sub(r'[ \t]+',' ',s)
    return s

def parse(text):
    flat = re.sub(r'\s+',' ',strip_html(text))
    # Common output: CODE NAME View Item
    pat = re.compile(r'\b(\d{4,6})\b\s+([A-Z][A-Z0-9 &\'./()\-]{2,80}?)\s+View Item\b', re.I)
    out = {}
    for code,name in pat.findall(flat):
        name = re.sub(r'\s+',' ',name).strip().upper()
        if 3 <= len(name) <= 80 and re.search('[A-Z]', name):
            out[code] = name
    return out

all_records = {}
max_pages = 24
for page in range(1, max_pages+1):
    url = BASE.format(page)
    raw = fetch(url)
    records = parse(raw)
    print(f'page {page}: {len(records)} records', file=sys.stderr)
    all_records.update(records)

# Hard fail rather than publish a dangerously incomplete catalog.
if len(all_records) < 500:
    raise SystemExit(f'Refusing catalog with only {len(all_records)} records')

payload = {
    'source': 'England Furniture public fabric catalog',
    'count': len(all_records),
    'records': all_records,
}
with open('catalog.json','w',encoding='utf-8') as f:
    json.dump(payload,f,indent=2,sort_keys=True)
print(json.dumps({'count':len(all_records),'sample_9528':all_records.get('9528')}))
