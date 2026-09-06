import json, re, sys, time
from pathlib import Path
import xml.etree.ElementTree as ET
from openai import OpenAI

SRC = Path(sys.argv[1]) if len(sys.argv) > 1 else Path('app/src/main/res/values/strings.xml')
DEST = Path(sys.argv[2]) if len(sys.argv) > 2 else Path('app/src/main/res/values-ar/strings.xml')
CACHE = DEST.with_suffix('.cache.json')
ANDROID = 'http://schemas.android.com/apk/res/android'
BATCH = 35

def attr(el, name): return el.attrib.get(f'{{{ANDROID}}}{name}', el.attrib.get(name))
def placeholders(s): return sorted(set(re.findall(r'%(?:\\d+\$)?[-+# 0,(]*\d*(?:\.\d+)?[a-zA-Z]|\{[^{}]+\}|<[^>]+>', s)))
def translatable(el): return attr(el, 'translatable') != 'false'
def collect(root):
    result=[]
    for el in root.iter():
        if el.tag == 'string' and translatable(el) and (el.text or '').strip(): result.append((el.attrib['name'], el.text or ''))
        elif el.tag == 'item' and translatable(el) and (el.text or '').strip():
            for parent in root.iter():
                if el in list(parent) and parent.tag in ('plurals','string-array','array'):
                    result.append((f"{parent.attrib['name']}__{list(parent).index(el)}", el.text or '')); break
    return result

def translate(client, pairs):
    schema={'type':'object','additionalProperties':False,'properties':{'items':{'type':'array','items':{'type':'object','additionalProperties':False,'properties':{'key':{'type':'string'},'text':{'type':'string'}},'required':['key','text']}}},'required':['items']}
    prompt='''Translate Android UI strings from English to natural Modern Standard Arabic. Keep all keys exactly. Preserve printf placeholders (%s, %1$s, %d), XML/HTML tags, escaped apostrophes, URLs, file extensions, code identifiers, model/provider/product names, and technical acronyms. Do not translate strings that are only symbols or identifiers. Keep concise and suitable for mobile UI. Return only the requested JSON.'''
    payload=[{'key':k,'text':v} for k,v in pairs]
    for attempt in range(4):
        try:
            r=client.chat.completions.create(model='gpt-5-mini',messages=[{'role':'system','content':prompt},{'role':'user','content':json.dumps(payload,ensure_ascii=False)}],response_format={'type':'json_schema','json_schema':{'name':'android_arabic_strings','strict':True,'schema':schema}},max_completion_tokens=7000)
            out={x['key']:x['text'] for x in json.loads(r.choices[0].message.content)['items']}
            if set(out)!=set(k for k,_ in pairs): raise ValueError('key mismatch')
            for k,v in pairs:
                if placeholders(v)!=placeholders(out[k]): raise ValueError(f'placeholder mismatch: {k}')
            return out
        except Exception:
            if attempt==3: raise
            time.sleep(2**attempt)

def main():
    root=ET.parse(SRC).getroot(); pairs=collect(root)
    cache=json.loads(CACHE.read_text()) if CACHE.exists() else {}
    pending=[p for p in pairs if p[0] not in cache]
    print(f'{len(pairs)} strings; {len(cache)} cached; {len(pending)} pending')
    if pending:
        client=OpenAI()
        for i in range(0,len(pending),BATCH):
            got=translate(client,pending[i:i+BATCH]); cache.update(got); CACHE.parent.mkdir(parents=True,exist_ok=True); CACHE.write_text(json.dumps(cache,ensure_ascii=False,indent=2)+'\n'); print(f'{min(i+BATCH,len(pending))}/{len(pending)}')
    for el in root.iter():
        if el.tag=='string' and el.attrib.get('name') in cache and translatable(el): el.text=cache[el.attrib['name']]
        elif el.tag=='item' and translatable(el) and (el.text or '').strip():
            for parent in root.iter():
                if el in list(parent) and parent.tag in ('plurals','string-array','array'):
                    key=f"{parent.attrib['name']}__{list(parent).index(el)}"
                    if key in cache: el.text=cache[key]
                    break
    DEST.parent.mkdir(parents=True,exist_ok=True); ET.indent(root,space='  '); ET.ElementTree(root).write(DEST,encoding='utf-8',xml_declaration=True); print(DEST)
if __name__=='__main__': main()
