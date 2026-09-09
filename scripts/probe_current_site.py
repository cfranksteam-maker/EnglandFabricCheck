import re, urllib.request, urllib.parse, urllib.error, json

UA='Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 Chrome/140 Safari/537.36'
ROOT='https://www.englandfurniture.com'
URL=ROOT+'/product/nola-chair-3n04'

def get(url):
    req=urllib.request.Request(url,headers={'User-Agent':UA,'Accept':'application/json,text/html,application/xhtml+xml,*/*','Referer':ROOT+'/'})
    try:
        with urllib.request.urlopen(req,timeout=25) as r:
            return r.status,r.geturl(),r.read().decode('utf-8','replace')
    except urllib.error.HTTPError as e:
        body=e.read().decode('utf-8','replace')
        return e.code,url,body

status,final,html=get(URL)
print('PAGE',status,final,'LEN',len(html))
print('HAS9606', '9606' in html, 'HAS9528', '9528' in html)

# Inspect page for likely channel/bootstrap data.
for key in ['channel','channel_idx','channelId','channel_id','"ch"','England']:
    low=html.lower(); pos=low.find(key.lower())
    if pos>=0:
        print('HTML_SNIP',key,html[max(0,pos-250):pos+450].replace('\n',' ')[:700])

scripts=re.findall(r'<script[^>]+src=["\']([^"\']+)',html,re.I)
for s in scripts:
    u=urllib.parse.urljoin(final,s)
    if 'englandfurniture.com' not in urllib.parse.urlparse(u).netloc: continue
    st,fu,js=get(u)
    low=js.lower()
    if '/api/matrix/' in low or 'channel_from_cookie' in low or 'search_query' in low:
        print('JS',u,'STATUS',st,'LEN',len(js))
        for keyword in ['/api/matrix/v2/__channel__/search/','channel_from_cookie','search_query','querys:{','feature_set?.idx===\"fabric\"','barcode']:
            pos=low.find(keyword.lower())
            if pos>=0:
                print('JS_SNIP',keyword,js[max(0,pos-400):pos+900].replace('\n',' ')[:1300])

# Probe likely channel names and basic query shapes against the current Matrix API.
channels=['england','England','englandfurniture','default','retail','consumer','web']
queries=[
    ('search_q','/api/matrix/v2/{ch}/search/?q=9606'),
    ('search_query','/api/matrix/v2/{ch}/search/?query=9606'),
    ('search_term','/api/matrix/v2/{ch}/search/?term=9606'),
    ('products_sku','/api/matrix/v2/{ch}/products/?sku=9606&include=full'),
    ('options','/api/matrix/v2/{ch}/options/?q=9606'),
]
for ch in channels:
    for label,tpl in queries:
        url=ROOT+tpl.format(ch=urllib.parse.quote(ch))
        st,fu,body=get(url)
        if st != 404 or len(body)>100:
            clean=body[:500].replace('\n',' ')
            print('API',ch,label,'STATUS',st,'FINAL',fu,'LEN',len(body),'HAS9606',('9606' in body),'BODY',clean)

print('probe-complete')
