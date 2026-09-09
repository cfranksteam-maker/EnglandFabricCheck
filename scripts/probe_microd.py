import urllib.request, urllib.error, re

URLS = [
    'https://englandfurniture-imp.microdinc.com/',
    'https://englandfurniture-imp.microdinc.com/fabric/cover-type.aspx?page=2',
    'https://englandfurniture-imp.microdinc.com/fabric/all/cover-type.aspx?page=2',
    'https://englandfurniturestore.microdinc.com/',
    'https://englandfurniturestore.microdinc.com/fabric/cover-type.aspx?page=2',
    'https://englandfurniturestore.microdinc.com/fabric/all/cover-type.aspx?page=2',
    'https://www.englandfurniture.com/product/nola-sectional-3n00-sect?action=SelectCover&body=BENNETT+JUNGLE&cbAreaName=BODY&coveredArea=BODY',
    'https://www.englandfurniture.com/product/nola-sectional-3n00-sect?action=SelectCover&body=ZZZZZ+INVALID&cbAreaName=BODY&coveredArea=BODY',
]

for url in URLS:
    print('\nURL', url)
    req = urllib.request.Request(url, headers={'User-Agent':'Mozilla/5.0 Chrome/140 Safari/537.36','Accept':'text/html,*/*'})
    try:
        with urllib.request.urlopen(req, timeout=20) as r:
            data = r.read().decode('utf-8', errors='replace')
            print('status', r.status)
            print('final', r.geturl())
            print('length', len(data))
            for needle in ['9528','BENNETT JUNGLE','View Item','Nola Sectional','ZZZZZ INVALID']:
                print(needle, needle.lower() in data.lower())
            m = re.search(r'<title[^>]*>(.*?)</title>', data, re.I|re.S)
            print('title', re.sub(r'\s+',' ',m.group(1)).strip()[:160] if m else '(none)')
    except Exception as e:
        print(type(e).__name__, repr(e))
