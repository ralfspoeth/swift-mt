# swift-ia

An [xldr](https://github.com/ralfspoeth/xldr) input adapter for SWIFT FIN
messages - MT940 statements, MT103 transfers, and the rest of the MT family.

It is a separate project rather than a module of the toolkit because nothing in
xldr needs it: the adapter is found at run time through `ServiceLoader`, so
dropping the jar on the module path is the whole of the installation.

## Who this is for

Someone who knows the Message Reference Guide for the message they are loading.

That is a design premise rather than an apology. This adapter has **no knowledge
of any message type**: it does not know that an MT940 exists, that `:61:` is
significant, that `:62a:` follows the bookings, or that `:86:` may not appear
without a `:61:` before it. It reads blocks, it reads tags, and it does what the
spec tells it. Everything above that comes from the reader.

The alternative - a table of message grammars - would be a second product,
would need maintaining against a yearly standards release, and would be wrong
for any bank that deviates from it. It also would not help: the value sets in a
`:61:` transaction-type code are the same problem again one level down.

Several entries under [Known limitations](#known-limitations) are only
acceptable because of this premise. A spec that names the wrong tag loads
nothing rather than complaining, and a malformed statement loads quietly, on the
grounds that the person writing the spec can read the message.

## What a SWIFT message looks like

A FIN message is five brace-delimited blocks, of which the first, second and
fourth are always present:

    {1:F01BANKDEFFXXXX0000000000}          basic header - who sent it
    {2:O9401044260806BICODEMMXXXX...N}     application header - message type, direction
    {3:{108:20260806001}}                  user header, optional
    {4:
    :20:STMT20260806
    :25:DE12345678901234567890
    ...
    -}                                    text block - the message itself
    {5:{MAC:12345678}{CHK:ABC123XYZ456}}   trailer, optional

Block 4 is a sequence of **tags**, each `:nn:` or `:nnA:` followed by its
content, which runs until the next tag and may be several lines long -
`:86:` on a statement and `:50K:` on a transfer usually are. Neither the
separating line break nor the one before `-}` belongs to the content.

The other four blocks are handed over as the raw text between their
braces; this adapter does not take them apart further, because what is worth
extracting from them differs per message type and a regular expression says it
more directly than a parser would.

## Record selectors: a tag sequence

A record selector is a list of tags separated by `~`, matched against the text
block. It is required: xldr lets a record selector leave it out, and for a CSV
or a fixed-length file that sensibly means "every record", but here a record
*is* a tag sequence and an absent one names nothing. A spec that omits it is
refused when the adapter is built, with the offending selector named.

    ":61:~:86:"     one record per statement line and its information
    ":61:"          one record per statement line
    ":20:"          one record, the transaction reference

The adapter walks the tags of block 4 in order, and emits a record every time it
has seen the whole sequence. A tag that is not the one expected next is skipped,
so `:61:~:86:` over

    :61: ... :86: ... :61: ... :86: ... :62F: ...

yields two records, and the `:62F:` at the end is ignored. **Only complete
sequences produce a record**: a trailing `:61:` with no `:86:` after it is
dropped rather than emitted with a missing half.


A tag may be written with a lower-case `a` in place of its option letter, which
is the Message Reference Guide's own notation: `:62a:` is field 62 in whichever
form the message carries, `:62F:` on an end-of-day statement and `:62M:` on an
intraday one, and `:25a:` covers both `:25:` and `:25P:`. There is no ambiguity
with a literal tag, because a real option letter is always upper case - `:62F:`
stays literal and does not match `:62M:`.

Tags are checked for shape when the adapter is built. A selector of `61` or
`:610:` would match nothing in any message and load nothing without complaining,
so it is refused instead.

Order within the sequence is the order in the message, not a set. `:86:~:61:`
over the same input pairs each information line with the *following* statement
line, which is a different and probably wrong reading - and yields one record
rather than two.

## Field selectors: block, tag, pattern, group

    [<block>]~[<tagNo>~]<pattern>~[<groupNo>]

with `4` the default block, `0` the default tag and `0` the default group. Read
it as: take this block, and within block 4 this tag of the record; match the
pattern against it; hand over this capture group. The separator is a tilde
because it is the one character absent from every SWIFT character set that is
also inert in a regular expression - see [SEPARATOR.md](SEPARATOR.md).

`<block>` is a block *identifier*, not a number from 1 to 5. The standard defines
it as one to three alphanumeric characters, and the five numbered blocks are only
the ones a FIN user-to-user message happens to carry - SWIFT appends a system
block `{S:...}` on the way out of the network. So the identifier is checked for
shape when the adapter is built, and one the message does not carry yields `null`
at parse time, exactly as an absent block 3 or 5 does. Only block `4` is
addressed by tag; every other block is handed over whole.

| Selector | Reads |
|---|---|
| `1~.*~0` | the whole basic header |
| `2~.*~0` | the whole application header |
| `~.*~0` | the first tag of the record, whole (block 4 by default) |
| `~1~.*~0` | the second tag of the record, whole |
| `~([0-9]{6}).*~1` | the first six characters of the first tag - a value date |
| `~1~.*UNIT/([0-9]+),~1` | a quantity out of `:AGGR//UNIT/1500,` - slashes and all |
| `5~.*~0` | the whole trailer |

Two things to note. `<tagNo>` is an **index into the record selector's tag
list**, not a SWIFT tag number: with `":61:~:86:"`, tag `0` is the `:61:` content
and tag `1` the `:86:`. And the pattern is matched with `matches()`, not `find()`
- it must describe the whole segment, which is why every example above ends in
`.*`. A pattern that does not match the whole segment yields `null` for that
field rather than an error, so the row still loads with the column empty.

Patterns are compiled with `DOTALL`, so `.` crosses a line break. A tag may run
to several lines, and `.*` there means the whole of it rather than its first
line.

Group `0` is the whole match, exactly as `Matcher.group(0)` means it.

## A worked example

For an MT940 statement, one row per booking with the value date, the amount side
and the free-text information:

```json
{
  "input": {
    "mimeType": "text/x-swift",
    "accepts": "glob:*.sta",
    "recordSelectors": [
      {
        "name": "booking",
        "selector": ":61:~:86:",
        "fieldSelectors": [
          {"name": "account",   "selector": "1~.*~0",                "type": "STRING"},
          {"name": "valueDate", "selector": "~([0-9]{6}).*~1",       "type": "STRING"},
          {"name": "side",      "selector": "~[0-9]{10}([CD]).*~1",  "type": "STRING"},
          {"name": "info",      "selector": "~1~.*~0",               "type": "STRING"}
        ]
      }
    ]
  }
}
```

`valueDate` arrives as `260806` - six characters of `yymmdd`. Convert it in the
mapping rather than here, with `${parse(valueDate, 'yyMMdd')}`: the adapter deals
in text and leaves types to the spec.

## MIME types

The factory claims `text/x-swift`, `application/x-swift`, `text/plain` and
`application/octet-stream`. The last two are worth a thought before deploying
alongside other adapters: they are the natural MIME types for a fixed-length or
delimited file too, and whichever factory `ServiceLoader` happens to return first
wins. Prefer `text/x-swift` in a spec, and treat the generic two as a
convenience for a feed that has no better name for its input.

## Known limitations

**A pattern may not contain `~`.** The parts of a selector are found by
position, so the separator cannot appear inside a pattern. With this separator
that forbids nothing worth saying: the tilde is in none of the three SWIFT
character sets, so it cannot occur in the data, and it is not a
regular-expression metacharacter, so nothing in the pattern language is lost
either. [SEPARATOR.md](SEPARATOR.md) has the reasoning, including why `/`, `|`,
`_` and `§` were each rejected.

**Field order is not the spec's order.** The selectors are collected into a
`HashMap`, so `Result.fields()` comes back in an arbitrary order. Harmless for
loading, which binds by name, and confusing when reading the output.

**A line break inside a tag is handed over as it was found.** Tags are separated
on `\R`, so a message with DOS line endings parses the same as one without - but
the `\r\n` between two lines of one `:86:` reaches the field as it stands. A
pattern that has to look inside a multi-line tag should allow for it.

**Only ASCII is read.** The input is taken a byte at a time as a character,
which is right for the SWIFT X character set and wrong for anything else: a
byte above 0x7F becomes a character nobody wants rather than an error.
