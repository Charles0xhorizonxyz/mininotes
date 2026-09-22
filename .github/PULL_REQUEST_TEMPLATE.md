## What this changes

<!-- One or two sentences. Why, not what — the diff says what. -->

## What you ran

```
cd android && ./gradlew testReleaseUnitTest lintRelease assembleRelease
```

<!-- Paste the result. If you tested on a phone, say which phone and what you did. -->

## Check

- [ ] The pad still works with no node, no network and no account.
- [ ] Nothing here can silently lose writing.
- [ ] Tests added for changed persistence, merge or sync behaviour.
- [ ] Any schema change is a new ordered step, not an edit to an old one.
- [ ] No notes, keys, addresses, keystores or real-device logs committed.
