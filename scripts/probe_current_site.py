import json, urllib.request, urllib.error, urllib.parse
ROOT='https://www.englandfurniture.com'
UA='EnglandFabricCheck-Android/1.6'

def get(path):
    u=ROOT+path
    req=urllib.request.Request(u,headers={'User-Agent':UA,'Accept':'application/json,text/plain,*/*','Referer':ROOT+'/'})
    try:
        with urllib.request.urlopen(req,timeout=25) as r:
            return r.status,json.loads(r.read().decode('utf-8'))
    except urllib.error.HTTPError as e:
        body=e.read().decode('utf-8','replace')
        print('HTTPERR',e.code,path,body[:300])
        return e.code,{}

def summarize(label,path):
    st,data=get(path)
    results=data.get('results') if isinstance(data,dict) else None
    products=(data.get('products') or {}) if isinstance(data,dict) else {}
    pres=products.get('results') or [] if isinstance(products,dict) else []
    arr=results if isinstance(results,list) else pres
    print('\nCASE',label,'HTTP',st,'PATH',path)
    if isinstance(data,dict):
        print(' keys',list(data.keys())[:20])
        print(' total',data.get('total'),products.get('total') if isinstance(products,dict) else None,'page',data.get('page'),'page_size',data.get('page_size'),'has_next',data.get('has_next_page'))
    print(' result_count',len(arr))
    for p in arr[:5]:
        if isinstance(p,dict): print('  ',p.get('name'),p.get('sku'),p.get('feature_set'))

cases=[
 ('products_default','/api/matrix/v2/england/products/?include=full'),
 ('products_ps100','/api/matrix/v2/england/products/?include=full&page_size=100'),
 ('products_fabric','/api/matrix/v2/england/products/?include=full&feature_set=fabric&page_size=100'),
 ('products_feature_idx','/api/matrix/v2/england/products/?include=full&feature_set_idx=fabric&page_size=100'),
 ('search_empty','/api/matrix/v2/england/search/?q=&page_size=100'),
 ('search_fabric','/api/matrix/v2/england/search/?q=fabric&page_size=100'),
]
for label,path in cases: summarize(label,path)

# Confirm known barcodes as a sanity check.
for code in ('8858','9606','9528'):
    summarize('search_'+code,'/api/matrix/v2/england/search/?q='+code)
