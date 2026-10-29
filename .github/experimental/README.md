# Experimental composition maintenance

`layers.json` remains the ordered list of **mandatory** feature branches. They
are never dropped by build retries. Optional upstream PRs are considered only
after the mandatory baseline passes both release environments.

## Reviewed merge resolutions

`merge-layer.py LAYER REF` first attempts a normal Git merge. On conflict it
only replaces exact pairs of `ours` and `theirs` fragments listed in
`resolutions/manifest.json`, scoped to the layer and file. The `.resolved` file
is the reviewed combination. Surrounding automatic merges are preserved.
Unknown fragments, paths, or conflict formats abort the merge and fail the run.
There is no whole-file side selection, merge strategy preference, or fallback
that drops a mandatory feature.

The experimental-channel resolution keeps upstream's `WriteProperties` task
and official-build verification while adding the two experimental update
properties. The Metadata resolution keeps upstream's `REPOSITORY_URL` and the
channel layer's packaged/runtime update overrides. RPM's `CreateRpm` import,
task, shared `releaseChannel`, and `ReleaseType.EXPERIMENTAL` merge normally;
the native-decoration/JavaFX layer is also retained unchanged.

To refresh a resolution after upstream drift, reproduce the merge in a clean
worktree, review both conflicting fragments and surrounding changes, update
the three fragment files, and run the safety tests and composition validation.
Do not replace entire source files with a saved snapshot.

## Integration patches

`apply-compatibility.py` applies the small patches in `compatibility/` after
mandatory layers and again after optional PRs. Patches are checked before
application, committed to the generated branch, and recognized when already
applied. A source file absent because its optional PR was not selected is not
patched. If a present file no longer matches either direction, composition
fails for human review.

- `experimental-update-source.patch` points the channel metadata to this
  repository, matching where Create Release uploads `update-exp.json`.
- `language-javafx17.patch` replaces PR #6689's JavaFX 19+ `ObservableValue.map`
  with a JavaFX 17 listener and handles a cleared selection.
- `fetch-partial-response.patch` flushes PR #7023's intentionally truncated
  HTTP response bodies before closing them. Newer JDK HttpServer implementations
  can otherwise discard the buffered fragment, leaving no bytes to resume.
  All range, hash, byte-content and retry-count assertions remain intact.

Remove an optional compatibility patch when its upstream PR incorporates the
fix, after updating validation. Do not relax its context to accept unknown code.

## Validation

`python3 -m unittest discover -s .github/scripts -p 'test_*.py' -v` checks
reviewed fragments, preservation of surrounding changes, and abort behavior
for unknown conflicts using a real Git repository.

`Experimental composition validation` builds two compositions on JDK 17 and
25: current upstream plus all three layers (and #7023 for the download
regression), and the original `release/20261020` composition containing #6689
and #7023. It runs FetchTaskTest explicitly before the full build/check and
uploads test reports even on failure. It neither pushes release branches nor
publishes releases.

Daily composition now uses Liberica JDK 17 with JavaFX for `build`, then JDK 25
with the layer's JavaFX 27 for `check`, matching Create Release. Both the
mandatory baseline and final optional-PR composition must pass. The artifact
check requires all five formats (JAR/EXE/SH/DEB/RPM), checksums, experimental
version/channel and the correct packaged update URLs. Failed checks are not
ignored; diagnostics are uploaded and no release is triggered.
