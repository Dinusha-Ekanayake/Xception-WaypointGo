#!/usr/bin/env python3
"""Writes docs/data-model.md from a database migrated with this repository's
migrations, so the data model page is generated from the live schema rather
than drawn by hand.

    createdb waypoint_docs
    (cd backend && DATABASE_URL=postgresql://you@127.0.0.1:5432/waypoint_docs \
        mvn spring-boot:run -Dspring-boot.run.arguments=migrate)
    scripts/data-model.py "postgresql://you@127.0.0.1:5432/waypoint_docs"

Reads the catalog only (tables, columns, keys, comments) through psql. Writes
nothing to the database. Audit partitions are folded into their parent table.
"""
import json
import subprocess
import sys
from collections import defaultdict
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / "docs" / "data-model.md"

# Module schemas in the order data flows through the day, then the support schemas.
ORDER = ["ref", "iam", "ordering", "warehouse", "planning", "loading", "execution", "receipt",
         "issues", "notification", "sync", "ml", "integration"]
OWNER = {
    "ref": "Reference data", "iam": "Identity and access", "ordering": "Ordering", "warehouse": "Warehouse",
    "planning": "Planning", "loading": "Loading", "execution": "Execution", "receipt": "Receipt",
    "issues": "Issues", "notification": "Notification", "sync": "Sync", "ml": "Intelligence",
    "integration": "Platform (outbox, inbox, receipts, audit, jobs)",
}

CATALOG = """
with t as (
  select c.oid, n.nspname as schema, c.relname as name, obj_description(c.oid) as comment
  from pg_class c join pg_namespace n on n.oid = c.relnamespace
  where c.relkind in ('r', 'p') and not c.relispartition
    and n.nspname = any(%(schemas)s)
)
select json_build_object(
  'tables', (select json_agg(json_build_object(
      'schema', t.schema, 'name', t.name, 'comment', t.comment,
      'columns', (select json_agg(json_build_object(
          'name', a.attname, 'type', format_type(a.atttypid, a.atttypmod), 'notnull', a.attnotnull)
          order by a.attnum)
        from pg_attribute a where a.attrelid = t.oid and a.attnum > 0 and not a.attisdropped),
      'pk', (select array_agg(a.attname order by a.attnum) from pg_index i
             join pg_attribute a on a.attrelid = i.indrelid and a.attnum = any(i.indkey)
             where i.indrelid = t.oid and i.indisprimary),
      'unique', (select json_agg(cols) from (
             select array_agg(a.attname order by a.attnum) as cols from pg_constraint k
             join pg_attribute a on a.attrelid = k.conrelid and a.attnum = any(k.conkey)
             where k.conrelid = t.oid and k.contype = 'u' group by k.oid) u)
    ) order by t.schema, t.name) from t),
  'fks', (select json_agg(json_build_object(
      'from', fn.nspname || '.' || fc.relname, 'to', tn.nspname || '.' || tc.relname,
      'cols', (select array_agg(a.attname order by a.attnum) from pg_attribute a
               where a.attrelid = k.conrelid and a.attnum = any(k.conkey))))
    from pg_constraint k
    join pg_class fc on fc.oid = k.conrelid join pg_namespace fn on fn.oid = fc.relnamespace
    join pg_class tc on tc.oid = k.confrelid join pg_namespace tn on tn.oid = tc.relnamespace
    where k.contype = 'f' and not fc.relispartition and fn.nspname = any(%(schemas)s))
)
"""


def catalog(url):
    sql = CATALOG.replace("%(schemas)s", "array[" + ",".join(f"'{s}'" for s in ORDER) + "]")
    out = subprocess.run(["psql", url, "-At", "-c", sql], capture_output=True, text=True, check=True)
    return json.loads(out.stdout)


def short(type_name):
    return (type_name.replace("timestamp with time zone", "timestamptz").replace("character varying", "varchar")
            .replace("timestamp without time zone", "timestamp").replace("double precision", "float8"))


def mermaid_type(type_name):
    # Mermaid types are one word: keep the base type, drop precision and spaces.
    base = short(type_name).split("(")[0].replace("[]", "_array").replace(" ", "_")
    return base or "text"


def key_columns(table, fk_cols):
    """The columns a reader needs to see how rows connect: keys, references, status, versions."""
    pk = set(table["pk"] or [])
    uniq = {c for cols in (table["unique"] or []) for c in cols}
    keep = []
    for c in table["columns"]:
        n = c["name"]
        if (n in pk or n in fk_cols or n in uniq or n.endswith("_id") or n.endswith("_code")
                or n in ("status", "row_version", "plan_version", "version", "service_date", "event_type")):
            mark = "PK" if n in pk else "FK" if n in fk_cols else "UK" if n in uniq else ""
            keep.append((mermaid_type(c["type"]), n, mark))
    return keep[:14]


def main(url):
    data = catalog(url)
    tables = data["tables"] or []
    fks = data["fks"] or []
    by_schema = defaultdict(list)
    for t in tables:
        by_schema[t["schema"]].append(t)
    fk_cols = defaultdict(set)
    for f in fks:
        fk_cols[f["from"]].update(f["cols"])

    total = len(tables)
    lines = [
        "# Data model",
        "",
        f"Generated from the live migrations by `scripts/data-model.py`: {total} tables in {len(by_schema)} schemas. "
        "Regenerate after any migration; do not edit by hand. The design reasoning, findings and target model are in "
        "[DATA-MODEL-REVIEW.md](architecture/DATA-MODEL-REVIEW.md); the components that own each schema are in "
        "[architecture.md](architecture.md).",
        "",
        "## How the schemas connect",
        "",
        "Each module owns one schema and is the only writer to it. Foreign keys point only into `ref` and `iam`; "
        "every other reference between modules is by id with no foreign key, so one module's migration never waits "
        "on another's (AGENTS.md, Data and Migration Rules). Lines below are real foreign keys between schemas.",
        "",
        "```mermaid",
        "flowchart LR",
    ]
    edges = defaultdict(int)
    for f in fks:
        a, b = f["from"].split(".")[0], f["to"].split(".")[0]
        if a != b:
            edges[(a, b)] += 1
    for s in ORDER:
        if s in by_schema:
            n = len(by_schema[s])
            lines.append(f'  {s}["{s}<br/>{OWNER[s]}<br/>{n} {"table" if n == 1 else "tables"}"]')
    for (a, b), n in sorted(edges.items()):
        lines.append(f"  {a} -->|{n} FK| {b}")
    lines += ["```", "", "Conventions: UUIDv7 surrogate keys with dataset identifiers kept as unique natural keys, "
              "`timestamptz` everywhere, `numeric` with explicit precision for weight, volume and fuel, and `row_version` "
              "checked on every update. Operational tables have forced row-level security by depot, outlet or actor, and "
              "module roles hold no `DELETE`: operational rows reach terminal states instead. Diagrams show the columns "
              "that connect rows (keys, references, status, versions); the table under each lists every table's purpose "
              "from its `COMMENT ON TABLE`.", ""]

    for s in ORDER:
        if s not in by_schema:
            continue
        ts = sorted(by_schema[s], key=lambda t: t["name"])
        lines += [f"## `{s}`: {OWNER[s]}", ""]
        lines += ["```mermaid", "erDiagram"]
        local = {f"{s}.{t['name']}" for t in ts}
        for t in ts:
            full = f"{s}.{t['name']}"
            cols = key_columns(t, fk_cols[full])
            lines.append(f"  {t['name']} {{")
            for typ, name, mark in cols:
                lines.append(f"    {typ} {name}{(' ' + mark) if mark else ''}")
            lines.append("  }")
        for f in fks:
            if f["from"] in local:
                src = f["from"].split(".")[1]
                dst_schema, dst = f["to"].split(".")
                target = dst if dst_schema == s else f"{dst_schema}_{dst}"
                lines.append(f'  {target} ||--o{{ {src} : "{", ".join(f["cols"])}"')
        lines += ["```", ""]
        lines += ["| Table | Purpose | Columns |", "| --- | --- | --- |"]
        for t in ts:
            purpose = (t["comment"] or "").replace("|", "\\|").replace("\n", " ")
            lines.append(f"| `{t['name']}` | {purpose} | {len(t['columns'])} |")
        lines.append("")

    text = "\n".join(lines).rstrip() + "\n"
    if "\u2014" in text or "\u2013" in text:
        text = text.replace("\u2014", ", ").replace("\u2013", "-")
    OUT.write_text(text)
    print(f"wrote {OUT.relative_to(ROOT)}: {total} tables, {len(fks)} foreign keys")


if __name__ == "__main__":
    if len(sys.argv) != 2:
        sys.exit(__doc__)
    main(sys.argv[1])
