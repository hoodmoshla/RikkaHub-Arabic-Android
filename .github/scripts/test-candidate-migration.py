#!/usr/bin/env python3
"""Host smoke test of the actual 31->32 SQL; not a replacement for Room on Android."""
import ast
import json
from pathlib import Path
import re
import sqlite3

ROOT = Path(__file__).resolve().parents[2]
SCHEMAS = ROOT / 'app/schemas/me.rerere.rikkahub.data.db.AppDatabase'
MIGRATIONS = ROOT / 'app/src/main/java/me/rerere/rikkahub/data/db/migrations'


def main():
    schema = json.loads((SCHEMAS / '31.json').read_text())['database']
    db = sqlite3.connect(':memory:')
    for entity in schema['entities']:
        db.execute(entity['createSql'].replace('${TABLE_NAME}', entity['tableName']))
        for index in entity.get('indices', []):
            db.execute(index['createSql'].replace('${TABLE_NAME}', entity['tableName']))
    db.execute("INSERT INTO ConversationEntity (id,assistant_id,title,nodes,create_at,update_at) VALUES ('conv','assistant','محادثة عربية','[]',1000,1000)")
    messages = json.dumps([
        {'role': 'user', 'content': 'مرحبا'},
        {'role': 'assistant', 'createdAt': '2026-08-12T09:01:00', 'modelId': 'model-a', 'usage': {'promptTokens': 100, 'completionTokens': 40, 'cachedTokens': 20}},
        {'role': 'assistant', 'createdAt': 'invalid', 'modelId': 'model-b', 'usage': {'promptTokens': 50, 'completionTokens': 10}},
        {'role': 'assistant', 'usage': {'promptTokens': 0, 'completionTokens': 0}},
    ], ensure_ascii=False)
    db.execute("INSERT INTO message_node (id,conversation_id,node_index,messages,select_index) VALUES ('node','conv',0,?,0)", (messages,))
    db.execute("INSERT INTO message_node (id,conversation_id,node_index,messages,select_index) VALUES ('invalid','conv',1,'not json',0)")
    before = db.execute('SELECT * FROM message_node ORDER BY id').fetchall()
    source = (MIGRATIONS / 'Migration_31_32.kt').read_text()
    statements = re.findall(r'db\.execSQL\(("[^\n]*")\)|db\.execSQL\(\n(.*?)\n {12}\)', source, re.S)
    count = 0
    for single, multiline in statements:
        body = multiline or single
        if '"""' in body:
            sql = body.split('"""')[1].strip()
        else:
            sql = ''.join(ast.literal_eval(s) for s in re.findall(r'"(?:[^"\\]|\\.)*"', body))
        db.execute(sql)
        count += 1
    assert count == 3, f'Expected three SQL statements, got {count}'
    assert db.execute('SELECT * FROM message_node ORDER BY id').fetchall() == before
    assert db.execute('SELECT title FROM ConversationEntity').fetchone()[0] == 'محادثة عربية'
    assert db.execute('SELECT prompt_tokens,completion_tokens,cached_tokens FROM usage_record ORDER BY id').fetchall() == [(100,40,20),(50,10,0)]
    assert db.execute("SELECT created_at FROM usage_record WHERE model_id='model-b'").fetchone()[0] == 1000
    expected = json.loads((SCHEMAS / '32.json').read_text())['database']
    usage = next(e for e in expected['entities'] if e['tableName'] == 'usage_record')
    assert [c[1] for c in db.execute('PRAGMA table_info(usage_record)')] == [f['columnName'] for f in usage['fields']]
    assert (MIGRATIONS / 'Migration_30_31.kt').exists()
    print('PASS: actual Kotlin migration SQL, schema31->32, Arabic conversation/messages preserved, usage backfill, invalid JSON and timestamp fallback')
    print('NOTE: host SQLite smoke test only; Android Room instrumentation remains required.')


if __name__ == '__main__':
    main()
