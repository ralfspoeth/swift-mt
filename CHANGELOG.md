# Changelog

Notable changes per release, newest first.

Before `1.0` the version has two components and the second carries everything: a release that only fixes something
and a release that adds one both move it, because inventing a patch component to classify one change would be more
ceremony than this project has earned. What a release contains is written here rather than inferred from its number.

The versions are the git tags `swift-mt-<version>`. Nothing is published to Maven Central; each release is a jar
attached to the GitHub release of the same name.

## 0.2

About where the jar can be deployed, and about the first test of the example this README has been printing since
0.1.

### Fixed

- **The adapter was invisible on a classpath.** It declared itself only with `provides` in its `module-info`, and
  that directive is read only for a resolved *named* module - so the jar in a war, or in anything Quarkus builds,
  landed in the unnamed module and had its declaration ignored entirely. Such a deployment found no adapter for the
  MT mime types and refused every spec naming one, with nothing to suggest the jar was present and unread. A
  `META-INF/services` entry now carries the same line for that case.

  One of the two declarations is dead weight in any given deployment and there is no telling in advance which. xldr
  reached the same conclusion for its five bundled adapters at 1.3.0; this module was released before that and did
  not catch up until now.

### Changed

- **The README no longer promises the module path specifically.** Its first paragraph said dropping the jar there
  was the whole of the installation, which is what made the gap above invisible for a release. It now says either
  path, and says why there are two declarations, so that whoever meets the duplication next does not tidy one of
  them away.

- **The parent moves to `plumbum` 3.0.4**, which requires `central-publishing-maven-plugin` 0.12.0 - without which
  publishing does not work under Maven 3.10.

### Internal

- **The worked example in the README is now a test.** `SwiftFeedIT` already drove the whole chain, but with every
  column a varchar and every field untyped, which proves nothing about what a declared type does. The documented
  spec now runs too, into a table whose columns are `timestamp` and `decimal`, so a value that did not convert
  cannot reach the assertions. Writing it found the printed example broken in two ways - see *Known* below.

- **A green run is quiet.** The integration test's logging hook had been adding a second `ConsoleHandler` on top of
  the one `logging.properties` configures, so every line the server logged was printed twice; and the level was
  `ALL`, which at a one-second scan interval buried the useful lines under hundreds of identical DEBUG ones. The
  handler is gone and the level is INFO, which keeps the four lines that narrate the test, the row count among
  them. What explains a failure is the diagnosis assembled when the wait gives up.

### Known

- **The worked example printed in the README does not work**, and is unchanged in this release. It declares
  `"type": "DATE"`, a type xldr renamed `TEMPORAL` at 0.47 and now refuses by name, so the spec as printed fails
  when the adapter is built. And it calls block 1 the account: the account is `:25:`, which a `:61:~:86:` record
  cannot reach, because a block-4 field selector addresses a tag *of the record* and `:25:` sits once above the
  bookings. The test added in this release uses the corrected form. The document has not been changed to match,
  because what to do about the second one is a question rather than an erratum - rename it, drop it, or make header
  tags reachable from a repetition record, which would be a feature.

## 0.1

First release, recorded here after the fact: the adapter, its blocks-and-tags reading of a FIN message, the tag
group and `seq~` record selectors, and the field selector grammar.
