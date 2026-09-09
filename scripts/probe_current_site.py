import json, urllib.request, urllib.error
ROOT='https://www.englandfurniture.com'
UA='EnglandFabricCheck-Android/1.6'

def get(path):
    req=urllib.request.Request(ROOT+path,headers={'User-Agent':UA,'Accept':'application/json,text/plain,*/*','Referer':ROOT+'/'})
    with urllib.request.urlopen(req,timeout=30) as r:
        return json.loads(r.read().decode('utf-8'))

def feature_idx(p):
    raw=p.get('feature_set')
    if isinstance(raw,dict): return raw.get('idx')
    if isinstance(raw,str):
        try: return json.loads(raw).get('idx')
        except Exception: return ''
    return ''

def barcode_of(p):
    for bucket in ('visual_assets','attributes'):
        vals=p.get(bucket) or []
        if isinstance(vals,str):
            try: vals=json.loads(vals)
            except Exception: vals=[]
        if not isinstance(vals,list): continue
        for item in vals:
            if not isinstance(item,dict): continue
            if (item.get('feature_idx') or item.get('code'))=='barcode':
                return str(item.get('value') or item.get('attribute_value') or '')
    return ''

seen=set(); fabrics={}
for page in range(1,21):
    data=get(f'/api/matrix/v2/england/products/?include=full&page_size=100&page={page}')
    results=data.get('results') or []
    skus=[str(p.get('sku') or '') for p in results]
    new=sum(1 for s in skus if s and s not in seen)
    for p in results:
        sku=str(p.get('sku') or '')
        if sku: seen.add(sku)
        if feature_idx(p)=='fabric':
            bc=barcode_of(p)
            if bc: fabrics[bc]=p.get('name') or sku
    print('PAGE',page,'results',len(results),'new',new,'has_next',data.get('has_next_page'),'unique_products',len(seen),'fabrics',len(fabrics),'8858',fabrics.get('8858'))
    if not results or new==0: break
print('FINAL products',len(seen),'fabrics',len(fabrics),'8858',fabrics.get('8858'),'9606',fabrics.get('9606'),'9528',fabrics.get('9528'))
