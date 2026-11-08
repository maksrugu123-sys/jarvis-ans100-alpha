# JARVIS SuperNeuron Research Track 0.1
## Boundary-Triggered Counterfactual Credit (BTCC)

**Status:** PROPOSED / NOT IMPLEMENTED / NOT TESTED / NOT MEASURED  
**Date:** 8 November 2026  
**Scope:** one Adaptive LIF-DT neuron and a small set of excitatory input synapses  
**Relationship to V5:** parallel learning-quality research; does not replace or bypass the V5.1 reference-resource baseline  
**Compatibility:** canonical STARTER V1, Numerical Oracle, frozen V4, V4.1, V4.2 and V4.3 remain unchanged

## 1. Mission: a stronger neuron, not a larger neuron

"SuperNeuron" is a research direction, not a claim of intelligence or consciousness. The intended improvement is to extract more task-relevant information from each input event and to spend extra computation only when it may change learning.

The candidate must improve held-out task quality or causal-credit alignment without an unjustified increase in memory, compute or instability. A larger mechanism count is not an objective.

The first candidate is **Boundary-Triggered Counterfactual Credit (BTCC)**: after a neuron makes an uncertain or incorrect decision, it tests a small number of alternative input histories and estimates how each event affected the task loss.

This is a JARVIS engineering hypothesis assembled from existing scientific ideas. It is not claimed to be a previously unknown scientific principle.

## 2. Evidence motivating the research

### 2.1 JARVIS evidence

The user-run V4.3 pilot on Android Termux used two seeds, 512 training pairs per seed and 128 held-out pairs per seed. Held-out accuracy was:

| Condition | Accuracy |
|---|---:|
| FIXED | 81.8359% |
| STDP | 100% |
| Frozen R-STDP V4 | 83.0078% |
| Decision-Latched R-STDP | 82.2266% |
| Neutral R-STDP | 81.8359% |

Decision-Latched processed all 6,144 sensory inputs and skipped 1,024 post-decision plasticity events, but its accuracy was 0.78125 percentage points below frozen V4. Thus controlling when traces are accumulated changed learning dynamics but did not improve held-out quality. The detailed user result is recorded in TASK_VALIDATION_R_STDP_V4_3_RESULTS.md.

Important limitation: STDP already reached 100% on this small synthetic benchmark. The existing task therefore has a ceiling effect and cannot, by itself, establish that a new method is generally better. BTCC requires a harder, independently designed temporal task before any quality claim.

### 2.2 Existing scientific analogues

The following work establishes that several building blocks are known:

1. **e-prop (Bellec et al., Nature Communications, 2020):** factorizes recurrent-network gradients into local eligibility information and a learning signal, enabling online temporal credit assignment without conventional BPTT. DOI: https://doi.org/10.1038/s41467-020-17236-y
2. **Expected Eligibility Traces (van Hasselt et al., AAAI 2021):** extends ordinary eligibility traces to account for counterfactual states/actions that could have preceded an observed state. DOI: https://doi.org/10.1609/aaai.v35i11.17200
3. **EventProp with learnable delays (Mészáros et al., Nature Communications, 2025):** event-based exact gradients for weights and delays, including recurrent SNNs; the authors report task-specific speed and memory gains. DOI: https://doi.org/10.1038/s41467-025-65394-8
4. **Diffusion of neuromodulators for temporal credit assignment (Barretto-Bittar et al., PNAS, 2026):** diffuses error/credit signals through locally connected recurrent SNNs and reports improvements over e-prop in its sparse-feedback benchmark setting. DOI: https://doi.org/10.1073/pnas.2608831123
5. **Dynamic predictive early stopping for SNN accelerators (Miao & Ikeda, IEICE Electronics Express, 2024):** selectively stops nonessential inference computation; reported resource gains are hardware- and task-specific. DOI: https://doi.org/10.1587/elex.21.20240206

These papers do not establish BTCC. They do establish that local eligibility, counterfactual credit, event-based gradients, modulatory signal propagation and adaptive compute have existing precedents.

**Novelty rule:** BTCC is currently classified as a proposed engineering combination. A claim of scientific novelty requires a broader literature and patent search, direct comparison to related algorithms, and an independently reproducible result. The phrase "nobody has invented this" must not be used as a factual claim.

## 3. Proposed mechanism

### 3.1 Cheap normal path

The neuron first executes its normal Adaptive LIF-DT event path. Existing neuron dynamics, STDP/R-STDP reference implementations and the canonical STARTER profile are not changed.

For each decision episode, record only a bounded candidate-event buffer. The initial V4 diagnostic task has three sensory pulses, so the first experiment needs at most K=3 event records. A later generic profile may evaluate K up to 8, but only after measuring memory and replay cost.

Each candidate event record needs only:
- stable synapse/event handle;
- model timestamp;
- event strength and attention;
- weight/version snapshot identifier required to reproduce the event.

Do not store an unbounded full episode history.

### 3.2 Decision margin

At each time where the Oracle evaluates a spike decision, derive the pre-reset margin:

    m(t) = u_pre_reset(t) - theta_effective(t)

For one episode, define:

    z(E) = max of m(t) over the exact decision-evaluation times
           up to the first output spike, or through the deadline if no spike occurs.

The evaluation-time set must match the simulator's actual event/deadline calls. The implementation must capture the pre-reset potential; it must not infer it from the post-reset NeuronState.

For the binary task, y=1 denotes FAST and y=0 denotes SLOW. A smooth score may be used:

    p(E) = clip(sigmoid(z(E) / T), epsilon, 1 - epsilon)

    L(E, y) = -y * log(p(E)) - (1-y) * log(1-p(E))

T is a dimensionless margin-temperature parameter. Its value must be fixed before held-out evaluation, using only a declared training/calibration subset. No held-out-driven tuning is allowed. A simpler calibrated proper loss may replace this form only if pre-registered before the benchmark.

### 3.3 Bounded counterfactual event test

For a candidate event i, replay the same episode with only event i removed:

    E_minus_i = E without event i

    C_i = L(E_minus_i, y) - L(E, y)

Interpretation:
- C_i > 0: removing event i worsened the loss; the event was beneficial in this episode.
- C_i < 0: removing event i improved the loss; the event was harmful in this episode.
- C_i = 0: the omission did not change the measured loss.

Every shadow replay starts from the same immutable initial neuron/synapse snapshot and uses the same event ordering, parameter-set version and deadline. Shadow paths never modify the real neuron, real weights, reward baseline, eligibility state, or future episodes.

This is a marginal leave-one-event-out score, not a full Shapley value. It does not automatically resolve interactions between multiple events. Event interactions and redundant pulses must be measured separately.

### 3.4 Compute gate

Do not run all counterfactual replays on every episode.

Trigger the audit only when:
- the real prediction is wrong; or
- the decision margin lies within a pre-registered uncertainty band around zero.

Otherwise use the ordinary path and perform no shadow replay.

Two ablations are required:
- **BTCC-ALWAYS:** audit every training episode, to measure the value of counterfactual information without the compute gate.
- **BTCC-GATED:** audit only errors or boundary-uncertain episodes, to measure the resource/quality trade-off.

If the gate saves computation but loses useful learning cases, it fails. Trigger rate, replay count and events processed in shadow paths must be reported.

### 3.5 From causal event score to weight update

For the initial excitatory-only prototype, event sensitivity at the selected decision boundary may be approximated by the local impulse response:

    h_i = gain_i * strength_i * exp(-(t_star - t_i) / tau_m)

where t_star is the selected decision-evaluation time and t_i is the event time. This is only a local sensitivity approximation; it must be checked against finite-difference perturbations of the full Oracle trajectory.

A candidate per-synapse update is:

    q_s = sum(C_i * h_i for events i belonging to synapse s)
          / (epsilon + sum(h_i * h_i for those events))

    delta_w_s = clip(eta_counterfactual * q_s,
                     -delta_w_max, +delta_w_max)

Then apply existing weight bounds and homeostatic limits.

This update equation is a proposal, not a proven gradient identity. It must pass:
- sign agreement against central finite-difference loss gradients on deterministic test episodes;
- tests where removing a pulse improves, worsens or leaves the decision unchanged;
- bounded-update and zero-event tests;
- reproducibility and held-out isolation tests.

If its sign does not align with finite-difference loss descent often enough, reject or reformulate it before task-level training. Do not silently tune the rule until it wins.

The first prototype handles excitatory synapses only. Inhibitory plasticity remains out of scope until separately specified and validated.

## 4. Why this could help

V4.1/V4.2 diagnosed a mismatch between feedback sign and the local eligibility sign. V4.3 altered the set of eligible events, but still did not prove that retained events were causally useful.

BTCC asks a more direct question: "What changes if this event had not happened?" The answer is task-loss difference, rather than timing coincidence alone.

The compute gate adds a second hypothesis: easy, confident episodes need no counterfactual replay; uncertain and failed episodes receive a bounded diagnostic budget.

Potential benefits:
- more selective event credit;
- reduction of updates from irrelevant or harmful pulses;
- a direct diagnostic for redundant events;
- additional computation concentrated on uncertain cases;
- bounded replay history and explicit compute accounting.

Potential risks:
- counterfactual replay can multiply compute cost;
- leave-one-out scores can misattribute interactions;
- a poor margin/loss calibration can create misleading credit;
- the same synthetic task may overfit the mechanism;
- a one-neuron result may not transfer to recurrent networks.

## 5. Experimental plan and controls

### Stage A — mathematical and oracle checks

Before task-level training:
1. Build a deterministic event-replay function that cannot mutate the real state.
2. Capture pre-reset margin correctly, including refractory and deadline edge cases.
3. Validate loss and counterfactual signs with hand-computed examples.
4. Compare BTCC local sensitivity with central finite-difference weight gradients.
5. Test simultaneous events, duplicated IDs, event ordering, omitted first/last pulse, empty episodes, ties, no-spike and first-spike episodes.
6. Test that held-out episodes cannot update weights or trigger training-only state mutation.

### Stage B — V4-compatible diagnostic

Use the existing V4 data generator initially, with:
- canonical STARTER profile unchanged;
- shared diagnostic initial weight 0.70, explicitly not a calibration;
- identical train/held-out splits and input order;
- fixed seeds recorded in the report;
- held-out inference only.

Compare:
- FIXED;
- STDP;
- frozen R-STDP V4;
- neutral R-STDP;
- BTCC-ALWAYS;
- BTCC-GATED.

Because STDP was already at 100% on the V4.3 pilot, Stage B is a mechanism/debug check, not sufficient evidence of superiority.

### Stage C — harder temporal generalization

Create a separate pre-registered task suite with:
- distractor and misleading pulses;
- same-count temporal patterns with variable gaps;
- controlled input noise;
- examples where one pulse is decisive, redundant or harmful;
- a held-out temporal/noise distribution not seen during training.

Compare the same controls and report accuracy, balanced accuracy, macro-F1, precision/recall, FP/FN, per-seed results and uncertainty intervals where sample size permits.

No held-out sample may be used to tune T, uncertainty band, K, learning rate or event selection.

### Stage D — efficiency and quality gate

Report:
- accuracy and error metrics;
- finite-difference gradient sign agreement;
- number of audited episodes;
- shadow replays per episode;
- number of replayed events;
- peak bounded event-buffer size;
- event-path allocations;
- wall-clock/CPU time on a named environment when measured;
- RAM/RSS/PSS where available.

Acceptance requires a repeatable task-quality improvement over frozen R-STDP and fixed controls on the harder task, with no unacceptable regression against the best control. The allowed quality margin and compute budget must be fixed before the main run.

If BTCC raises quality only by spending excessive compute, it does not meet the SuperNeuron objective. If it does not improve quality, retain the result as a negative experiment and do not promote it.

## 6. Proposed integrated SuperNeuron stack

Only after individual mechanisms pass their gates should they be combined:

1. **Neural dynamics:** current Adaptive LIF-DT, unchanged until evidence supports a revision.
2. **Temporal learning:** STDP/R-STDP as reference alternatives, not assumed winners.
3. **Predictive margin:** estimate confidence relative to the output boundary.
4. **Causal audit:** BTCC, only on selected uncertain/error episodes.
5. **Adaptive compute gate:** bound extra work by K, trigger rate and event budget.
6. **Homeostasis:** preserve weight/trace bounds and detect instability.
7. **Compact state:** proceed with the V5.1 baseline and F64 layout gates independently.
8. **Memory:** introduce longer-term predictive/associative memory only after single-neuron quality and resource evidence exists.

No full network, consciousness or general intelligence claim follows from this design.

## 7. Development stages

- **0.1 — research specification:** this document. No code or behavior changes.
- **0.2 — mathematical replay oracle:** deterministic score and leave-one-event-out reference calculations.
- **0.3 — unit and finite-difference tests:** correctness before training.
- **0.4 — V4-compatible mechanism diagnostic:** smoke and pilot.
- **0.5 — harder temporal/noise benchmark:** multiple seeds and untouched held-out distributions.
- **0.6 — compute/memory profiling:** measure shadow replay cost and bounded-buffer overhead.
- **0.7 — decision:** promote, revise once with an explicit hypothesis, or reject.
- **Later:** evaluate adaptive membrane timescale, learnable synaptic delay, shared predictive memory and other mechanisms as separate ablations. Do not combine all candidates in one experiment.

## 8. References

- Bellec et al. (2020), “A solution to the learning dilemma for recurrent networks of spiking neurons,” Nature Communications 11, 3625. https://doi.org/10.1038/s41467-020-17236-y
- van Hasselt et al. (2021), “Expected Eligibility Traces,” AAAI 35(11), 9997–10005. https://doi.org/10.1609/aaai.v35i11.17200
- Mészáros, Knight & Nowotny (2025), “Efficient event-based delay learning in spiking neural networks,” Nature Communications 16, 10422. https://doi.org/10.1038/s41467-025-65394-8
- Barretto-Bittar et al. (2026), “Diffusion of neuromodulators for temporal credit assignment,” PNAS 123(38), e2608831123. https://doi.org/10.1073/pnas.2608831123
- Miao & Ikeda (2024), “Advancing energy efficiency of spiking neural network accelerator via dynamic predictive early stopping,” IEICE Electronics Express 21, 20240206. https://doi.org/10.1587/elex.21.20240206

## 9. Current status

**PROPOSED / NOT IMPLEMENTED / NOT TESTED / NOT MEASURED.**

This specification does not change the Numerical Oracle, neuron equations, STDP/R-STDP, STARTER values, V4 protocols, V5 compact-state specification or resource baseline. No performance claim is made.

Next action: keep the V5.1 reference baseline as its own workstream; then implement the BTCC replay oracle and mathematical tests as a separate research branch within main. Never replace frozen controls.
