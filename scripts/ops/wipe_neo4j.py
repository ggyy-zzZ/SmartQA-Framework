#!/usr/bin/env python
"""仅清空 Neo4j，不灌数据。"""
import argparse
import os
import sys
from neo4j import GraphDatabase

NEO4J_WIPE_CYPHER = """
MATCH (n) DETACH DELETE n
"""


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--neo4j-uri", default=os.environ.get("NEO4J_URI", "bolt://localhost:7687"))
    ap.add_argument("--neo4j-username", default=os.environ.get("NEO4J_USERNAME", ""))
    ap.add_argument("--neo4j-password", default=os.environ.get("NEO4J_PASSWORD", ""))
    args = ap.parse_args()

    auth = (args.neo4j_username or None, args.neo4j_password or None)
    auth = tuple(a for a in auth if a) or None
    driver = GraphDatabase.driver(args.neo4j_uri, auth=auth)
    try:
        with driver.session() as session:
            print(f"[neo4j] wipe start: uri={args.neo4j_uri}")
            result = session.run(NEO4J_WIPE_CYPHER)
            counters = result.consume().counters
            print(f"[neo4j] wiped nodes counters: nodes_deleted={counters.nodes_deleted} rels_deleted={counters.relationships_deleted}")
        print("[neo4j] wipe done")
    finally:
        driver.close()


if __name__ == "__main__":
    try:
        main()
    except Exception as e:
        print(f"[neo4j] FAILED: {e}", file=sys.stderr)
        sys.exit(1)
