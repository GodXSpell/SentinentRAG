"""
chunker.py
----------
Splits Markdown/plain text into retrieval-sized chunks.

STRATEGY:
  1. First split on section headers (Markdown "#"/"##" or Wikipedia-style
     "== Header ==" / "=== Subheader ===") to preserve semantic boundaries.
  2. For any resulting section still longer than `max_tokens`, hard-split
     it further using a sliding window of `max_tokens` with `overlap_tokens`
     of overlap between consecutive chunks (so context isn't lost at cuts).
  3. Attach metadata to every chunk: source document name, section title,
     and a chunk index.

We approximate "tokens" as whitespace-split words. This is not exact
(a real tokenizer would differ slightly) but it's a fine, fast,
dependency-free approximation for chunk-sizing purposes.
"""

import re
from dataclasses import dataclass, field

@dataclass
class Chunk:
    text: str
    source_doc: str  # e.g. "machine_learning.txt"
    section_title: str  # e.g. "Overfitting" or "" if no header found
    chunk_index: int  # position of this chunk within the document
    metadata: dict = field(default_factory=dict)

def split_into_sections(raw_text: str) -> list[tuple[str, str]]:
    """
    Split raw text into (section_title, section_body) pairs using
    header markers. Supports Wikipedia-style "== Header ==" and
    Markdown-style "# Header" / "## Header".

    Returns a list of (title, body) tuples, in document order.
    If no headers are found, returns a single tuple: ("", raw_text).
    """
    # Matches either:
    #   == Header ==   or   === Subheader ===      (Wikipedia style)
    #   # Header       or   ## Subheader            (Markdown style)
    header_pattern = re.compile(
        r'^(={2,3}\s*.+?\s*={2,3}|#{1,3}\s+.+)$',
        flags=re.MULTILINE,
    )

    # re.split with a capturing group keeps the matched headers in the
    # result list, interleaved with the text between them.
    parts = header_pattern.split(raw_text)
    sections: list[tuple[str, str]] = []

    # If the very first part isn't a header, it's body text that came
    # before any header (e.g. an intro paragraph) — keep it under ""
    if parts and not header_pattern.match(parts[0].strip()):
        intro = parts[0].strip()
        if intro:
            sections.append(("", intro))
        parts = parts[1:]

    # Now parts should alternate: [header, body, header, body, ...]
    for i in range(0, len(parts) - 1, 2):
        raw_header = parts[i].strip()
        body = parts[i + 1].strip()

        title = raw_header.strip("= #").strip()

        if body:
            sections.append((title, body))
    if not sections:
        return [("", raw_text.strip())]

    return sections

def split_section_into_chunks(
        section_title: str,
        body: str,
        max_tokens: int,
        overlap_tokens: int,
) -> list[str]:
    """
    Split a single section's body into token-bounded chunks with overlap.
    "Tokens" here means whitespace-split words.
    """
    assert overlap_tokens < max_tokens, (
        "overlap_tokens must be smaller than max_tokens, "
        "otherwise the sliding window never advances"
    )

    words = body.split()
    if len(words) <= max_tokens:
        return [body]

    chunks = []
    start = 0
    step = max_tokens - overlap_tokens
    while start < len(words):
        window = words[start : start + max_tokens]
        chunks.append(" ".join(window))
        start += step

    return chunks

def chunk_document(
    raw_text: str,
    source_doc: str,
    max_tokens: int = 400,
    overlap_tokens: int = 50,
) -> list[Chunk]:
    """
        Full pipeline: raw text -> sections -> token-bounded chunks -> Chunk objects.
    """
    sections = split_into_sections(raw_text)

    chunks: list[Chunk] = []
    idx = 0
    for section_title, body in sections:
        body = body.strip()
        if not body:
            continue

        pieces = split_section_into_chunks(
            section_title, body, max_tokens, overlap_tokens
        )
        for piece in pieces:
            chunks.append(
                Chunk(
                    text=piece,
                    source_doc=source_doc,
                    section_title=section_title,
                    chunk_index=idx,
                    metadata={"char_count": len(piece)},
                )
            )
            idx += 1

    return chunks













