# quick_test_embed.py
from app.ingestion.embedder import embed_text, embed_batch

v = embed_text("Machine learning is a subset of artificial intelligence.")
print("single embed dim:", len(v))

vs = embed_batch(["first chunk of text", "second chunk of text"])
print("batch embed count:", len(vs), "dim:", len(vs[0]))