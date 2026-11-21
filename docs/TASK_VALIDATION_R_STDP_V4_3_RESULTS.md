# V4.3 — Decision-Boundary Temporal-Credit Results

**Status:** user-run tests PASS; smoke and pilot completed; exploratory result  
**Date:** 8 November 2026

The protocol and reference implementation are published, but no V4.3 test or benchmark has been run at publication time. No result or learning improvement is claimed.

**Pre-run audit correction (8 November 2026):** a static review found that the earlier assertion only required the skipped-event count to be non-negative, which did not prove that the decision latch actually skipped anything. The test and diagnostics were strengthened without changing the learning rule or neuron behavior:
- sensory inputs are counted only after process_neuron_batch returns;
- post-decision sensory inputs are counted independently from plasticity updates;
- the decision-latched training condition must report a strictly positive skipped-event count, and post-decision sensory-input count must equal skipped plasticity count;
- total processed sensory inputs must equal three per training episode;
- a matched FAST episode must retain identical spike times and prediction under frozen V4 and decision-latched policies.

These checks are **AUTHORED / NOT RUN**. The implementation, tests and benchmark still await user execution.

## User-run result (Android Termux, 8 November 2026)

- Dedicated tests: **7/7 PASS, 1.636 s**.
- Smoke: all five conditions completed; Decision-Latched accuracy 76.5625%; processed 384 inputs, including 64 post-decision inputs, and skipped 64 post-decision plasticity updates.
- Pilot: two seeds, 512 training pairs and 128 held-out pairs per seed (2,048 train and 512 held-out episodes pooled per condition).

| Condition | Held-out accuracy | Balanced accuracy | Macro-F1 | FP | FN |
|---|---:|---:|---:|---:|---:|
| FIXED | 81.8359% | 81.8359% | 0.812162 | 93 | 0 |
| STDP | 100% | 100% | 1.000000 | 0 | 0 |
| R-STDP V4 | 83.0078% | 83.0078% | 0.825026 | 87 | 0 |
| Decision-Latched R-STDP | 82.2266% | 82.2266% | 0.816468 | 91 | 0 |
| R-STDP Neutral | 81.8359% | 81.8359% | 0.812162 | 93 | 0 |

Decision-Latched was 0.78125 percentage points below V4 and 16.796875 points below STDP. It processed all 6,144 sensory inputs, including 1,024 post-decision inputs; exactly 1,024 post-decision plasticity events were skipped. It produced 1,024 positive / 343 negative / 681 zero weight updates. The policy is mechanically verified but not promoted as a better learner. Keep V4 frozen as the R-STDP control; STDP is best only on this synthetic benchmark.

No full repository suite, build, CI or GitHub Actions was run.

The following commands are retained for reproducing the experiment (do not rerun full solely to seek a reversal of this pilot):

```sh
cd ~/jarvis-ans100-alpha
git pull --ff-only origin main
python3 -m unittest tools.task_validation.test_rstdp_feedback_v4_3 -v
python3 -m tools.task_validation.rstdp_feedback_v4_3 --profile smoke > rstdp_feedback_v4_3_smoke.json
python3 -m tools.task_validation.rstdp_feedback_v4_3 --profile pilot > rstdp_feedback_v4_3_pilot.json
```

Inspect the aggregate results:

```sh
python3 - <<'PY'
import json
for path in ("rstdp_feedback_v4_3_smoke.json", "rstdp_feedback_v4_3_pilot.json"):
    with open(path) as f:
        report = json.load(f)
    print("\\n===", path, "===")
    for row in report["aggregate_conditions"]:
        d = row["training_diagnostics"]
        h = row["held_out"]
        print(
            row["condition"],
            "accuracy=", h["accuracy"],
            "balanced=", h["balanced_accuracy"],
            "macro_f1=", h["macro_f1"],
            "FP=", h["fp"], "FN=", h["fn"],
            "weight_delta=", d["weight_delta_sum"],
            "updates(+/-/0)=",
            d["positive_weight_delta_count"],
            d["negative_weight_delta_count"],
            d["zero_weight_delta_count"],
            "postdecision_skipped=",
            d["skipped_postdecision_plasticity_event_count"],
        )
    print("per-seed:", report["per_seed_comparisons"])
PY
```

Full repository tests, builds, APK/AAB, CI and GitHub Actions have not been run.
