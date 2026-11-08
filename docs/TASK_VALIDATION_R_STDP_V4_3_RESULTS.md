# V4.3 — Decision-Boundary Temporal-Credit Results

**Status:** AWAITING USER RUN  
**Date:** 8 November 2026

The protocol and reference implementation are published, but no V4.3 test or benchmark has been run at publication time. No result or learning improvement is claimed.

**Pre-run audit correction (8 November 2026):** a static review found that the earlier assertion only required the skipped-event count to be non-negative, which did not prove that the decision latch actually skipped anything. The test and diagnostics were strengthened without changing the learning rule or neuron behavior:
- sensory inputs are counted only after process_neuron_batch returns;
- post-decision sensory inputs are counted independently from plasticity updates;
- the decision-latched training condition must report a strictly positive skipped-event count, and post-decision sensory-input count must equal skipped plasticity count;
- total processed sensory inputs must equal three per training episode;
- a matched FAST episode must retain identical spike times and prediction under frozen V4 and decision-latched policies.

These checks are **AUTHORED / NOT RUN**. The implementation, tests and benchmark still await user execution.

Run on Android Termux:

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
