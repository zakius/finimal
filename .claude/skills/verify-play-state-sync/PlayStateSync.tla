---------------------------- MODULE PlayStateSync ----------------------------
(***************************************************************************)
(* finimal's play-state sync for ONE item, several accounts on one server. *)
(* Every action is one indivisible step of the real code; the comment on  *)
(* each condition names the file and line (at the sync commit in SKILL.md). *)
(*                                                                         *)
(* A play state is abstracted to its LastPlayedDate on the server's clock  *)
(* (t) plus the account that made it (by). t = 0 is "never played"        *)
(* (lastPlayedMs = null). Position and the played flag ride along with t:  *)
(* every local change stamps a new now(), so a newer t is a newer state.   *)
(***************************************************************************)
EXTENDS Naturals

CONSTANTS Accounts,   \* accounts saved on the device, all on one server
          MaxClock,   \* bound on new play states (local and other devices)
          Reports     \* FALSE: no player reports in this run (only edits, flushes, switches)

None == [t |-> 0, by |-> "none"]            \* PendingStore has no entry
State(t, u) == [t |-> t, by |-> u]

VARIABLES
    clock,    \* Server.now(): server clock, Server.kt:113
    cur,      \* the logged-in account: prefs.userId and the api token
    pending,  \* pending[u]: account u's PendingStore entry (App.pending, Platform.kt:70)
    latest,   \* ghost: newest t account u recorded locally (Server.record)
    server,   \* server[u]: the server's UserData for account u
    rep,      \* the one report in flight (reportProgress / reportStop), with its Owner u
    fl        \* the one flush (flushing Mutex, Server.kt:414), with its Owner u

vars == <<clock, cur, pending, latest, server, rep, fl>>

Init ==
    /\ clock = 0
    /\ cur \in Accounts
    /\ pending = [u \in Accounts |-> None]
    /\ latest = [u \in Accounts |-> 0]
    /\ server = [u \in Accounts |-> State(0, u)]
    /\ rep = [pc |-> "idle", st |-> None, u |-> "none"]
    /\ fl = [pc |-> "idle", snap |-> None, view |-> None, u |-> "none"]

\* Server.record (Server.kt:339): runs on App.disk, writes the pending file of owner(), the account
\* logged in now (Server.kt:94, read under the lock switchTo writes under).
Record(st) ==
    /\ pending' = [pending EXCEPT ![cur] = st]          \* Server.kt:342
    /\ latest' = [latest EXCEPT ![cur] = st.t]

\* PendingStore.confirm (PendingStore.kt:36): removes the entry only if it is still that state.
Confirm(p, u, st) == IF p[u] = st THEN [p EXCEPT ![u] = None] ELSE p

\* shouldPush (PlayState.kt:52): newer LastPlayedDate wins; a server state without a date never wins.
ShouldPush(local, view) == view.t = 0 \/ local.t >= view.t   \* PlayState.kt:53, :55

-----------------------------------------------------------------------------
\* startOver (Server.kt:349) and setPlayed's record (Server.kt:366): a local change, no report.
LocalEdit ==
    /\ clock < MaxClock
    /\ clock' = clock + 1
    /\ Record(State(clock + 1, cur))
    /\ UNCHANGED <<cur, server, rep, fl>>

\* reportProgress / reportStop: record first and keep its Owner (Server.kt:291, :312), then report
\* under `reports` with that Owner's client.
ReportRecord ==
    /\ Reports
    /\ rep.pc = "idle"
    /\ clock < MaxClock
    /\ clock' = clock + 1
    /\ Record(State(clock + 1, cur))
    /\ rep' = [pc |-> "post", st |-> State(clock + 1, cur), u |-> cur]
    /\ UNCHANGED <<cur, server, fl>>

\* Takes `reports` (free unless the flush holds it, and FlushPush is one step) and, unless a newer
\* local change replaced the entry, posts with the Owner's token (Server.kt:292-301, :315-319). Assumed: the
\* server stores the reported state with this date.
ReportSkip ==
    /\ rep.pc = "post"
    /\ pending[rep.u] # rep.st                            \* Server.kt:292
    /\ rep' = [pc |-> "idle", st |-> None, u |-> "none"]
    /\ UNCHANGED <<clock, cur, pending, latest, server, fl>>

ReportPost ==
    /\ rep.pc = "post"
    /\ pending[rep.u] = rep.st
    /\ \/ /\ server' = [server EXCEPT ![rep.u] = rep.st]
          /\ rep' = [rep EXCEPT !.pc = "confirm"]
       \/ /\ rep' = [pc |-> "idle", st |-> None, u |-> "none"]   \* runCatching swallows the failure
          /\ UNCHANGED server
    /\ UNCHANGED <<clock, cur, pending, latest, fl>>

\* .onSuccess { confirm(owner, id, state) } (Server.kt:301, :319), still under `reports`, in the
\* Owner's pending file.
ReportConfirm ==
    /\ rep.pc = "confirm"
    /\ pending' = Confirm(pending, rep.u, rep.st)        \* Server.kt:356
    /\ rep' = [pc |-> "idle", st |-> None, u |-> "none"]
    /\ UNCHANGED <<clock, cur, latest, server, fl>>

-----------------------------------------------------------------------------
\* flush (Server.kt:376): owner() and its pending file, read on App.disk (Server.kt:377).
FlushSnapshot ==
    /\ fl.pc = "idle"
    /\ pending[cur] # None                                \* Server.kt:378
    /\ fl' = [pc |-> "fetch", snap |-> pending[cur], view |-> None, u |-> cur]
    /\ UNCHANGED <<clock, cur, pending, latest, server, rep>>

\* One list request for server state, as the Owner (Server.kt:382).
FlushFetch ==
    /\ fl.pc = "fetch"
    /\ \/ fl' = [fl EXCEPT !.pc = "push", !.view = server[fl.u]]
       \/ fl' = [pc |-> "idle", snap |-> None, view |-> None, u |-> "none"]   \* Server.kt:382-385
    /\ UNCHANGED <<clock, cur, pending, latest, server, rep>>

\* Under `reports` (Server.kt:390), so not while a report holds it between post and confirm:
\* the entry still unchanged and shouldPush against the fetched view, then POST
\* /UserItems/{id}/UserData as the Owner (Server.kt:392-400). A failure keeps the entry
\* (Server.kt:406); 404 and 401 are not modeled.
FlushPush ==
    /\ fl.pc = "push"
    /\ rep.pc # "confirm"
    /\ IF pending[fl.u] = fl.snap /\ ShouldPush(fl.snap, fl.view)   \* Server.kt:392
       THEN \/ /\ server' = [server EXCEPT ![fl.u] = fl.snap]          \* Server.kt:393
               /\ fl' = [fl EXCEPT !.pc = "confirm"]
            \/ /\ fl' = [pc |-> "idle", snap |-> None, view |-> None, u |-> "none"]
               /\ UNCHANGED server
       ELSE /\ fl' = [fl EXCEPT !.pc = "confirm"]
            /\ UNCHANGED server
    /\ UNCHANGED <<clock, cur, pending, latest, rep>>

\* .onSuccess { confirm(owner, id, local) } (Server.kt:402), after `reports` is released, in the
\* Owner's pending file.
FlushConfirm ==
    /\ fl.pc = "confirm"
    /\ pending' = Confirm(pending, fl.u, fl.snap)
    /\ fl' = [pc |-> "idle", snap |-> None, view |-> None, u |-> "none"]
    /\ UNCHANGED <<clock, cur, latest, server, rep>>

-----------------------------------------------------------------------------
\* Another device of account u plays the item now (server clock).
OtherDevice(u) ==
    /\ clock < MaxClock
    /\ clock' = clock + 1
    /\ server' = [server EXCEPT ![u] = State(clock + 1, u)]
    /\ UNCHANGED <<cur, pending, latest, rep, fl>>

\* switchTo (Server.kt:148): prefs and the api token move to another saved account at once.
Switch ==
    /\ cur' \in Accounts \ {cur}
    /\ UNCHANGED <<clock, pending, latest, server, rep, fl>>

Next ==
    \/ LocalEdit \/ ReportRecord \/ ReportSkip \/ ReportPost \/ ReportConfirm
    \/ FlushSnapshot \/ FlushFetch \/ FlushPush \/ FlushConfirm
    \/ \E u \in Accounts : OtherDevice(u)
    \/ Switch

Spec == Init /\ [][Next]_vars

-----------------------------------------------------------------------------
TypeOK ==
    /\ clock \in 0..MaxClock
    /\ cur \in Accounts
    /\ rep.pc \in {"idle", "post", "confirm"}
    /\ fl.pc \in {"idle", "fetch", "push", "confirm"}

\* Progress is never lost: once an account has no unsynced entry, the server holds a state at
\* least as new as the newest one that account recorded.
NoLostProgress ==
    \A u \in Accounts : pending[u] = None => server[u].t >= latest[u]

\* Progress never reaches another account (design.md Sync rule 9).
OwnAccountOnly ==
    \A u \in Accounts : server[u].by = u
=============================================================================
