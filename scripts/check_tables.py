import sqlite3, os
p='data/pipeline.db'
if not os.path.exists(p):
    print('DB not found', p)
else:
    conn=sqlite3.connect(p)
    cur=conn.cursor()
    rows = cur.execute("SELECT name FROM sqlite_master WHERE type='table' ORDER BY name").fetchall()
    print('Tables:', [r[0] for r in rows])
    # show counts for key tables
    for t in ['jobs','job_items','job_results','job_aggregates']:
        try:
            cnt = cur.execute(f"SELECT count(*) FROM {t}").fetchone()[0]
            print(f"{t}: {cnt}")
        except Exception as e:
            print(f"{t}: error ->", e)
    conn.close()
