#!/usr/bin/env python3
"""SDK-free SQL smoke test of the exact additive migration; not Room's schema/identity validation."""
import re
import sqlite3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
MIGRATION = ROOT / "core/vault/src/main/java/com/swagdroid/core/vault/db/VaultMigrations.kt"
SQL = re.findall(r'db\.execSQL\("([^"\n]+)"\)', MIGRATION.read_text(encoding="utf-8"))
assert len(SQL) == 5, "Review this check when the migration changes"

V1 = """CREATE TABLE vault_snapshots (
    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
    title TEXT NOT NULL, reason TEXT NOT NULL, sourceName TEXT NOT NULL,
    createdAtEpochMs INTEGER NOT NULL, rolledBackAtEpochMs INTEGER,
    privilegeTierName TEXT NOT NULL, statusName TEXT NOT NULL, operationsJson TEXT NOT NULL
)"""
ROWS = [
    (7, "Legacy setting", "keep", "FIXER", 100, None, "ADB_SHIZUKU", "ACTIVE", '[{"type":"setting_change","key":"a","previousValue":null}]'),
    (12, "Failed undo", "keep", "OPTIMIZER", 90, None, "ROOT", "FAILED", '[{"type":"shell_revert","applyCommand":"old","revertCommand":"true"}]'),
    (15, "Corrupt payload", "keep raw bytes", "MANUAL", 50, None, "BASIC", "ACTIVE", "{broken-json"),
]


def fixture():
    db = sqlite3.connect(":memory:")
    db.execute(V1)
    db.executemany("INSERT INTO vault_snapshots VALUES (?,?,?,?,?,?,?,?,?)", ROWS)
    db.commit()
    return db


with fixture() as db:
    db.execute("BEGIN")
    for statement in SQL:
        db.execute(statement)
    db.commit()
    names = [row[1] for row in db.execute("PRAGMA table_info(vault_snapshots)")]
    assert len(names) == 14
    original = db.execute("SELECT id,title,reason,sourceName,createdAtEpochMs,rolledBackAtEpochMs,privilegeTierName,statusName,operationsJson FROM vault_snapshots ORDER BY id").fetchall()
    assert original == ROWS, "Migration must not reinterpret, drop or rewrite old journal data"
    defaults = db.execute("SELECT journalVersion,userId,bootId,stepsJson,lastError FROM vault_snapshots").fetchall()
    assert defaults == [(0, None, None, "[]", None)] * len(ROWS)
    assert [r[0] for r in db.execute("SELECT id FROM vault_snapshots ORDER BY id DESC")] == [15, 12, 7]
    schema = {row[1]: row for row in db.execute("PRAGMA table_info(vault_snapshots)")}
    assert schema["journalVersion"][2:5] == ("INTEGER", 1, "0")
    assert schema["stepsJson"][2:5] == ("TEXT", 1, "'[]'")
    assert all(schema[name][3] == 0 for name in ("userId", "bootId", "lastError"))

with fixture() as db:
    db.execute("BEGIN")
    try:
        for statement in SQL[:3]:
            db.execute(statement)
        raise RuntimeError("simulate migration failure")
    except RuntimeError:
        db.rollback()
    assert len(list(db.execute("PRAGMA table_info(vault_snapshots)"))) == 9
    assert db.execute("SELECT * FROM vault_snapshots ORDER BY id").fetchall() == ROWS

print("Vault migration SQL: old rows/raw JSON/IDs preserved; defaults/types match; transaction rollback preserved v1.")
print("This does not replace an Android Room migration test or APK build.")
