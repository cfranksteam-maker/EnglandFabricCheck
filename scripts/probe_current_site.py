import json, urllib.request, urllib.error
ROOT='https://www.englandfurniture.com'
UA='EnglandFabricCheck-Android/1.5'

def get(path):
    u=ROOT+path
    req=urllib.request.Request(u,headers={'User-Agent':UA,'Accept':'application/json,text/plain,*/*','Referer':ROOT+'/'})
    try:
        with urllib.request.urlopen(req,timeout=25) as r:
            return r.status,json.loads(r.read().decode('utf-8'))
    except urllib.error.HTTPError as e:
        body=e.read().decode('utf-8','replace')
        print('HTTPERR',e.code,path,body[:200])
        return e.code,{}

def barcode_of(product):
    for bucket in ('visual_assets','attributes'):
        vals=product.get(bucket) or []
        if isinstance(vals,str):
            try: vals=json.loads(vals)
            except Exception: vals=[]
        for item in vals:
            if not isinstance(item,dict): continue
            key=item.get('feature_idx') or item.get('code')
            if key=='barcode':
                return str(item.get('value') or item.get('attribute_value') or '')
    return None

for code in ('8858','9606','9528'):
    st,s=get('/api/matrix/v2/england/search/?q='+code)
    prods=(s.get('products') or {}).get('results') or []
    print('SEARCH',code,'HTTP',st,'COUNT',len(prods))
    for p in prods[:10]:
        print(' RESULT',p.get('name'),p.get('sku'),p.get('feature_set'))
        sku=p.get('sku')
        if not sku: continue
        st2,full=get('/api/matrix/v2/england/products/?sku='+sku+'&include=full')
        for fp in full.get('results') or []:
            print(' FULL HTTP',st2,fp.get('name'),fp.get('sku'),'BARCODE',barcode_of(fp))
