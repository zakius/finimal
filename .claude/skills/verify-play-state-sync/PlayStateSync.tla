---------------------------- MODULE PlayStateSync ----------------------------
(***************************************************************************)
(* finimal's play-state sync for ONE item, several accounts on one server. *)
(* Every action is one indivisible step of the real code; the comment on  *)
(* each condition names the file and line (Coverage in SKILL.md).          *)
(*                                                                         *)
(* A play state is the local change it carries (t: when now() was read, on *)
(* the server's clock; 0 is never played), the LastPlayedDate the server   *)
(* keeps for it (d), and the account it was recorded for (by). Each change *)
(* reads a new now(), so a newer t is a newer state, and locally d = t.    *)
(* The server dates a progress or stop report on arrival (Server.kt:366),  *)
(* so a report it stores keeps its t but gets a later d.                   *)
(***************************************************************************)
EXTENDS Naturals

CONSTANTS Accounts,          \* accounts saved on the device, all on one server
          MaxClock,          \* bound on now() reads and report arrivals
          Reports,           \* FALSE: no player reports in this run
          Edits,             \* FALSE: no startOver or setPlayed in this run
          OutOfOrderRecords, \* FALSE: no change is recorded after one that read now() later (leaves out finding 2)
          \* TRUE is the code as it is. FALSE puts back the bug a fix removed (run.sh, replay rows).
          UsesOwner,         \* 7639821: reports, flush and confirms use the Owner recorded with the state
          ReportRecheck,     \* 7639821: a report is sent only while its state is still pending
          FlushRecheck,      \* 7639821: a flush pushes only while its snapshot is still pending
          FlushLock          \* 7639821 (`reports`), 7cbbb32 (itemLock): the flush pushes under the item's lock

None == [t |-> 0, d |-> 0, by |-> "none"]   \* PendingStore has no entry
Local(t, u) == [t |-> t, d |-> t, by |-> u]

VARIABLES
    clock,    \* Server.now(): server clock, Server.kt:132
    cur,      \* the logged-in account: prefs.userId and the api token
    pending,  \* pending[u]: account u's PendingStore entry (App.pending, Platform.kt:127)
    latest,   \* ghost: newest t recorded for account u
    server,   \* server[u]: the server's UserData for account u
    ed,       \* the one startOver or setPlayed between its now() and its record
    rep,      \* the one report (reportProgress / reportStop), with its Owner u once recorded
    fl        \* the one flush (the flushing Mutex, Server.kt:632), with its Owner u

vars == <<clock, cur, pending, latest, server, ed, rep, fl>>

EdIdle == [pc |-> "idle", t |-> 0]
RepIdle == [pc |-> "idle", st |-> None, u |-> "none"]
FlIdle == [pc |-> "idle", snap |-> None, view |-> None, u |-> "none"]

Init ==
    /\ clock = 0
    /\ cur \in Accounts
    /\ pending = [u \in Accounts |-> None]
    /\ latest = [u \in Accounts |-> 0]
    /\ server = [u \in Accounts |-> [t |-> 0, d |-> 0, by |-> u]]
    /\ ed = EdIdle
    /\ rep = RepIdle
    /\ fl = FlIdle

\* Without an Owner, each step uses the account logged in when it runs: `api`, `userId`, App.pending.
RepAcct == IF UsesOwner THEN rep.u ELSE cur
FlAcct == IF UsesOwner THEN fl.u ELSE cur

\* itemLock(id) (Server.kt:136): a report holds it from its check until its confirm, a flush from
\* its check until its push returns.
ReportHoldsLock == rep.pc \in {"send", "confirm"}
FlushHoldsLock == FlushLock /\ fl.pc = "send"

InOrder(t) ==
    \/ OutOfOrderRecords
    \/ /\ ed.pc = "stamped" => ed.t >= t
       /\ rep.pc = "stamped" => rep.st.t >= t

\* recordOnDisk (Server.kt:437), one App.disk task: owner() and a put into its pending file
\* (Server.kt:438-441), whichever change it holds.
Record(st) ==
    /\ pending' = [pending EXCEPT ![cur] = st]
    /\ latest' = [latest EXCEPT ![cur] = IF st.t > @ THEN st.t ELSE @]

\* PendingStore.confirm and confirmAll (PendingStore.kt:59, :70): remove the entry only if it is still that state.
Confirm(p, u, st) == IF p[u] = st THEN [p EXCEPT ![u] = None] ELSE p

\* shouldPush (PlayState.kt:52): newer LastPlayedDate wins; a server state without a date never wins.
ShouldPush(local, view) == view.d = 0 \/ local.d >= view.d   \* PlayState.kt:53, :55

-----------------------------------------------------------------------------
\* startOver (Server.kt:490) and setPlayed (Server.kt:505) read now() on the caller's thread, then
\* hop to App.disk to record it (record, recordAll: Server.kt:456, :467). No report follows.
EditStamp ==
    /\ Edits
    /\ ed.pc = "idle"
    /\ clock < MaxClock
    /\ clock' = clock + 1
    /\ ed' = [pc |-> "stamped", t |-> clock + 1]
    /\ UNCHANGED <<cur, pending, latest, server, rep, fl>>

EditRecord ==
    /\ ed.pc = "stamped"
    /\ InOrder(ed.t)
    /\ Record(Local(ed.t, cur))
    /\ ed' = EdIdle
    /\ UNCHANGED <<clock, cur, server, rep, fl>>

\* reportProgress reads now() on Dispatchers.IO (Server.kt:370), then records on App.disk and keeps
\* the Owner (Server.kt:371). reportStop reads now() inside its App.disk task (Server.kt:399-400),
\* the case with no step in between.
ReportStamp ==
    /\ Reports
    /\ rep.pc = "idle"
    /\ clock < MaxClock
    /\ clock' = clock + 1
    /\ rep' = [pc |-> "stamped", st |-> Local(clock + 1, "none"), u |-> "none"]
    /\ UNCHANGED <<cur, pending, latest, server, ed, fl>>

ReportRecord ==
    /\ rep.pc = "stamped"
    /\ InOrder(rep.st.t)
    /\ Record(Local(rep.st.t, cur))
    /\ rep' = [pc |-> "wait", st |-> Local(rep.st.t, cur), u |-> cur]
    /\ UNCHANGED <<clock, cur, server, ed, fl>>

\* Takes itemLock and, unless a newer local change replaced the entry, starts the request with the
\* Owner's client (Server.kt:372-375, :407-410). Nothing holds a record back until the server answers.
ReportCheck ==
    /\ rep.pc = "wait"
    /\ ~FlushHoldsLock
    /\ rep' = IF ~ReportRecheck \/ pending[RepAcct] = rep.st   \* Server.kt:373, :408
              THEN [rep EXCEPT !.pc = "send"]
              ELSE RepIdle
    /\ UNCHANGED <<clock, cur, pending, latest, server, ed, fl>>

\* The server stores the reported position dated on arrival (Server.kt:366), or the request fails
\* and runCatching swallows it, leaving the entry for a flush (Server.kt:374, :409).
ReportSend ==
    /\ rep.pc = "send"
    /\ \/ /\ clock < MaxClock
          /\ clock' = clock + 1
          /\ server' = [server EXCEPT ![RepAcct] = [rep.st EXCEPT !.d = clock + 1]]
          /\ rep' = [rep EXCEPT !.pc = "confirm"]
       \/ /\ rep' = RepIdle
          /\ UNCHANGED <<clock, server>>
    /\ UNCHANGED <<cur, pending, latest, ed, fl>>

\* .onSuccess { confirm(owner, id, state) } (Server.kt:383, :413, :494), then itemLock is released.
ReportConfirm ==
    /\ rep.pc = "confirm"
    /\ pending' = Confirm(pending, RepAcct, rep.st)
    /\ rep' = RepIdle
    /\ UNCHANGED <<clock, cur, latest, server, ed, fl>>

-----------------------------------------------------------------------------
\* runFlush under `flushing`: owner() and its pending file, read on App.disk (Server.kt:551-558).
\* The hasPendingWork fast path and the recordVersion coalescing (Server.kt:528-547) only decide
\* whether a caller starts one; a flush may start whenever an entry is pending.
FlushSnapshot ==
    /\ fl.pc = "idle"
    /\ pending[cur] # None                                \* Server.kt:558
    /\ fl' = [pc |-> "fetch", snap |-> pending[cur], view |-> None, u |-> cur]
    /\ UNCHANGED <<clock, cur, pending, latest, server, ed, rep>>

\* One list request for server state, as the Owner; a failure ends the flush (Server.kt:564-569).
FlushFetch ==
    /\ fl.pc = "fetch"
    /\ \/ fl' = [fl EXCEPT !.pc = "check", !.view = server[FlAcct]]
       \/ fl' = FlIdle
    /\ UNCHANGED <<clock, cur, pending, latest, server, ed, rep>>

\* A Semaphore(4) permit, then itemLock, then the still-pending check and shouldPush against the
\* fetched view (Server.kt:580-585). Either way the item counts as confirmed unless the push fails
\* (Server.kt:598-599).
FlushCheck ==
    /\ fl.pc = "check"
    /\ FlushLock => ~ReportHoldsLock                      \* Server.kt:583
    /\ fl' = IF (~FlushRecheck \/ pending[FlAcct] = fl.snap) /\ ShouldPush(fl.snap, fl.view)   \* Server.kt:585
             THEN [fl EXCEPT !.pc = "send"]
             ELSE [fl EXCEPT !.pc = "confirm"]
    /\ UNCHANGED <<clock, cur, pending, latest, server, ed, rep>>

\* POST /UserItems/{id}/UserData as the Owner, with the local LastPlayedDate (Server.kt:586-595).
\* A failure keeps the entry (Server.kt:609-615); 404 and 401 are not modeled.
FlushSend ==
    /\ fl.pc = "send"
    /\ \/ /\ server' = [server EXCEPT ![FlAcct] = fl.snap]
          /\ fl' = [fl EXCEPT !.pc = "confirm"]
       \/ /\ fl' = FlIdle
          /\ UNCHANGED server
    /\ UNCHANGED <<clock, cur, pending, latest, ed, rep>>

\* owner.pending.confirmAll(confirmed) once every item is done, outside itemLock (Server.kt:622-626).
FlushConfirm ==
    /\ fl.pc = "confirm"
    /\ pending' = Confirm(pending, FlAcct, fl.snap)
    /\ fl' = FlIdle
    /\ UNCHANGED <<clock, cur, latest, server, ed, rep>>

-----------------------------------------------------------------------------
\* Another device of account u plays the item; the server dates it on arrival.
OtherDevice(u) ==
    /\ clock < MaxClock
    /\ clock' = clock + 1
    /\ server' = [server EXCEPT ![u] = Local(clock + 1, u)]
    /\ UNCHANGED <<cur, pending, latest, ed, rep, fl>>

\* switchTo (Server.kt:189): prefs change under synchronized(App) (Server.kt:192), the lock owner() reads under.
Switch ==
    /\ cur' \in Accounts \ {cur}
    /\ UNCHANGED <<clock, pending, latest, server, ed, rep, fl>>

Next ==
    \/ EditStamp \/ EditRecord
    \/ ReportStamp \/ ReportRecord \/ ReportCheck \/ ReportSend \/ ReportConfirm
    \/ FlushSnapshot \/ FlushFetch \/ FlushCheck \/ FlushSend \/ FlushConfirm
    \/ \E u \in Accounts : OtherDevice(u)
    \/ Switch

Spec == Init /\ [][Next]_vars

-----------------------------------------------------------------------------
TypeOK ==
    /\ clock \in 0..MaxClock
    /\ cur \in Accounts
    /\ ed.pc \in {"idle", "stamped"}
    /\ rep.pc \in {"idle", "stamped", "wait", "send", "confirm"}
    /\ fl.pc \in {"idle", "fetch", "check", "send", "confirm"}

\* Progress is never lost: once an account has no unsynced entry, the server holds a state at
\* least as new as the newest one recorded for that account.
NoLostProgress ==
    \A u \in Accounts : pending[u] = None => server[u].t >= latest[u]

\* Progress never reaches another account (design.md Sync rule 9).
OwnAccountOnly ==
    \A u \in Accounts : server[u].by = u
=============================================================================
