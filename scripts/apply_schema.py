import sqlite3
import shutil
import time
import os
proj_root = os.path.abspath(os.path.join(os.path.dirname(__file__), '..'))
db_path = os.path.join(proj_root, 'data', 'pipeline.db')
schema_paths = [
    os.path.join(proj_root, 'target', 'classes', 'schema.sql'),
    os.path.join(proj_root, 'src', 'main', 'resources', 'schema.sql')
]
if os.path.exists(db_path):
    bak = db_path + '.bak.' + time.strftime('%Y%m%d%H%M%S')
    shutil.copy2(db_path, bak)
    print('Backup created:', bak)
else:
    # ensure directory exists
    os.makedirs(os.path.dirname(db_path), exist_ok=True)
    print('DB file does not exist, will be created at', db_path)
# Read schema.sql
schema_file = None
for p in schema_paths:
    if os.path.exists(p):
        schema_file = p
        break
if not schema_file:
    print('ERROR: schema.sql not found in expected locations:', schema_paths)
    raise SystemExit(2)
with open(schema_file, 'r', encoding='utf-8') as f:
    sql = f.read()
conn = sqlite3.connect(db_path)
cur = conn.cursor()
try:
    cur.executescript(sql)
    conn.commit()
    print('Applied schema from', schema_file)
    rows = cur.execute("SELECT name FROM sqlite_master WHERE type='table' ORDER BY name").fetchall()
    print('Tables present:')
    for r in rows:
        print(' -', r[0])
finally:
    conn.close()
