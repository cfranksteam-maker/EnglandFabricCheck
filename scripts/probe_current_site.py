import re, urllib.request, urllib.parse

UA='Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 Chrome/140 Safari/537.36'
URL='https://www.englandfurniture.com/product/nola-chair-3n04'

def get(url):
    req=urllib.request.Request(url,headers={'User-Agent':UA,'Accept':'text/html,application/xhtml+xml,*/*','Referer':'https://www.englandfurniture.com/'})
    with urllib.request.urlopen(req,timeout=25) as r:
        return r.geturl(),r.read().decode('utf-8','replace')

final,html=get(URL)
print('FINAL',final,'LEN',len(html))
print('HAS9606', '9606' in html)
print('HAS9528', '9528' in html)

scripts=re.findall(r'<script[^>]+src=["\']([^"\']+)',html,re.I)
print('SCRIPTS',len(scripts))
for s in scripts:
    u=urllib.parse.urljoin(final,s)
    if any(k in u.lower() for k in ['microd','config','product','fabric','cover','custom','app','main']):
        print('SCRIPT',u)

patterns=[r'https?://[^"\'\s<>]+',r'["\']([^"\']*(?:api|graphql|fabric|cover|configurator|selectcover)[^"\']*)["\']']
seen=set()
for pat in patterns:
    for m in re.finditer(pat,html,re.I):
        v=m.group(1) if m.lastindex else m.group(0)
        v=v.replace('&amp;','&')
        if len(v)>240 or v in seen: continue
        seen.add(v)
        if any(x in v.lower() for x in ['fabric','cover','config','api','graphql','microd']):
            print('HINT',v)

for s in scripts:
    u=urllib.parse.urljoin(final,s)
    if 'englandfurniture.com' not in urllib.parse.urlparse(u).netloc: continue
    try:
        _,js=get(u)
    except Exception:
        continue
    low=js.lower()
    if any(k in low for k in ['selectcover','fabric','coveredarea','graphql','/api/']):
        print('JS_MATCH',u,'LEN',len(js))
        for keyword in ['selectcover','coveredarea','fabric','graphql','/api/']:
            pos=low.find(keyword)
            if pos>=0:
                snippet=js[max(0,pos-180):pos+400].replace('\n',' ')
                print('SNIP',keyword,snippet[:580])

print('probe-complete')
