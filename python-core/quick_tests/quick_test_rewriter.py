from app.correction.query_rewriter import rewrite_query

results = rewrite_query("what is overfitting")
for r in results:
    print(r)