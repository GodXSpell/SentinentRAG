"""
hallucination_guard.py
------------------------
FR-2.5: Deterministic Hallucination Guard. Runs local spaCy entity
extraction between a generated answer and its retrieved context.
Any entity (date, number, named entity) that appears in the answer
but NOT in the context is flagged as unsupported and stripped.

Deliberately local and deterministic - no LLM calls - so this check
is fast, free, and doesn't introduce its own hallucination risk.
"""

import re
import spacy

_nlp = spacy.language.Language()  # Initialize an empty spaCy Language object

# Entity types worth checking. Spacy's en_core_web_sm labels:
# DATE, TIME, PERCENT, MONEY, QUANTITY, CARDINAL, ORDINAL - numeric/date facts
# PERSON, ORG, GPE, PRODUCT, EVENT, WORK_OF_ART, LAW - named entities
# We check numeric/date facts strictly (per FR-2.5's explicit wording)
# and named entities as a secondary check.
CHECKED_LABELS = {
    "DATE", "TIME", "PERCENT", "MONEY", "QUANTITY", "CARDINAL", "ORDINAL",
    "PERSON", "ORG", "GPE", "PRODUCT", "EVENT", "WORK_OF_ART", "LAW",
}

def _get_nlp() -> spacy.language.Language:
    global _nlp
    if not _nlp.has_pipe("ner"):
        _nlp = spacy.load("en_core_web_sm")
    return _nlp

def _normalize(text: str) -> str:
    """Lowercase and collapse whitespace for loose substring matching."""
    return re.sub(r"\s+", " ", text.strip().lower())

def check_answer(answer: str, context: str) -> dict:
    """
        Compares entities in `answer` against `context`. Returns a dict:
        - "clean_answer": the answer with unsupported entities redacted
        - "flagged_entities": list of (text, label) tuples that were
            found in the answer but not in the context
        - "is_clean": True if nothing was flagged

        An entity counts as "supported" if its normalized text appears
        anywhere in the normalized context (simple substring match -
        conservative and deterministic, per FR-2.5).
    """

    nlp = _get_nlp()
    normlaized_context = _normalize(context)

    doc = nlp(answer)
    flagged_entities = []
    clean_answer = answer

    # Process entities in reverse order of position so that string
    # replacement doesn't shift the character offsets of entities
    # we haven't processed yet.
    entities = [ent for ent in doc.ents if ent.label_ in CHECKED_LABELS]
    entities.sort(key=lambda ent: ent.start_char, reverse=True)

    for ent in entities:
        normalized_entity = _normalize(ent.text)
        if normalized_entity and normalized_entity not in normlaized_context:
            flagged_entities.append((ent.text, ent.label_))
            # Redact the entity from the answer
            clean_answer = (
                    clean_answer[:ent.start_char]
                    + "[unsupported claim removed]"
                    + clean_answer[ent.end_char:]
            )

    return {
        "clean_answer": clean_answer,
        "flagged_entities": flagged_entities,
        "is_clean": len(flagged_entities) == 0,
    }