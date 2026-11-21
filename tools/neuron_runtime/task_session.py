"""Bounded task/session lifecycle reference for JARVIS."""
from __future__ import annotations
from dataclasses import dataclass
from enum import Enum
import json
import re
from typing import Any, Mapping

class TaskSessionError(ValueError): pass
class TaskConflict(TaskSessionError): pass
class TaskNotFound(TaskSessionError): pass

class SessionStatus(str, Enum):
    OPEN="open"; PAUSED="paused"; CLOSED="closed"

class TaskStatus(str, Enum):
    QUEUED="queued"; RUNNING="running"; WAITING="waiting"
    COMPLETED="completed"; FAILED="failed"; CANCELLED="cancelled"

_TERMINAL={TaskStatus.COMPLETED,TaskStatus.FAILED,TaskStatus.CANCELLED}
_TRANSITIONS={
 TaskStatus.QUEUED:{TaskStatus.RUNNING,TaskStatus.CANCELLED},
 TaskStatus.RUNNING:{TaskStatus.WAITING,TaskStatus.COMPLETED,TaskStatus.FAILED,TaskStatus.CANCELLED},
 TaskStatus.WAITING:{TaskStatus.RUNNING,TaskStatus.FAILED,TaskStatus.CANCELLED},
}
_ID=re.compile(r"^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$")

def _id(name,value):
    if not isinstance(value,str) or not _ID.fullmatch(value):
        raise TaskSessionError(f"{name} must be a stable 1..128 character identifier")
    return value

def _text(name,value,limit):
    if not isinstance(value,str): raise TaskSessionError(f"{name} must be text")
    value=value.strip()
    if not value or len(value)>limit: raise TaskSessionError(f"{name} must contain 1..{limit} characters")
    return value

def _json_object(value,name,max_bytes):
    if not isinstance(value,Mapping): raise TaskSessionError(f"{name} must be a mapping")
    try:
        raw=json.dumps(value,sort_keys=True,separators=(",",":"),ensure_ascii=False,allow_nan=False)
        copied=json.loads(raw)
    except (TypeError,ValueError,RecursionError) as exc:
        raise TaskSessionError(f"{name} must be finite JSON data") from exc
    if not isinstance(copied,dict) or len(raw.encode("utf-8"))>max_bytes:
        raise TaskSessionError(f"{name} exceeds its JSON object limit")
    return copied

@dataclass(frozen=True)
class SessionRecord:
    session_id:str; status:SessionStatus; revision:int; created_order:int; metadata:dict[str,Any]

@dataclass(frozen=True)
class TaskRecord:
    task_id:str; session_id:str; idempotency_key:str; title:str; status:TaskStatus
    revision:int; created_order:int; updated_order:int; input_refs:tuple[str,...]
    result:dict[str,Any]|None; failure_code:str|None

@dataclass(frozen=True)
class LifecycleEvent:
    sequence:int; entity_type:str; entity_id:str; from_status:str|None; to_status:str; revision:int

class TaskSessionManager:
    """Single-writer, bounded lifecycle registry with canonical snapshots."""
    SCHEMA="jarvis.task-session"; VERSION=1
    def __init__(self,*,manager_id,max_sessions=128,max_tasks=1024,max_events=4096,
                 max_refs_per_task=32,max_metadata_bytes=4096):
        self.manager_id=_id("manager_id",manager_id)
        limits={"max_sessions":max_sessions,"max_tasks":max_tasks,"max_events":max_events,
                "max_refs_per_task":max_refs_per_task,"max_metadata_bytes":max_metadata_bytes}
        maxima={"max_sessions":4096,"max_tasks":16384,"max_events":65536,
                "max_refs_per_task":256,"max_metadata_bytes":65536}
        for name,value in limits.items():
            if type(value)is not int or not 1<=value<=maxima[name]:
                raise TaskSessionError(f"{name} must be in 1..{maxima[name]}")
        self.limits=limits; self.revision=0; self._order=0
        self._sessions={}; self._tasks={}; self._idempotency={}; self._events=[]

    @property
    def sessions(self): return tuple(self._sessions[k] for k in sorted(self._sessions))
    @property
    def tasks(self): return tuple(self._tasks[k] for k in sorted(self._tasks))
    @property
    def events(self): return tuple(self._events)

    def _event(self,kind,entity,before,after,revision):
        if len(self._events)>=self.limits["max_events"]: raise TaskSessionError("lifecycle event capacity exhausted")
        self._events.append(LifecycleEvent(len(self._events)+1,kind,entity,before,after,revision))
    def _next_order(self): self._order+=1; return self._order

    def get_session(self,session_id):
        try:return self._sessions[_id("session_id",session_id)]
        except KeyError as exc:raise TaskNotFound("session not found") from exc
    def get_task(self,task_id):
        try:return self._tasks[_id("task_id",task_id)]
        except KeyError as exc:raise TaskNotFound("task not found") from exc

    def create_session(self,session_id,*,metadata=None):
        sid=_id("session_id",session_id)
        if sid in self._sessions:raise TaskConflict("session_id already exists")
        if len(self._sessions)>=self.limits["max_sessions"]:raise TaskSessionError("session capacity exhausted")
        clean=_json_object(metadata or {},"metadata",self.limits["max_metadata_bytes"])
        if len(self._events)>=self.limits["max_events"]:raise TaskSessionError("lifecycle event capacity exhausted")
        order=self._next_order(); rec=SessionRecord(sid,SessionStatus.OPEN,1,order,clean)
        self._sessions[sid]=rec; self.revision+=1
        self._event("session",sid,None,SessionStatus.OPEN.value,1)
        return rec

    def set_session_status(self,session_id,status,*,expected_revision):
        current=self.get_session(session_id)
        try:target=SessionStatus(status)
        except ValueError as exc:raise TaskSessionError("unsupported session status") from exc
        if type(expected_revision)is not int or expected_revision!=current.revision:raise TaskConflict("session revision conflict")
        if target==current.status:return current
        allowed={SessionStatus.OPEN:{SessionStatus.PAUSED,SessionStatus.CLOSED},
                 SessionStatus.PAUSED:{SessionStatus.OPEN,SessionStatus.CLOSED},SessionStatus.CLOSED:set()}
        if target not in allowed[current.status]:raise TaskConflict("invalid session status transition")
        if target==SessionStatus.CLOSED and any(t.session_id==current.session_id and t.status not in _TERMINAL for t in self._tasks.values()):
            raise TaskConflict("cannot close a session with non-terminal tasks")
        if len(self._events)>=self.limits["max_events"]:raise TaskSessionError("lifecycle event capacity exhausted")
        rec=SessionRecord(current.session_id,target,current.revision+1,current.created_order,dict(current.metadata))
        self._sessions[current.session_id]=rec; self.revision+=1
        self._event("session",current.session_id,current.status.value,target.value,rec.revision)
        return rec

    def create_task(self,task_id,session_id,idempotency_key,title,*,input_refs=()):
        tid=_id("task_id",task_id); sid=_id("session_id",session_id); key=_id("idempotency_key",idempotency_key)
        title=_text("title",title,512); session=self.get_session(sid)
        if session.status!=SessionStatus.OPEN:raise TaskConflict("tasks can only be added to open sessions")
        refs_in=tuple(input_refs)
        existing=self._idempotency.get(key)
        if existing is not None:
            prior=self._tasks[existing]
            if (prior.task_id,prior.session_id,prior.title,prior.input_refs)==(tid,sid,title,refs_in):return prior
            raise TaskConflict("idempotency key is already bound to another task")
        if tid in self._tasks:raise TaskConflict("task_id already exists")
        if len(self._tasks)>=self.limits["max_tasks"]:raise TaskSessionError("task capacity exhausted")
        if len(refs_in)>self.limits["max_refs_per_task"]:raise TaskSessionError("input_refs exceeds its configured limit")
        refs=tuple(_text("input_ref",r,512) for r in refs_in)
        if len(set(refs))!=len(refs):raise TaskSessionError("input_refs must be unique")
        if len(self._events)>=self.limits["max_events"]:raise TaskSessionError("lifecycle event capacity exhausted")
        order=self._next_order()
        rec=TaskRecord(tid,sid,key,title,TaskStatus.QUEUED,1,order,order,refs,None,None)
        self._tasks[tid]=rec; self._idempotency[key]=tid; self.revision+=1
        self._event("task",tid,None,TaskStatus.QUEUED.value,1)
        return rec

    def transition_task(self,task_id,status,*,expected_revision,result=None,failure_code=None):
        current=self.get_task(task_id)
        try:target=TaskStatus(status)
        except ValueError as exc:raise TaskSessionError("unsupported task status") from exc
        if type(expected_revision)is not int or expected_revision!=current.revision:raise TaskConflict("task revision conflict")
        if target==current.status:
            if result is None and failure_code is None:return current
            raise TaskConflict("same-status retry cannot replace terminal data")
        if target not in _TRANSITIONS[current.status]:raise TaskConflict("invalid task status transition")
        session=self.get_session(current.session_id)
        if session.status!=SessionStatus.OPEN and target==TaskStatus.RUNNING:raise TaskConflict("task cannot start while session is not open")
        if len(self._events)>=self.limits["max_events"]:raise TaskSessionError("lifecycle event capacity exhausted")
        clean_result=None; clean_failure=None
        if target==TaskStatus.COMPLETED:
            clean_result=_json_object(result or {},"result",self.limits["max_metadata_bytes"])
            if failure_code is not None:raise TaskSessionError("completed task cannot carry failure_code")
        elif target==TaskStatus.FAILED:
            clean_failure=_text("failure_code",failure_code or "unspecified",128)
            if result is not None:raise TaskSessionError("failed task cannot carry result")
        elif result is not None or failure_code is not None:raise TaskSessionError("non-terminal task cannot carry terminal data")
        order=self._next_order()
        rec=TaskRecord(current.task_id,current.session_id,current.idempotency_key,current.title,
                       target,current.revision+1,current.created_order,order,current.input_refs,clean_result,clean_failure)
        self._tasks[current.task_id]=rec; self.revision+=1
        self._event("task",current.task_id,current.status.value,target.value,rec.revision)
        return rec

    def snapshot(self):
        return {"schema":self.SCHEMA,"version":self.VERSION,"manager_id":self.manager_id,
          "limits":dict(self.limits),"revision":self.revision,"order":self._order,
          "sessions":[{"session_id":s.session_id,"status":s.status.value,"revision":s.revision,
            "created_order":s.created_order,"metadata":s.metadata} for s in self.sessions],
          "tasks":[{"task_id":t.task_id,"session_id":t.session_id,"idempotency_key":t.idempotency_key,
            "title":t.title,"status":t.status.value,"revision":t.revision,"created_order":t.created_order,
            "updated_order":t.updated_order,"input_refs":list(t.input_refs),"result":t.result,
            "failure_code":t.failure_code} for t in self.tasks],
          "events":[{"sequence":e.sequence,"entity_type":e.entity_type,"entity_id":e.entity_id,
            "from_status":e.from_status,"to_status":e.to_status,"revision":e.revision} for e in self.events]}

    def encode_snapshot(self):
        try:data=json.dumps(self.snapshot(),sort_keys=True,separators=(",",":"),ensure_ascii=False,allow_nan=False).encode()
        except (TypeError,ValueError,RecursionError) as exc:raise TaskSessionError("cannot encode snapshot") from exc
        if len(data)>4*1024*1024:raise TaskSessionError("snapshot exceeds 4 MiB")
        return data

    @classmethod
    def restore_snapshot(cls,payload):
        if not isinstance(payload,(bytes,bytearray,memoryview)) or len(payload)>4*1024*1024:raise TaskSessionError("snapshot must be bytes no larger than 4 MiB")
        try:raw=json.loads(bytes(payload).decode("utf-8"),object_pairs_hook=_unique,parse_constant=_reject_constant)
        except (UnicodeDecodeError,json.JSONDecodeError,ValueError,RecursionError) as exc:raise TaskSessionError("invalid snapshot JSON") from exc
        fields={"schema","version","manager_id","limits","revision","order","sessions","tasks","events"}
        if not isinstance(raw,dict) or set(raw)!=fields:raise TaskSessionError("invalid snapshot fields")
        if raw["schema"]!=cls.SCHEMA or raw["version"]!=cls.VERSION:raise TaskSessionError("unsupported snapshot schema")
        if not isinstance(raw["limits"],dict):raise TaskSessionError("invalid limits")
        manager=cls(manager_id=raw["manager_id"],**raw["limits"])
        if type(raw["revision"])is not int or raw["revision"]<0 or type(raw["order"])is not int or raw["order"]<0:raise TaskSessionError("invalid snapshot counters")
        if not all(isinstance(raw[k],list) for k in ("sessions","tasks","events")):raise TaskSessionError("snapshot collections must be arrays")
        if len(raw["sessions"])>manager.limits["max_sessions"] or len(raw["tasks"])>manager.limits["max_tasks"] or len(raw["events"])>manager.limits["max_events"]:raise TaskSessionError("snapshot exceeds capacity")
        try:
            for x in raw["sessions"]:
                if set(x)!={"session_id","status","revision","created_order","metadata"}:raise TaskSessionError("invalid session fields")
                sid=_id("session_id",x["session_id"])
                if sid in manager._sessions:raise TaskSessionError("duplicate session")
                rev=x["revision"]; order=x["created_order"]
                if type(rev)is not int or rev<1 or type(order)is not int or order<1:raise TaskSessionError("invalid session counters")
                manager._sessions[sid]=SessionRecord(sid,SessionStatus(x["status"]),rev,order,
                  _json_object(x["metadata"],"metadata",manager.limits["max_metadata_bytes"]))
            for x in raw["tasks"]:
                keys={"task_id","session_id","idempotency_key","title","status","revision","created_order","updated_order","input_refs","result","failure_code"}
                if set(x)!=keys:raise TaskSessionError("invalid task fields")
                tid=_id("task_id",x["task_id"]); sid=_id("session_id",x["session_id"]); key=_id("idempotency_key",x["idempotency_key"])
                if tid in manager._tasks or key in manager._idempotency or sid not in manager._sessions:raise TaskSessionError("duplicate task identity or missing session")
                rev,created,updated=x["revision"],x["created_order"],x["updated_order"]
                if any(type(v)is not int or v<1 for v in (rev,created,updated)) or updated<created:raise TaskSessionError("invalid task counters")
                if not isinstance(x["input_refs"],list) or len(x["input_refs"])>manager.limits["max_refs_per_task"]:raise TaskSessionError("invalid input refs")
                refs=tuple(_text("input_ref",r,512) for r in x["input_refs"])
                if len(set(refs))!=len(refs):raise TaskSessionError("duplicate input refs")
                result=None if x["result"] is None else _json_object(x["result"],"result",manager.limits["max_metadata_bytes"])
                failure=None if x["failure_code"] is None else _text("failure_code",x["failure_code"],128)
                status=TaskStatus(x["status"])
                if status==TaskStatus.COMPLETED and (result is None or failure is not None):raise TaskSessionError("invalid completed task")
                if status==TaskStatus.FAILED and (failure is None or result is not None):raise TaskSessionError("invalid failed task")
                if status not in _TERMINAL and (result is not None or failure is not None):raise TaskSessionError("nonterminal task has terminal data")
                manager._tasks[tid]=TaskRecord(tid,sid,key,_text("title",x["title"],512),status,rev,created,updated,refs,result,failure)
                manager._idempotency[key]=tid
            for x in raw["events"]:
                if set(x)!={"sequence","entity_type","entity_id","from_status","to_status","revision"}:raise TaskSessionError("invalid event fields")
                seq=x["sequence"]
                if type(seq)is not int or seq!=len(manager._events)+1:raise TaskSessionError("event sequence is not contiguous")
                if x["entity_type"] not in {"session","task"}:raise TaskSessionError("invalid event type")
                manager._events.append(LifecycleEvent(seq,x["entity_type"],_id("entity_id",x["entity_id"]),x["from_status"],_text("to_status",x["to_status"],32),x["revision"]))
        except (KeyError,TypeError,ValueError) as exc:
            if isinstance(exc,TaskSessionError):raise
            raise TaskSessionError("invalid snapshot record") from exc
        if raw["revision"]<len(manager._events):raise TaskSessionError("revision precedes event history")
        max_order=max([s.created_order for s in manager._sessions.values()]+[t.updated_order for t in manager._tasks.values()]+[0])
        if raw["order"]<max_order:raise TaskSessionError("order precedes records")
        manager.revision=raw["revision"]; manager._order=raw["order"]
        return manager

def _unique(pairs):
    result={}
    for key,value in pairs:
        if key in result:raise ValueError("duplicate JSON key")
        result[key]=value
    return result

def _reject_constant(value): raise ValueError(f"non-finite JSON constant: {value}")
