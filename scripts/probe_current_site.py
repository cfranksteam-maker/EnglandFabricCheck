import json, urllib.request, urllib.error
ROOT='https://www.englandfurniture.com'
UA='EnglandFabricCheck-Android/1.5'

def get(path):
    u=ROOT+path
    req=urllib.request.Request(u,headers={'User-Agent':UA,'Accept':'application/json','Referer':ROOT+'/'})
    with urllib.request.urlopen(req,timeout=25) as r:
        return r.status, json.loads(r.read().decode('utf-8'))

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

for code in ('9606','9528'):
    status,s=get('/api/matrix/v2/england/search/?q='+code)
    prods=(s.get('products') or {}).get('results') or []
    print('SEARCH',code,'HTTP',status,'COUNT',len(prods))
    for p in prods[:5]:
        print(' RESULT',p.get('name'),p.get('sku'),p.get('feature_set'))
        sku=p.get('sku')
        if not sku: continue
        status,full=get('/api/matrix/v2/england/products/?sku='+sku+'&include=full')
        for fp in full.get('results') or []:
            print(' FULL HTTP',status,fp.get('name'),fp.get('sku'),'BARCODE',barcode_of(fp))
