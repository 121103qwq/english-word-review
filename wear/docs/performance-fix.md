# Study transition performance fix

The gesture study path retains its ScrollView instead of replacing the Activity's
content view for each transition. It also reuses its card
labels and buttons, changing their text and visibility when a word is revealed
or advanced. A reveal shows the answer immediately, with grading disabled until
the background save commits. Answer-and-advance displays the next card only after
that atomic transaction commits. The original interaction barrier, context/epoch checks,
atomic answer-and-advance, and undo remain in place.

JSON snapshots remain deeply detached, but copy their object/array trees directly
instead of serializing and parsing the same content. Atomic file persistence and
the event/sync protocol remain unchanged. The root pack/packages/words branches
reuse committed serialized content while their object identities remain unchanged.
Package activation and sync replace these branches; bootstrap insertion explicitly
invalidates the cache. Failed writes do not advance the cache. A complete state
file is still atomically committed; storage latency is not hidden by showing an
uncommitted next word. Store logs split serialization and durable-commit time.

StudyEngine builds one validated active-event snapshot per queue calculation and
shares it with card projection and daily counts. Event merges detach output once,
JSON copies walk trees without round-trip parsing, and ID patterns are compiled
once. These changes retain event conflict checks, undo ordering and queue rules.

Regression coverage includes nested JSON copy isolation, gesture control reuse
on both round-screen sizes, and the existing persistence, undo, sync and
process-recreation checks. Tests use synthetic data without an emulator.

`WordWatchPerformance` logs separate gesture background `workMs` from UI
`bindMs`, without words, records, tokens or sensor samples. These are diagnostic
timings, not proof of physical frame latency. Hardware timing is still required
after this fix is installed. MainActivity prepares study data and writes actions
on a single background executor; rendering reads that prepared state. Automatic
sync preflight also reads the store on its IO executor instead of the main thread.
No persistence migration or additional framework is introduced.

Personal trial rearms after 250 ms of calm following a card change instead of
750 ms. The 400 ms segment-end gap stays intact to keep the two pinches together.
When template distances are close, sustained rub texture can resolve a missing
pinch pulse without adding another rejection condition. This is still a personal
trial, not a claim of independent accuracy or zero false positives.
