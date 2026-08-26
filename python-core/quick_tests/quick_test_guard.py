# quick_test_guard.py
from app.guard.hallucination_guard import check_answer

context = "Overfitting is the production of an analysis that corresponds too closely or exactly to a particular set of data, which may cause it to fail to fit additional data."

# A clean answer (should have zero flagged entities)
clean = "Overfitting occurs when a model fits training data too closely."
result = check_answer(clean, context)
print("CLEAN TEST:", result["is_clean"], result["flagged_entities"])

# An answer with an invented, unsupported fact
hallucinated = "Overfitting was first identified by John Smith in 1995 and affects 80% of models."
result2 = check_answer(hallucinated, context)
print("\nHALLUCINATED TEST:")
print("is_clean:", result2["is_clean"])
print("flagged:", result2["flagged_entities"])
print("clean_answer:", result2["clean_answer"])