#!/usr/bin/env python3
"""
Batch migration of Skill embeddings into Neo4j for large datasets.

Design goals:
- bounded memory usage (streaming batches)
- local embedding inference (no paid API)
- resumable progress via last processed internal id
"""

from __future__ import annotations

import argparse
import json
import time
from pathlib import Path
from typing import List, Tuple

import numpy as np
from neo4j import GraphDatabase
from sentence_transformers import SentenceTransformer


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Migrate Skill embeddings to Neo4j in batches")
    parser.add_argument("--uri", default="bolt://127.0.0.1:7687")
    parser.add_argument("--user", default="neo4j")
    parser.add_argument("--password", required=True)
    parser.add_argument("--model", default="sentence-transformers/all-MiniLM-L6-v2")
    parser.add_argument("--batch-size", type=int, default=1024)
    parser.add_argument("--encode-batch-size", type=int, default=256)
    parser.add_argument("--resume-file", default=".embedding_migration_progress.json")
    parser.add_argument("--skip-existing", action="store_true", default=True)
    parser.add_argument("--max-rows", type=int, default=0, help="0 means no limit")
    return parser.parse_args()


def load_progress(path: Path) -> int:
    if not path.exists():
        return -1
    try:
        payload = json.loads(path.read_text(encoding="utf-8"))
        return int(payload.get("last_internal_id", -1))
    except Exception:
        return -1


def save_progress(path: Path, last_internal_id: int, processed: int) -> None:
    payload = {
        "last_internal_id": last_internal_id,
        "processed": processed,
        "updated_at_epoch": int(time.time()),
    }
    path.write_text(json.dumps(payload, ensure_ascii=True, indent=2), encoding="utf-8")


def fetch_batch(tx, last_internal_id: int, batch_size: int, skip_existing: bool) -> List[Tuple[int, str]]:
    where_embedding = "AND (s.embedding IS NULL OR size(s.embedding) = 0)" if skip_existing else ""
    query = f"""
    MATCH (s:Skill)
    WHERE id(s) > $lastInternalId
      {where_embedding}
      AND coalesce(trim(s.name), '') <> ''
    RETURN id(s) AS internalId, s.name AS name
    ORDER BY id(s)
    LIMIT $batchSize
    """
    records = tx.run(query, lastInternalId=last_internal_id, batchSize=batch_size)
    return [(r["internalId"], r["name"]) for r in records]


def write_batch(tx, rows: List[dict]) -> int:
    query = """
    UNWIND $rows AS row
    MATCH (s:Skill)
    WHERE id(s) = row.internalId
    SET s.embedding = row.embedding
    RETURN count(*) AS updated
    """
    rec = tx.run(query, rows=rows).single()
    return int(rec["updated"] if rec else 0)


def main() -> None:
    args = parse_args()

    resume_path = Path(args.resume_file)
    last_id = load_progress(resume_path)

    model = SentenceTransformer(args.model)

    driver = GraphDatabase.driver(args.uri, auth=(args.user, args.password), max_connection_pool_size=20)

    processed = 0
    total_updated = 0
    started = time.time()

    try:
        while True:
            with driver.session() as session:
                batch = session.execute_read(fetch_batch, last_id, args.batch_size, args.skip_existing)

            if not batch:
                break

            ids = [row[0] for row in batch]
            names = [row[1] for row in batch]

            vectors: np.ndarray = model.encode(
                names,
                batch_size=args.encode_batch_size,
                convert_to_numpy=True,
                normalize_embeddings=True,
                show_progress_bar=False,
            )

            rows = []
            for idx, internal_id in enumerate(ids):
                rows.append(
                    {
                        "internalId": internal_id,
                        "embedding": vectors[idx].astype(np.float32).tolist(),
                    }
                )

            with driver.session() as session:
                updated = session.execute_write(write_batch, rows)

            last_id = ids[-1]
            processed += len(batch)
            total_updated += updated
            save_progress(resume_path, last_id, processed)

            elapsed = max(time.time() - started, 1e-9)
            rate = processed / elapsed
            print(
                f"processed={processed} updated={total_updated} lastInternalId={last_id} "
                f"rate={rate:.1f} skills/sec"
            )

            if args.max_rows > 0 and processed >= args.max_rows:
                break
    finally:
        driver.close()

    elapsed = time.time() - started
    print(f"DONE processed={processed} updated={total_updated} elapsed_sec={elapsed:.1f}")


if __name__ == "__main__":
    main()
