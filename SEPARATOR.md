# Why the selector separator is `~`

A record selector separates tags, and a field selector separates block, tag,
pattern and group:

    ":61:~:86:"                 record: a statement line and its information
    "~([0-9]{6}).*~1"           field: block 4, tag 0, a pattern, group 1

The character doing the separating took four attempts to settle. This file is
the reasoning, so that the next person to look at it - or to want it changed -
does not have to rediscover it.

## The constraint

The parts of a selector are found **by position**: `indexOf` for the first two
separators, `lastIndexOf` for the last. So the separator cannot appear inside a
pattern. There is no escaping mechanism and adding one would buy little: the
patterns a spec writes are short.

That makes the choice of character load-bearing in two independent ways.

1. **It must not occur in SWIFT data.** A pattern describes the content of a
   tag or a block, so any character the data can contain is a character a
   pattern may need to match.
2. **It must not be a regular-expression metacharacter.** A pattern is a regular
   expression, so a character with a meaning in that language is one the pattern
   loses the use of.

Most obvious candidates fail one or the other. Getting both is what took the
four attempts.

## The SWIFT character sets

SWIFT defines three, and which one applies depends on the field:

| Set | Where it applies | Characters beyond letters and digits |
|---|---|---|
| **X** | the general FIN set - block 4 of an ordinary message | `/ - ? : ( ) . , ' +` CrLf space |
| **Y** | EDIFACT level A (ISO 9735); field `77F` of an MT105, upper case only | `. , - ( ) / = ' + : ? ! " % & * < > ;` space |
| **Z** | the information-service set: field `70F` of an MT568, `70G` of an MT564, `77T` of an MT103 REMIT | X and Y together, plus `@ # _` and `{` - but **not** `}` |

The trap is Z. It is easy to assume it adds only `@` and `#`; it also adds the
underscore.

## The candidates

| Character | In X | In Y | In Z | Regex metacharacter | Verdict |
|---|---|---|---|---|---|
| `/` | **yes** | **yes** | **yes** | no | unusable |
| `\|` | no | no | no | **yes** | costs alternation |
| `_` | no | no | **yes** | no | fails in three z-format fields |
| `~` | no | no | no | no | **chosen** |

### `/` - the original choice

Unusable, and not marginally. The slash is in every SWIFT character set and is
structural in the data a spec most wants to pick apart:

    :61:2608060806D1250,00NTRFBRF-998234//NONREF     the reference separator
    :28C:145/1                                       statement number / sequence
    :50K:/DE89370400440532013000                     the ordering account
    :93B::AGGR//UNIT/1500,                           a securities quantity

Under `/`, the last of those had to be matched as `~1~.*UNIT.([0-9]+),~1`, with
a `.` standing in for a slash that could not be written. That is the sort of
workaround that looks like a typo six months later.

### `|` - correct about the data, wrong about the language

The vertical bar is in none of the three character sets, so it is safe against
SWIFT content. But it is regex alternation, so `(NTRF|NCHK)` becomes
inexpressible - and alternation over transaction-type codes is exactly what
someone will want to write.

### `_` - correct about the language, wrong about the data

Not a metacharacter, and absent from X and Y. But it *is* in Z, so a pattern
over `70F`, `70G` or `77T` could not match a literal underscore in free text
that legitimately contains one. Narrow, but real, and the sort of restriction
that bites on a Friday.

### `§` - rejected for a reason unrelated to SWIFT

Absent from all three sets and not a metacharacter, so it passes both tests. It
fails a third: `§` is U+00A7, outside ASCII. A selector lives in a spec file,
and a spec file saved as ISO-8859-1 rather than UTF-8 carries a different byte
for it. The separator would then depend on the encoding of the document it is
written in, and the failure - a selector that splits in the wrong place - is not
one the reader would connect to the file's encoding.

Being awkward to type on some keyboards is the lesser objection.

### `~` - passes all three

ASCII, absent from X, Y and Z, and inert in a regular expression. The
positional-split restriction survives in principle, but there is now nothing a
pattern over SWIFT content could want to say that it forbids.

## What the choice does not fix

The separator is a named constant, `MtInputAdapter.SEPARATOR`, and
`parseRecordSelector` splits on `Pattern.quote` of it rather than on the bare character -
`String.split` takes a regular expression, and a bare `|` there is an empty
alternation that splits between every pair of characters. If the separator ever
changes again, those are the two places, and the quoting is what keeps the split
correct for whatever it changes to.

## Sources

- [SWIFT formatting rules and character sets of MT messages - Paiementor](https://www.paiementor.com/swift-formatting-rules-and-character-sets-of-mt-messages/)
- [Standards MT General Information - SWIFT](https://www2.swift.com/knowledgecentre/rest/v1/publications/usgi_20210723/2.0/usgi_20210723.pdf)
