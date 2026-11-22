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


## Multi-turn dialogue experiment

For a small topic-switching and topic-return experiment:

- Import `multiturn_train_ru_v1.txt` as training pairs.
- Use `multiturn_heldout_ru_v1.txt` only in the independent held-out field.
- Do not train or fine-tune on held-out rows.

These files use compact recent-dialogue context followed by the current
question, then the expected continuation. They cover two-topic switching,
returning to a deferred subject, and identifying which earlier requirement a
follow-up refers to. They are synthetic fixtures, not a broad Russian dialogue
corpus or a quality benchmark. The held-out set has only eight cases and must
not be repeatedly used for tuning and then presented as an unbiased score.

The decoder limits each prompt/answer pair to 64 subword tokens. If importing
the corpus reports an over-limit row, do not silently truncate it: shorten the
example while preserving the distinguishing context, or leave it out and
record the change.


## In-app multi-turn corpus loading

The same train and held-out text files are bundled as Android assets:

- `app/src/main/assets/multiturn_train_ru_v1.txt`
- `app/src/main/assets/multiturn_heldout_ru_v1.txt`

In the app, open **ANS · нейронное обучение**. Use **Загрузить диалоги** to populate the training field, then explicitly start training. Use **Загрузить отдельные 8 проверок** only in the independent evaluation field after training. The two actions load separate files; the evaluation examples are never appended to training automatically.

The decoder is still a small recurrent sequence model. The corpus tests a narrow task—topic switching and returning to an earlier subject—and does not establish general conversation competence. Evaluate it on-device and inspect every generated answer before drawing conclusions.
