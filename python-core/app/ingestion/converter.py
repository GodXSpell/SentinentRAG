"""
converter.py
------------
Thin wrapper around MarkItDown. Converts any supported file
(PDF, DOCX, PPTX, XLSX, HTML, plain text, etc.) into clean Markdown text
that downstream chunking can work with.

We use `convert_local()` specifically (not the more permissive `convert()`)
per MarkItDown's own security guidance: prefer the narrowest conversion
API for the input type you actually have.
"""

from pathlib import Path
from markitdown import MarkItDown

_md = MarkItDown()  # stateless, safe to reuse across requests

def convert_to_markdown(filepath: str | Path) -> str:
    """
        Convert a local file to Markdown text.

        Args:
            filepath: path to a local file on disk.

        Returns:
            The file's content as a Markdown string.

        Raises:
            FileNotFoundError: if filepath doesn't exist.
            Exception: propagates MarkItDown conversion errors as-is —
                the caller (ingest route) is responsible for turning
                these into a clean HTTP error response.
    """
    filepath = Path(filepath)
    if not filepath.exists():
        raise FileNotFoundError(f"No such file: {filepath}")

    result = _md.convert_local(str(filepath))
    return result.text_content
