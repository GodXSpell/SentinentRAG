from app.correction.query_rewriter import _call_groq, _parse_lines
for attempt in range(5):
    content = _call_groq("how do you stop a model from memorizing training data")
    print(f"--- attempt {attempt+1} ---")
    print("RAW:", repr(content))
    if content:
        lines = _parse_lines(content)
        print(f"PARSED ({len(lines)}):", lines)
    print()
