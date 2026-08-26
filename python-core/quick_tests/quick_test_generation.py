# quick_test_generation.py
from app.generation.generate_answer import generate_answer

context = "Overfitting is when a model fits the training data too closely, including its noise, and fails to generalize to new data."
answer = generate_answer("What is overfitting?", context)
print(answer)