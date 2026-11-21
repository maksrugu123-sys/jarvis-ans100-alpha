"""Tests for the compact F64 neuron-state codec."""
from __future__ import annotations
import struct, unittest
from dataclasses import replace
from tools.numerical_oracle.jarvis_oracle import NeuronState,SynapseState
from .compact_state import CompactNeuronStateError,pack_neuron_runtime,parameter_bundle,parameter_set_id,unpack_neuron_runtime
from .runtime import NeuronRuntime

def make_runtime(rule="none"):
    active=rule=="rstdp"
    synapses={"input-A":SynapseState(weight=.7,pre_trace=.2,post_trace=.3,
        trace_last_update_model_time_ns=25,eligibility_trace=.25 if active else 0.0,
        eligibility_context_id="episode-17" if active else None,
        eligibility_started_model_time_ns=20 if active else None,eligibility_last_update_model_time_ns=25),
        "input-B":SynapseState(weight=.4)}
    return NeuronRuntime(synapses,neuron_state=NeuronState(u=.125,a=.2,last_update_model_time_ns=25,
        refractory_until_model_time_ns=23,spike_sequence=2,last_spike_model_time_ns=21),
        learning_rule=rule,processed_batches=9)

class CompactNeuronStateTests(unittest.TestCase):
    def test_round_trip_preserves_all_state_for_each_rule(self):
        for rule in ("none","stdp","rstdp"):
            with self.subTest(rule=rule):
                runtime=make_runtime(rule);packed=pack_neuron_runtime(runtime)
                restored=unpack_neuron_runtime(packed,{packed.parameter_set_id:parameter_bundle(runtime)})
                self.assertEqual(restored.learning_rule,runtime.learning_rule)
                self.assertEqual(restored.processed_batches,runtime.processed_batches)
                self.assertEqual(restored.neuron_state,runtime.neuron_state)
                self.assertEqual(restored.synapses,runtime.synapses)
                self.assertEqual(restored.neuron_params,runtime.neuron_params)
                self.assertEqual(restored.synapse_params,runtime.synapse_params)
                self.assertEqual(restored.reward_params,runtime.reward_params)
    def test_determinism_and_parameter_deduplication(self):
        a,b=make_runtime(),make_runtime()
        packed_a,packed_b=pack_neuron_runtime(a),pack_neuron_runtime(b)
        self.assertEqual(packed_a.data,packed_b.data);self.assertEqual(parameter_set_id(a),parameter_set_id(b))
        changed=NeuronRuntime(a.synapses,neuron_params=replace(a.neuron_params,tau_m_ns=a.neuron_params.tau_m_ns+1))
        self.assertNotEqual(parameter_set_id(a),parameter_set_id(changed))
    def test_optional_timestamps_round_trip(self):
        runtime=NeuronRuntime({"in":SynapseState(weight=.5)});packed=pack_neuron_runtime(runtime)
        restored=unpack_neuron_runtime(packed,{packed.parameter_set_id:parameter_bundle(runtime)})
        self.assertIsNone(restored.neuron_state.last_spike_model_time_ns)
        self.assertIsNone(restored.synapses["in"].eligibility_started_model_time_ns)
        self.assertIsNone(restored.synapses["in"].eligibility_context_id)
    def test_truncated_and_trailing_bytes_rejected(self):
        runtime=make_runtime();packed=pack_neuron_runtime(runtime);bank={packed.parameter_set_id:parameter_bundle(runtime)}
        with self.assertRaises(CompactNeuronStateError):unpack_neuron_runtime(packed.data[:-1],bank)
        with self.assertRaisesRegex(CompactNeuronStateError,"trailing"):unpack_neuron_runtime(packed.data+b"x",bank)
    def test_unknown_version_and_rule_rejected(self):
        runtime=make_runtime();packed=pack_neuron_runtime(runtime);bank={packed.parameter_set_id:parameter_bundle(runtime)}
        version=bytearray(packed.data);struct.pack_into("<H",version,4,99)
        with self.assertRaisesRegex(CompactNeuronStateError,"version"):unpack_neuron_runtime(version,bank)
        rule=bytearray(packed.data);rule[6]=99
        with self.assertRaisesRegex(CompactNeuronStateError,"rule"):unpack_neuron_runtime(rule,bank)
    def test_missing_or_wrong_parameter_entry_rejected(self):
        runtime=make_runtime();packed=pack_neuron_runtime(runtime)
        with self.assertRaisesRegex(CompactNeuronStateError,"missing parameter"):unpack_neuron_runtime(packed,{})
        wrong=NeuronRuntime(runtime.synapses,neuron_params=replace(runtime.neuron_params,tau_m_ns=runtime.neuron_params.tau_m_ns+1))
        with self.assertRaisesRegex(CompactNeuronStateError,"does not match"):
            unpack_neuron_runtime(packed,{packed.parameter_set_id:parameter_bundle(wrong)})

if __name__=="__main__":unittest.main(verbosity=2)
