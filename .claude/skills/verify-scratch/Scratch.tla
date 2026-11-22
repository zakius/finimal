------------------------------- MODULE Scratch -------------------------------
\* A bare line number is Scratch.kt at the hashes in SKILL.md. Entries are numbered in the order
\* entry(key) creates them, so a higher number is a newer entry for the same key.
EXTENDS Integers

CONSTANTS
    NChunks,               \* chunks in the file
    MaxEntries,            \* entries entry(key) may create in one run
    DeleteFindsEntry,      \* TRUE: delete(key) runs only while an entry is in the map
    IgnoresRange,          \* TRUE: the server answers every Range request with 200 from byte 0
    PreFixDeleteUnlocked,  \* 5beae1e replays, all FALSE for the code: deleteRecursively after the lock (:57-60)
    PreFixNoGoneCheck,     \* no gone check at the rename (:599) and in finally (:472)
    PreFixNoDeadline,      \* the failed-open retry ignores the deadline (:328)
    PreFixNoSkip           \* no skipping to the target in a 200 response (:555)

Chunks == 0..(NChunks - 1)
Gens == 1..MaxEntries
None == -1
Owners == {None} \cup (0..MaxEntries)   \* 0: left on disk by an earlier run of the app
Min(S) == CHOOSE x \in S : \A y \in S : x <= y

VARIABLES
    folder, lenFile, chunkFile, partFile,   \* File(dir, key) and its length, <n>, <n>.part files, by owner
    mapped, created,                        \* entries[key] (:37), and how many Entry objects exist
    gone, present, lenKnown, writing, written, demand, fatal,   \* Entry fields (:94-115), one demand
    fpc, fpos, fstart, fcancel, fquiet,     \* each entry's filler thread; fquiet: no demand change since its request
    cpc, cent, cpos, cfile, late,           \* the ScratchServer request; late: its read deadline passed
    dpc, dent, dseen,                       \* the delete(key) call; dseen: entries that existed when it read the map
    ev                                      \* the Events of the last step, which the checks forbid

disk == <<folder, lenFile, chunkFile, partFile>>
entryVars == <<gone, present, lenKnown, writing, written, demand, fatal>>
fillerVars == <<fpc, fpos, fstart, fcancel, fquiet>>
clientVars == <<cpc, cent, cpos, cfile, late>>
deleterVars == <<dpc, dent, dseen>>
vars == <<disk, mapped, created, entryVars, fillerVars, clientVars, deleterVars, ev>>

Events == {"truncate", "length", "move", "delete-part", "delete-newer", "delete-open", "refetch"}

TypeOK ==
    /\ folder \in BOOLEAN
    /\ lenFile \in Owners
    /\ chunkFile \in [Chunks -> Owners]
    /\ partFile \in [Chunks -> Owners]
    /\ mapped \in 0..MaxEntries /\ created \in 0..MaxEntries
    /\ gone \in [Gens -> BOOLEAN] /\ fatal \in [Gens -> BOOLEAN] /\ lenKnown \in [Gens -> BOOLEAN]
    /\ present \in [Gens -> SUBSET Chunks]
    /\ writing \in [Gens -> Chunks \cup {None}] /\ written \in [Gens -> BOOLEAN]
    /\ demand \in [Gens -> Chunks \cup {None}]
    /\ fpc \in [Gens -> {"none", "pick", "header", "decide", "open", "write", "commit", "finally"}]
    /\ fpos \in [Gens -> Chunks] /\ fstart \in [Gens -> Chunks]
    /\ fcancel \in [Gens -> BOOLEAN] /\ fquiet \in [Gens -> BOOLEAN]
    /\ cpc \in {"idle", "demand", "check", "read", "got", "close"}
    /\ cent \in 0..MaxEntries /\ cpos \in Chunks /\ cfile \in {"chunk", "part"} /\ late \in BOOLEAN
    /\ dpc \in {"idle", "release", "remove", "files"} /\ dent \in 0..MaxEntries /\ dseen \in 0..MaxEntries
    /\ ev \subseteq Events

\* computeTarget (:401-422) with demand position d and fatal flag f; one demand, wanting the rest of the file.
TargetOf(g, d, f) ==
    IF gone[g] \/ f \/ d = None THEN None                                   \* :402
    ELSE IF ~lenKnown[g] THEN d                                             \* :405-407
    ELSE LET missing == {i \in Chunks : i >= d /\ i \notin present[g]}      \* :418
         IN IF missing = {} THEN None ELSE Min(missing)
Target(g) == TargetOf(g, demand[g], fatal[g])

\* wake() after the demand changes (:223-226): a fetch nobody wants is cancelled.
Wake(g, d, f) ==
    IF writing[g] # None /\ TargetOf(g, d, f) # writing[g]
    THEN [fcancel EXCEPT ![g] = TRUE] ELSE fcancel

\* fill() calls fetch again at once when fetch returns normally (:440). If no demand changed since
\* the request and the target is still the chunk it asked for, the next request gets the same answer.
ReturnEv(g) == IF fquiet[g] /\ Target(g) = fstart[g] THEN {"refetch"} ELSE {}

HasBytes == \E i \in Chunks : chunkFile[i] # None \/ partFile[i] # None
NewerThan(o, g) == o # None /\ o > g

Init ==
    /\ folder \in BOOLEAN
    /\ lenFile = IF folder THEN 0 ELSE None
    /\ chunkFile \in [Chunks -> IF folder THEN {None, 0} ELSE {None}]
    /\ partFile = [i \in Chunks |-> None]
    /\ mapped = 0 /\ created = 0
    /\ gone = [g \in Gens |-> FALSE] /\ present = [g \in Gens |-> {}]
    /\ lenKnown = [g \in Gens |-> FALSE] /\ writing = [g \in Gens |-> None]
    /\ written = [g \in Gens |-> FALSE] /\ demand = [g \in Gens |-> None]
    /\ fatal = [g \in Gens |-> FALSE]
    /\ fpc = [g \in Gens |-> "none"] /\ fpos = [g \in Gens |-> 0]
    /\ fstart = [g \in Gens |-> 0] /\ fcancel = [g \in Gens |-> FALSE] /\ fquiet = [g \in Gens |-> FALSE]
    /\ cpc = "idle" /\ cent = 0 /\ cpos = 0 /\ cfile = "chunk" /\ late = FALSE
    /\ dpc = "idle" /\ dent = 0 /\ dseen = 0
    /\ ev = {}

-----------------------------------------------------------------------------
\* The request: ScratchServer.serve (ScratchServer.kt:59-134).

\* entry() (:44-47) returns the mapped entry, or creates one whose init (:94-98, :141-150) reads the
\* folder: chunk files become present, the length file gives the length, leftover parts are deleted.
Lookup ==
    /\ cpc = "idle"
    /\ \/ /\ mapped # 0                                                     \* :46
          /\ cent' = mapped
          /\ UNCHANGED <<disk, mapped, created, present, lenKnown>>
       \/ /\ mapped = 0
          /\ created < MaxEntries
          /\ LET g == created + 1 IN
             /\ created' = g /\ mapped' = g /\ cent' = g
             /\ present' = [present EXCEPT ![g] = {i \in Chunks : chunkFile[i] # None}]    \* :145
             /\ lenKnown' = [lenKnown EXCEPT ![g] = lenFile # None]                        \* :96
             /\ partFile' = [i \in Chunks |-> None]                                        \* :147
             /\ chunkFile' = [i \in Chunks |-> IF chunkFile[i] = None THEN None ELSE g]
             /\ lenFile' = IF lenFile = None THEN None ELSE g
             /\ UNCHANGED folder
    /\ cpc' = "demand"
    /\ ev' = {}
    /\ UNCHANGED <<gone, writing, written, demand, fatal, fillerVars, cpos, cfile, late, deleterVars>>

\* demand() (:200-213) at a chunk the player asks for. On a removed entry it throws, and the
\* server answers 404 (ScratchServer.kt:92-93).
Demand ==
    /\ cpc = "demand"
    /\ IF gone[cent]                                                        \* :202
       THEN /\ cpc' = "idle"
            /\ UNCHANGED <<demand, fatal, fpc, fcancel, fquiet, cpos, late>>
       ELSE \E c \in Chunks :
            /\ demand' = [demand EXCEPT ![cent] = c]                        \* :205
            /\ fatal' = [fatal EXCEPT ![cent] = FALSE]                      \* :207
            /\ fpc' = IF fpc[cent] = "none" THEN [fpc EXCEPT ![cent] = "pick"] ELSE fpc   \* :210
            /\ fcancel' = Wake(cent, c, FALSE)                              \* :211
            /\ fquiet' = [fquiet EXCEPT ![cent] = FALSE]
            /\ cpos' = c /\ late' = FALSE /\ cpc' = "check"
    /\ ev' = {}
    /\ UNCHANGED <<disk, mapped, created, gone, present, lenKnown, writing, written, fpos, fstart, cent, cfile, deleterVars>>

\* readInternal's check under the entry lock (:290-304) finds bytes to read.
ReadFind ==
    /\ cpc = "check"
    /\ ~gone[cent]                                                          \* :292
    /\ \/ lenKnown[cent] /\ cpos \in present[cent] /\ cfile' = "chunk"      \* :295
       \/ ~(lenKnown[cent] /\ cpos \in present[cent])
          /\ writing[cent] = cpos /\ written[cent] /\ cfile' = "part"       \* :299
    /\ cpc' = "read"
    /\ ev' = {}
    /\ UNCHANGED <<disk, mapped, created, entryVars, fillerVars, cent, cpos, late, deleterVars>>

\* The same check throws (:292-309).
ReadFail ==
    /\ cpc = "check"
    /\ LET hasData == (lenKnown[cent] /\ cpos \in present[cent]) \/ (writing[cent] = cpos /\ written[cent])
       IN \/ gone[cent]                                                     \* :292
          \/ ~hasData /\ fatal[cent]                                        \* :300
          \/ ~hasData /\ late                                               \* :306-309
    /\ cpc' = "close"
    /\ ev' = {}
    /\ UNCHANGED <<disk, mapped, created, entryVars, fillerVars, cent, cpos, cfile, late, deleterVars>>

ReadFile == IF cfile = "chunk" THEN chunkFile[cpos] ELSE partFile[cpos]

\* FileChannel.open and read outside the lock (:317-322).
ReadOpen ==
    /\ cpc = "read"
    /\ ReadFile # None
    /\ cpc' = "got"
    /\ ev' = {}
    /\ UNCHANGED <<disk, mapped, created, entryVars, fillerVars, cent, cpos, cfile, late, deleterVars>>

\* The file went away between the check and the open: NoSuchFileException, or AccessDeniedException
\* on Windows while a delete is pending (:323-329). Retried until the deadline.
ReadRetry ==
    /\ cpc = "read"
    /\ ReadFile = None
    /\ cpc' = IF late /\ ~PreFixNoDeadline THEN "close" ELSE "check"        \* :328
    /\ ev' = {}
    /\ UNCHANGED <<disk, mapped, created, entryVars, fillerVars, cent, cpos, cfile, late, deleterVars>>

\* System.currentTimeMillis() passes the read's deadline (:285).
Tick ==
    /\ cpc \in {"check", "read"}
    /\ ~late
    /\ late' = TRUE
    /\ ev' = {}
    /\ UNCHANGED <<disk, mapped, created, entryVars, fillerVars, cpc, cent, cpos, cfile, deleterVars>>

\* The next read call: demand.moveTo the next chunk (ScratchServer.kt:129, :82-86), with a new deadline.
ReadNext ==
    /\ cpc = "got"
    /\ cpos + 1 \in Chunks
    /\ demand[cent] # None
    /\ demand' = [demand EXCEPT ![cent] = cpos + 1]
    /\ fcancel' = Wake(cent, cpos + 1, fatal[cent])                         \* :85
    /\ fquiet' = [fquiet EXCEPT ![cent] = FALSE]
    /\ cpos' = cpos + 1 /\ late' = FALSE /\ cpc' = "check"
    /\ ev' = {}
    /\ UNCHANGED <<disk, mapped, created, gone, present, lenKnown, writing, written, fatal, fpc, fpos, fstart, cent, cfile, deleterVars>>

\* demand.use ends (ScratchServer.kt:95): the body is sent, a read threw, or the player hung up.
Close ==
    /\ cpc \in {"got", "close"}
    /\ demand' = [demand EXCEPT ![cent] = None]                             \* :216
    /\ fcancel' = Wake(cent, None, fatal[cent])                             \* :218
    /\ fquiet' = [fquiet EXCEPT ![cent] = FALSE]
    /\ cpc' = "idle"
    /\ ev' = {}
    /\ UNCHANGED <<disk, mapped, created, gone, present, lenKnown, writing, written, fatal, fpc, fpos, fstart, cent, cpos, cfile, late, deleterVars>>

-----------------------------------------------------------------------------
\* Scratch.delete(key) (:53-62), from eviction, clearBuffer, or a deleted download.

\* Reads entries[key] (:54). Not finding one, it skips release (:55).
DelLookup ==
    /\ dpc = "idle"
    /\ DeleteFindsEntry => mapped # 0
    /\ dent' = mapped
    /\ dseen' = created
    /\ dpc' = IF mapped = 0 THEN "remove" ELSE "release"
    /\ ev' = {}
    /\ UNCHANGED <<disk, mapped, created, entryVars, fillerVars, clientVars>>

\* release() (:230-235) refuses while a demand is open.
DelRefused ==
    /\ dpc = "release"
    /\ demand[dent] # None                                                  \* :232
    /\ dpc' = "idle"
    /\ ev' = {}
    /\ UNCHANGED <<disk, mapped, created, entryVars, fillerVars, clientVars, dent, dseen>>

\* release() shuts the entry down (:237-244): gone, and its call cancelled.
DelRelease ==
    /\ dpc = "release"
    /\ demand[dent] = None                                                  \* :232
    /\ gone' = [gone EXCEPT ![dent] = TRUE]                                 \* :239
    /\ fcancel' = [fcancel EXCEPT ![dent] = TRUE]                           \* :242
    /\ dpc' = "remove"
    /\ ev' = {}
    /\ UNCHANGED <<disk, mapped, created, present, lenKnown, writing, written, demand, fatal, fpc, fpos, fstart, fquiet, clientVars, dent, dseen>>

\* deleteRecursively (:59). "delete-open": an entry still has a demand open. "delete-newer": a file
\* belongs to an entry created after this delete read the map.
RemoveFolder ==
    /\ ev' = (IF HasBytes /\ \E g \in Gens : ~gone[g] /\ demand[g] # None THEN {"delete-open"} ELSE {})
             \cup (IF NewerThan(lenFile, dseen) \/ \E i \in Chunks : NewerThan(chunkFile[i], dseen) \/ NewerThan(partFile[i], dseen)
                   THEN {"delete-newer"} ELSE {})
    /\ folder' = FALSE /\ lenFile' = None
    /\ chunkFile' = [i \in Chunks |-> None] /\ partFile' = [i \in Chunks |-> None]

\* entries.remove(key) and deleteRecursively under the Scratch lock (:57-60), the same lock entry() takes.
DelRemove ==
    /\ dpc = "remove"
    /\ mapped' = 0                                                          \* :58
    /\ IF PreFixDeleteUnlocked
       THEN /\ dpc' = "files" /\ ev' = {} /\ UNCHANGED disk
       ELSE /\ RemoveFolder /\ dpc' = "idle"
    /\ UNCHANGED <<created, entryVars, fillerVars, clientVars, dent, dseen>>

\* Before 5beae1e, deleteRecursively ran after the lock was released.
DelFiles ==
    /\ dpc = "files"
    /\ RemoveFolder
    /\ dpc' = "idle"
    /\ UNCHANGED <<mapped, created, entryVars, fillerVars, clientVars, dent, dseen>>

-----------------------------------------------------------------------------
\* The filler thread of entry g: fill() (:424-479) and fetch() (:499-612).

\* fill() picks a target (:427-430) and fetch() creates the call (:501-506). A call made after the
\* entry is gone is not cancelled; HeaderArrives covers that by ignoring the cancel.
Pick(g) ==
    /\ fpc[g] = "pick"
    /\ Target(g) # None                                                     \* :430
    /\ fstart' = [fstart EXCEPT ![g] = Target(g)]
    /\ fcancel' = [fcancel EXCEPT ![g] = FALSE]
    /\ fquiet' = [fquiet EXCEPT ![g] = TRUE]
    /\ fpc' = [fpc EXCEPT ![g] = "header"]
    /\ ev' = {}
    /\ UNCHANGED <<disk, mapped, created, entryVars, fpos, clientVars, deleterVars>>

FillerExit(g) ==
    /\ fpc[g] = "pick"
    /\ Target(g) = None
    /\ demand[g] = None \/ gone[g]                                          \* :431
    /\ fpc' = [fpc EXCEPT ![g] = "none"]
    /\ ev' = {}
    /\ UNCHANGED <<disk, mapped, created, entryVars, fpos, fstart, fcancel, fquiet, clientVars, deleterVars>>

\* The response headers arrive, possibly before a cancel (:508-523), then the length block under
\* the entry lock (:524-538), which does not check gone: mkdirs and the length file.
HeaderArrives(g) ==
    /\ fpc[g] = "header"
    /\ fpos' = [fpos EXCEPT ![g] = IF IgnoresRange THEN 0 ELSE fstart[g]]   \* :512-518
    /\ IF lenKnown[g]                                                       \* :527
       THEN /\ ev' = {} /\ UNCHANGED <<folder, lenFile, lenKnown>>
       ELSE /\ folder' = TRUE                                               \* :529
            /\ lenFile' = g                                                 \* :530
            /\ lenKnown' = [lenKnown EXCEPT ![g] = TRUE]                    \* :534
            /\ ev' = IF NewerThan(lenFile, g) THEN {"length"} ELSE {}
    /\ fpc' = [fpc EXCEPT ![g] = "decide"]
    /\ UNCHANGED <<chunkFile, partFile, mapped, created, gone, present, writing, written, demand, fatal, fstart, fcancel, fquiet, clientVars, deleterVars>>

\* A cancelled call throws (:463-465); writing is -1 here, so the finally block has nothing to do.
CallFails(g) ==
    /\ fpc[g] = "header"
    /\ fcancel[g]
    /\ fpc' = [fpc EXCEPT ![g] = "pick"]
    /\ ev' = {}
    /\ UNCHANGED <<disk, mapped, created, entryVars, fpos, fstart, fcancel, fquiet, clientVars, deleterVars>>

Have(g) == fpos[g] \in present[g]                                         \* :548

\* The body reaches chunk i = fpos and the target is i (:549-553).
StartChunk(g) ==
    /\ fpc[g] = "decide"
    /\ ~Have(g)
    /\ Target(g) = fpos[g]
    /\ writing' = [writing EXCEPT ![g] = fpos[g]]
    /\ written' = [written EXCEPT ![g] = FALSE]
    /\ fpc' = [fpc EXCEPT ![g] = "open"]
    /\ ev' = {}
    /\ UNCHANGED <<disk, mapped, created, gone, present, lenKnown, demand, fatal, fpos, fstart, fcancel, fquiet, clientVars, deleterVars>>

\* A 200 response sends chunks before the wanted one. Missing ones before the target are skipped
\* (:555), and so are present ones (:548, :559-567). The bytes are read and dropped.
SkipAhead(g) == IgnoresRange /\ Target(g) # None /\ fpos[g] < Target(g) /\ ~PreFixNoSkip    \* :555
Skips(g) == (IgnoresRange /\ Have(g)) \/ (~Have(g) /\ SkipAhead(g))

SkipChunk(g) ==
    /\ fpc[g] = "decide"
    /\ Skips(g)
    /\ IF fpos[g] + 1 \in Chunks
       THEN /\ fpos' = [fpos EXCEPT ![g] = fpos[g] + 1] /\ ev' = {}
            /\ UNCHANGED fpc
       ELSE /\ fpc' = [fpc EXCEPT ![g] = "pick"] /\ ev' = ReturnEv(g)        \* :542, :611
            /\ UNCHANGED fpos
    /\ UNCHANGED <<disk, mapped, created, entryVars, fstart, fcancel, fquiet, clientVars, deleterVars>>

\* Reading the skipped bytes on a cancelled call throws (:563-564).
SkipFails(g) ==
    /\ fpc[g] = "decide"
    /\ Skips(g)
    /\ fcancel[g]
    /\ fpc' = [fpc EXCEPT ![g] = "pick"]
    /\ ev' = {}
    /\ UNCHANGED <<disk, mapped, created, entryVars, fpos, fstart, fcancel, fquiet, clientVars, deleterVars>>

\* fetch returns (:556, :561): the chunk here is not wanted. writing is -1, so finally does nothing.
StopFetch(g) ==
    /\ fpc[g] = "decide"
    /\ \/ Have(g) /\ ~IgnoresRange                                          \* :561
       \/ ~Have(g) /\ Target(g) # fpos[g] /\ ~SkipAhead(g)                  \* :556
    /\ fpc' = [fpc EXCEPT ![g] = "pick"]
    /\ ev' = ReturnEv(g)
    /\ UNCHANGED <<disk, mapped, created, entryVars, fpos, fstart, fcancel, fquiet, clientVars, deleterVars>>

\* A body read on a cancelled call throws (:576-577).
BodyFails(g) ==
    /\ fpc[g] = "write"
    /\ fcancel[g]
    /\ fpc' = [fpc EXCEPT ![g] = "finally"]
    /\ ev' = {}
    /\ UNCHANGED <<disk, mapped, created, entryVars, fpos, fstart, fcancel, fquiet, clientVars, deleterVars>>

\* FileOutputStream(partFile(i)) outside any lock and without a gone check (:569-573): creates the
\* part, or truncates the one at that path. With no folder it throws StorageError, and with
\* onStorageFull() false the entry fails (:441-453).
OpenPart(g) ==
    /\ fpc[g] = "open"
    /\ LET i == writing[g] IN
       IF folder
       THEN /\ partFile' = [partFile EXCEPT ![i] = g]
            /\ ev' = IF NewerThan(partFile[i], g) THEN {"truncate"} ELSE {}
            /\ fpc' = [fpc EXCEPT ![g] = "write"]
            /\ UNCHANGED fatal
       ELSE /\ fatal' = [fatal EXCEPT ![g] = TRUE]
            /\ fpc' = [fpc EXCEPT ![g] = "finally"]
            /\ ev' = {} /\ UNCHANGED partFile
    /\ UNCHANGED <<folder, lenFile, chunkFile, mapped, created, gone, present, lenKnown, writing, written, demand, fpos, fstart, fcancel, fquiet, clientVars, deleterVars>>

\* The chunk's bytes go out through the open stream; after each write the lock re-checks the target
\* (:584-589), so a chunk nobody wants any more is left as a part.
WriteChunk(g) ==
    /\ fpc[g] = "write"
    /\ written' = [written EXCEPT ![g] = TRUE]                              \* :585
    /\ IF Target(g) = writing[g]                                            \* :588
       THEN fpc' = [fpc EXCEPT ![g] = "commit"]
       ELSE fpc' = [fpc EXCEPT ![g] = "finally"]
    /\ ev' = IF Target(g) = writing[g] THEN {} ELSE ReturnEv(g)
    /\ UNCHANGED <<disk, mapped, created, gone, present, lenKnown, writing, demand, fatal, fpos, fstart, fcancel, fquiet, clientVars, deleterVars>>

\* Under the entry lock: a gone entry leaves the part (:599); else the part becomes the chunk (:600-606).
Commit(g) ==
    /\ fpc[g] = "commit"
    /\ LET i == writing[g] IN
       IF gone[g] /\ ~PreFixNoGoneCheck                                     \* :599
       THEN /\ fpc' = [fpc EXCEPT ![g] = "finally"] /\ ev' = {}
            /\ UNCHANGED <<chunkFile, partFile, present, writing, written, fatal, fpos>>
       ELSE IF partFile[i] = None                                           \* :600 rename fails
       THEN /\ fatal' = [fatal EXCEPT ![g] = TRUE]
            /\ fpc' = [fpc EXCEPT ![g] = "finally"] /\ ev' = {}
            /\ UNCHANGED <<chunkFile, partFile, present, writing, written, fpos>>
       ELSE /\ chunkFile' = [chunkFile EXCEPT ![i] = partFile[i]]
            /\ partFile' = [partFile EXCEPT ![i] = None]
            /\ ev' = IF NewerThan(partFile[i], g) \/ NewerThan(chunkFile[i], g) THEN {"move"} ELSE {}
            /\ present' = [present EXCEPT ![g] = @ \cup {i}]               \* :601
            /\ writing' = [writing EXCEPT ![g] = None]
            /\ written' = [written EXCEPT ![g] = FALSE]
            /\ IF i + 1 \in Chunks
               THEN /\ fpos' = [fpos EXCEPT ![g] = i + 1] /\ fpc' = [fpc EXCEPT ![g] = "decide"]
               ELSE /\ fpc' = [fpc EXCEPT ![g] = "pick"] /\ UNCHANGED fpos
            /\ UNCHANGED fatal
    /\ UNCHANGED <<folder, lenFile, mapped, created, gone, lenKnown, demand, fstart, fcancel, fquiet, clientVars, deleterVars>>

\* finally (:467-477): a gone entry leaves the part, since the folder may be a newer entry's (:472).
Finally(g) ==
    /\ fpc[g] = "finally"
    /\ LET i == writing[g] IN
       IF (~gone[g] \/ PreFixNoGoneCheck) /\ partFile[i] # None             \* :472
       THEN /\ partFile' = [partFile EXCEPT ![i] = None]
            /\ ev' = IF NewerThan(partFile[i], g) THEN {"delete-part"} ELSE {}
       ELSE /\ ev' = {} /\ UNCHANGED partFile
    /\ writing' = [writing EXCEPT ![g] = None]                              \* :473
    /\ written' = [written EXCEPT ![g] = FALSE]
    /\ fpc' = [fpc EXCEPT ![g] = "pick"]
    /\ UNCHANGED <<folder, lenFile, chunkFile, mapped, created, gone, present, lenKnown, demand, fatal, fpos, fstart, fcancel, fquiet, clientVars, deleterVars>>

-----------------------------------------------------------------------------
Serving == Demand \/ ReadFind \/ ReadFail \/ ReadOpen \/ ReadRetry \/ ReadNext \/ Close
Deleting == DelRefused \/ DelRelease \/ DelRemove \/ DelFiles
Filling(g) ==
    \/ Pick(g) \/ FillerExit(g) \/ HeaderArrives(g) \/ CallFails(g)
    \/ StartChunk(g) \/ SkipChunk(g) \/ SkipFails(g) \/ StopFetch(g) \/ BodyFails(g)
    \/ OpenPart(g) \/ WriteChunk(g) \/ Commit(g) \/ Finally(g)

\* Lookup and DelLookup start a new request or delete call; nothing forces one.
Next == Lookup \/ DelLookup \/ Tick \/ Serving \/ Deleting \/ \E g \in Gens : Filling(g)

\* Time passes, and every thread that can run eventually runs.
Fairness == WF_vars(Tick) /\ WF_vars(Serving) /\ WF_vars(Deleting) /\ \A g \in Gens : WF_vars(Filling(g))

Spec == Init /\ [][Next]_vars /\ Fairness

-----------------------------------------------------------------------------
\* Once a newer entry exists for the key, nothing done for an older entry truncates, replaces,
\* renames or deletes a file the newer entry created or took over.
OlderEntryKeepsOut == ev \cap {"truncate", "length", "move", "delete-part", "delete-newer"} = {}

\* An older entry's filler never renames or deletes a newer entry's part (the gone checks at :472, :599).
OldFillerLeavesParts == ev \cap {"move", "delete-part"} = {}

\* An older entry's filler never truncates a newer entry's part.
OldFillerNeverTruncates == "truncate" \notin ev

\* delete removes no bytes while a reader, buffer or download has the key open (:52).
DeleteSparesOpen == "delete-open" \notin ev

\* fill() never sends the same request again at once because the last response stored nothing.
NoRequestLoop == "refetch" \notin ev

\* Every read call returns: data, end of file, or an exception.
ReadsReturn == (cpc \in {"check", "read"}) ~> (cpc \notin {"check", "read"})
=============================================================================
