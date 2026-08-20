# quick_test.py — run from your project root
from app.ingestion.chunker import chunk_document

with open("../data/corpus/machine_learning.txt", encoding="utf-8") as f:
    text = f.read()

chunks = chunk_document(text, source_doc="machine_learning.txt")
print(f"{len(chunks)} chunks total")

for c in chunks[:5]:
    print(f"\n--- chunk {c.chunk_index} | section: {c.section_title!r} | words: {len(c.text.split())} ---")
    print(c.text[:150], "...")