# Sequence decoder toy dataset v1

These files are intentionally small synthetic fixtures for checking the Android training/evaluation workflow. They are not a production corpus and are not a measure of Russian language ability.

## Training

Import `train_ru_toy.txt` in the app's **Обучение** tab. Each non-empty line is one complete sequence:

`Вопрос: ... Ответ: ...`

Train only on this file for the toy experiment.

## Held-out evaluation

Copy the contents of `heldout_ru_toy.txt` into the app's independent evaluation field. Each line uses:

`prompt => expected continuation`

The prompt deliberately ends with `Ответ:`, because the model was trained on complete question/answer sequences and generation continues from that prefix.

Do not import the held-out file as training data or apply its answers as corrections. Do not repeatedly tune on these eight cases and then report their score as an unbiased result.

## Limitations

The train and held-out sets are small, synthetic, and stylistically related. They share a narrow request/response format and much of their vocabulary. They are useful only for testing data import, training, generation, and metric reporting. They do not establish semantic understanding, factual accuracy, broad generalization, or usefulness in open-ended conversation.
