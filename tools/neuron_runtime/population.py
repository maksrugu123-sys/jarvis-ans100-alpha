"""Validated functional population registry for ANS network topologies."""
from __future__ import annotations

from collections.abc import Mapping, Sequence
from types import MappingProxyType

from .neuron_features import NeuronRole


class PopulationRegistryError(ValueError):
    """Invalid population assignment or incompatible network route."""


class NeuronPopulationRegistry:
    """Immutable mapping of neuron IDs to functional populations."""

    def __init__(self, neuron_ids: Sequence[str], roles: Mapping[str, NeuronRole | str] | None = None) -> None:
        if not isinstance(neuron_ids, (tuple, list)) or not neuron_ids:
            raise PopulationRegistryError("neuron_ids must be a non-empty sequence")
        ids = tuple(neuron_ids)
        if any(not isinstance(x, str) or not x.strip() for x in ids) or len(ids) != len(set(ids)):
            raise PopulationRegistryError("neuron IDs must be non-empty and unique")
        if roles is None:
            roles = {}
        if not isinstance(roles, Mapping):
            raise PopulationRegistryError("roles must be a mapping")
        if set(roles) - set(ids):
            raise PopulationRegistryError("population role references unknown neuron IDs")
        prepared = {}
        for neuron_id in ids:
            raw = roles.get(neuron_id, NeuronRole.COORDINATOR)
            try:
                prepared[neuron_id] = raw if isinstance(raw, NeuronRole) else NeuronRole(raw)
            except (TypeError, ValueError) as exc:
                raise PopulationRegistryError("unknown population role for neuron " + neuron_id) from exc
        self._roles = MappingProxyType(dict(sorted(prepared.items())))
        groups = {}
        for role in NeuronRole:
            members = tuple(k for k, v in self._roles.items() if v == role)
            if members:
                groups[role] = members
        self._groups = MappingProxyType(groups)

    @property
    def roles(self):
        return self._roles

    @property
    def groups(self):
        return self._groups

    def role_for(self, neuron_id: str) -> NeuronRole:
        try:
            return self._roles[neuron_id]
        except KeyError as exc:
            raise PopulationRegistryError("unknown neuron: " + neuron_id) from exc

    def members(self, role: NeuronRole | str) -> tuple[str, ...]:
        try:
            selected = role if isinstance(role, NeuronRole) else NeuronRole(role)
        except (TypeError, ValueError) as exc:
            raise PopulationRegistryError("unknown population role") from exc
        return self._groups.get(selected, ())

    def validate_routes(self, connections: Sequence[object]) -> None:
        inhibitory_sources = set()
        for connection in connections:
            role = self.role_for(connection.source_neuron_id)
            if role == NeuronRole.EXCITATORY and connection.signal_type != "excitatory":
                raise PopulationRegistryError("excitatory neurons may only emit excitatory routes")
            if role == NeuronRole.INHIBITORY and connection.signal_type != "inhibitory":
                raise PopulationRegistryError("inhibitory neurons may only emit inhibitory routes")
            if connection.signal_type == "inhibitory":
                inhibitory_sources.add(connection.source_neuron_id)
        for neuron_id, role in self._roles.items():
            if role == NeuronRole.CONTRADICTION and neuron_id not in inhibitory_sources:
                raise PopulationRegistryError("contradiction neuron requires an inhibitory output route")
