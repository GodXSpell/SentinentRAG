# # debug_headers.py
# with open("data/corpus/machine_learning.txt", encoding="utf-8") as f:
#     text = f.read()
#
# # Show raw structure around the first "History" mention
# idx = text.find("History")
# print(repr(text[idx-50:idx+100]))
# print("---")
# print("Total words in file:", len(text.split()))
# print("Total chars:", len(text))
from app.ingestion.chunker import split_into_sections

with open("../data/corpus/machine_learning.txt", encoding="utf-8") as f:
    text = f.read()

sections = split_into_sections(text)
for title, body in sections:
    print(f"{title!r:30} -> {len(body.split())} words")