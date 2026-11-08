# JARVIS BTCC 0.2 — Replay Oracle and Gradient-Direction Audit

**Status:** AUTHORED / NOT RUN  
**Date:** 8 November 2026  
**Scope:** deterministic mathematical reference only; no task benchmark or production learning rule

## 1. Implementation

Added:
- \`tools/super_neuron_btcc/credit_oracle.py\`
- \`tools/super_neuron_btcc/test_credit_oracle.py\`

The replay oracle uses the existing Numerical Oracle's \`decay_neuron_state\`, \`attention_gain\`, \`effective_threshold\` and \`process_neuron_batch\`. It groups simultaneous events, preserves stable event ordering and calculates the actual pre-reset membrane margin before calling the state transition.

Binary output remains the actual Oracle result: FAST iff a spike is emitted at an input-event timestamp. A separate smooth score is derived from the maximum pre-reset, non-refractory margin through the first spike. This score is for counterfactual loss diagnostics only; it does not alter the neuron's decision.

The bounded audit tests each event omission from the same immutable initial state and same weight snapshot. Shadow replays cannot change the real state or the caller's weight map.

## 2. Objective and event score

For margin \(z\), temperature \(T>0\), target \(y\in\{0,1\}\), and clipping \(\epsilon\in(0,0.5)\):

\[
p=\operatorname{clip}(\sigma(z/T),\epsilon,1-\epsilon)
\]

\[
L(z,y)=-y\log p-(1-y)\log(1-p)
\]

For event \(i\):

\[
C_i=L(E\setminus\{i\},y)-L(E,y)
\]

Positive \(C_i\) means the omission worsens the loss; negative \(C_i\) means it improves the loss. This is a marginal leave-one-out contribution, not a Shapley value and not a guaranteed causal attribution under interacting/redundant events.

At the selected evaluation time \(t^*\), the first approximation for the event's direct membrane contribution is:

\[
h_i=w_{s(i)}\,g_i\,x_i\,e^{-(t^*-t_i)/\tau_m}
\]

for events no later than \(t^*\), and zero afterwards. Here \(w_{s(i)}\) is the event's excitatory synaptic weight, \(g_i\) is the bounded attention gain and \(x_i\) is event strength.

For synapse \(s\), the least-squares estimate of negative loss slope is:

\[
\widehat{-\partial L/\partial z}
=
\frac{\sum_{i\in s}C_i h_i}
{\epsilon_c+\sum_{i\in s}h_i^2}
\]

The local decision sensitivity per weight is approximated by:

\[
\partial z/\partial w_s \approx \sum_{i\in s} h_i/w_s
\]

and the diagnostic descent direction is their product. This is explicitly an approximation; it is not yet an accepted update rule. The independent comparator estimates \(dL/dw_s\) using central finite differences and compares its negative sign with the BTCC direction.

## 3. Compute gate

- \`always\`: audit every episode.
- \`gated\`: audit only if the prediction is wrong or \(|z|\) is within a predeclared uncertainty band.
- Event count is hard-bounded (default maximum 8); over-limit episodes are rejected by the reference audit rather than allowing unbounded replay cost.

This version does not yet implement an adaptive learned threshold for the gate. Temperature, uncertainty band and ridge must be frozen before any held-out experiment.

## 4. Tests authored

The dedicated suite covers:
- deterministic replay and order-independent timestamp batching;
- helpful FAST and harmful SLOW event omission signs;
- BTCC descent direction versus central finite-difference gradients for both binary targets;
- confident-correct skip, wrong-prediction trigger and correct-near-boundary trigger;
- shadow isolation/reproducibility;
- malformed IDs, missing weights, invalid labels, deadline violations and event-buffer limits;
- an explicit redundancy counterexample.

## 5. Known counterexample: redundant independent events

Two widely separated pulses can independently produce the same maximum decision margin. Removing either one leaves the other pulse and therefore leaves the loss unchanged:

\[
C_1=C_2=0
\]

Yet increasing their shared synaptic weight changes both margins, so the central finite-difference gradient can be non-zero. Ordinary leave-one-out credit therefore misses the shared value of redundant causes.

The test \`test_redundant_independent_events_expose_leave_one_out_blind_spot\` is intended to preserve this limitation. A later candidate could evaluate bounded event groups or sampled Shapley-style coalitions, but that would be a distinct experiment with a larger compute budget. Do not silently call the current BTCC complete.

## 6. Verification state

**AUTHORED / NOT RUN.** The assistant environment currently has no directly available local checkout or Python execution path for the private repository. The test module has not been executed. No numerical agreement percentage, benchmark improvement or device measurement is claimed.

User command after pulling:
\`\`\`sh
python3 -m unittest tools.super_neuron_btcc.test_credit_oracle -v
\`\`\`

No task-level training benchmark should be run until the focused tests pass and the gradient-direction agreement is inspected. No builds, APK/AAB, CI or GitHub Actions are part of this work.
