package io.github.ralfspoeth.xldr.swift.mt;

import io.github.ralfspoeth.xldr.ia.Field;
import io.github.ralfspoeth.xldr.ia.InputAdapter;
import io.github.ralfspoeth.xldr.ia.Result;
import io.github.ralfspoeth.xldr.ia.Row;
import io.github.ralfspoeth.xldr.spec.FieldSelectorSpec;
import io.github.ralfspoeth.xldr.spec.InputSpec;
import io.github.ralfspoeth.xldr.spec.RecordSelectorSpec;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static java.lang.Integer.parseInt;
import static java.util.Optional.ofNullable;
import static java.util.stream.Collectors.toMap;

class SwiftInputAdapter implements InputAdapter {

    private static final Pattern MT_PATTERN = Pattern.compile(
            "^\\{1:(.+?)}\\s*\\{2:([IO].+?)}\\s*(?:\\{3:(.+?)}\\s*)?\\{4:(.*?)-}\\s*(?:\\{5:(.+?)})?$",
            Pattern.DOTALL
    );

    /**
     * One tag of the text block: the tag itself, then everything up to the next
     * one.
     * <p>
     * The content is whatever stands between this tag and the next, newlines
     * included - {@code :86:} on a statement and {@code :50K:} on a transfer run
     * to several lines by design, and a continuation line is part of the tag
     * rather than a thing of its own.
     * <p>
     * Neither the separating line break nor the one before {@code -}} is
     * captured: the lookahead ends the content at {@code \R} before the next
     * tag, or at the trailing whitespace before the end of the block. Because
     * {@code \R} takes {@code \r\n} as one unit, a message with DOS line endings
     * parses the same as one without - though a line break *inside* a tag is
     * handed over as it was found.
     */
    private static final Pattern TEXT_BLOCK_PATTERN = Pattern.compile(
            "(?m)^(:[0-9]{2}[A-Z]?:)([\\s\\S]*?)(?=\\R:[0-9]{2}[A-Z]?:|\\s*\\z)");

    /**
     * The block whose content is a sequence of tags, and the one a field
     * selector addresses when it names none.
     */
    private static final String TEXT_BLOCK = "4";

    /**
     * A block identifier: one to three alphanumeric characters.
     * <p>
     * Not a digit from 1 to 5. The five numbered blocks are the ones a FIN
     * user-to-user message carries, but the envelope allows others - SWIFT and
     * Alliance append a system block {@code {S:...}} to messages coming out of
     * the network - and the standard defines the identifier by shape rather than
     * by a closed set.
     */
    private static final Pattern BLOCK_ID = Pattern.compile("[0-9A-Za-z]{1,3}");

    record FieldSelector(String block, int tag, Pattern pattern, int group) {
        FieldSelector {
            if (!BLOCK_ID.matcher(block).matches()) {
                throw new IllegalArgumentException(
                        "block identifier must be one to three alphanumeric characters, was '"
                                + block + "'");
            }
        }
    }

    record RecordSelector(List<Pattern> tags, Map<String, FieldSelector> fieldSelectors) {}

    private final Map<String, RecordSelector> recordSelectors;

    public SwiftInputAdapter(InputSpec inputSpec) {
        recordSelectors = inputSpec.recordSelectors()
                .stream()
                .collect(toMap(
                        RecordSelectorSpec::name,
                        rs -> new RecordSelector(
                                parseTags(rs),
                                rs.fieldSelectors()
                                        .stream()
                                        .collect(toMap(
                                                        FieldSelectorSpec::name,
                                                        fs -> parseFields(fs.selector())
                                                )
                                        )
                        )
                ));
    }

    /**
     * Separates the parts of a selector, in both the field and the record form.
     * <p>
     * A tilde because it is the one character absent from all three SWIFT
     * character sets that is also inert in a regular expression - the parts are
     * found by position, so the separator can appear neither in the data a
     * pattern describes nor in the pattern itself. {@code SEPARATOR.md} in the
     * project root has the argument, and why {@code /}, {@code |}, {@code _} and
     * {@code §} were each rejected.
     */
    static final char SEPARATOR = '~';

    /** {@link #SEPARATOR} as a regex, for {@link String#split(String)}. */
    private static final String SEPARATOR_REGEX = Pattern.quote(String.valueOf(SEPARATOR));

    private FieldSelector parseFields(String selector) {
        int first = selector.indexOf(SEPARATOR);
        int second = selector.indexOf(SEPARATOR, first + 1);
        int last = selector.lastIndexOf(SEPARATOR);
        if (first == -1 || last == -1 || first + 1 == second || last == first) {
            throw new IllegalArgumentException(
                    "field selector '%s' must match [<block>]~[<tagNo>~]<pattern>~[<groupNo>]"
                            .formatted(selector)
            );
        } else {
            return new FieldSelector(
                    first == 0 ? TEXT_BLOCK : selector.substring(0, first),
                    second == last ? 0 : parseInt(selector.substring(first + 1, second)),
                    // DOTALL, because a tag's content may run to several lines
                    // and a selector that says .* means the whole of it. Without
                    // it the commonest selector of all silently yields null on
                    // exactly the tags that carry the most text.
                    Pattern.compile(
                            second == last ?
                                    selector.substring(first + 1, last) :
                                    selector.substring(second + 1, last),
                            Pattern.DOTALL
                    ),
                    last == selector.length() - 1 ? 0 : parseInt(selector.substring(last + 1))
            );
        }
    }

    /**
     * The tags a record is made of, in the order the message carries them,
     * separated by {@link #SEPARATOR}.
     * <p>
     * A selector is required rather than defaulted. xldr lets a record selector
     * leave it out, and for a CSV or a fixed-length file that sensibly means
     * "every record"; here a record *is* a tag sequence, so an absent one names
     * nothing and there is no reading of it that could be right. Refusing it
     * outright beats loading a file that produces no rows and no complaint.
     * <p>
     * {@link RecordSelectorSpec#requireSelector()} does the refusing, both
     * because it names the offending selector in the message - useful in a spec
     * that declares several - and because it rejects a blank one, which
     * {@code split} would otherwise turn into a single tag that matches nothing.
     *
     * @throws IllegalArgumentException if the spec left the selector out or left
     *                                  it blank
     */
    private List<Pattern> parseTags(RecordSelectorSpec spec) {
        // the quoted separator, not the bare character: split takes a regex, and
        // a bare '|' is an empty alternation that matches between every pair of
        // characters
        return Stream.of(spec.requireSelector().split(SEPARATOR_REGEX))
                .map(tag -> tagPattern(tag, spec.name()))
                .toList();
    }

    /**
     * A tag as a spec writes it: {@code :20:}, {@code :62F:}, or {@code :62a:}.
     */
    private static final Pattern TAG_SELECTOR = Pattern.compile(":[0-9]{2}[A-Za-z]?:");

    /**
     * Compiles one tag of a record selector.
     * <p>
     * A trailing <em>lower-case</em> {@code a} is the Message Reference Guide's
     * own notation for "this field, whatever its option letter": {@code 62a}
     * covers {@code :62F:} and {@code :62M:}, {@code 25a} covers {@code :25:}
     * and {@code :25P:}. It is written here as it is written there, and it can
     * be told apart from a literal tag without ambiguity, because a real option
     * letter is always upper case.
     * <p>
     * Anything else is matched literally. The shape is checked rather than
     * assumed: a selector of {@code 61} or {@code :610:} would match no tag in
     * any message and load nothing, without complaint, which is the failure this
     * adapter has spent the most effort not having.
     *
     * @throws IllegalArgumentException if {@code tag} is not a tag
     */
    private static Pattern tagPattern(String tag, String selectorName) {
        if (!TAG_SELECTOR.matcher(tag).matches()) {
            throw new IllegalArgumentException("record selector '" + selectorName
                    + "': '" + tag + "' is not a tag; expected :nn:, :nnA: or :nna:");
        }
        return tag.endsWith("a:")
                ? Pattern.compile(":" + tag.substring(1, 3) + "[A-Z]?:")
                : Pattern.compile(Pattern.quote(tag));
    }

    @Override
    public Result parse(InputStream source, String recordSelector, Set<String> fieldSelectors) throws IOException {
        try (var ais = new AsciiReader(source)) {
            var contents = ais.readAllAsString();
            var matcher = MT_PATTERN.matcher(contents);
            var rs = recordSelectors.get(recordSelector);
            if (rs != null && matcher.matches()) {
                var blocks = blocksOf(matcher);
                var fields = rs.fieldSelectors.keySet()
                        .stream()
                        .filter(fieldSelectors::contains)
                        .map(s -> new Field(s, String.class))
                        .toList();
                List<Row> rows = parseRows(blocks, rs);
                return new Result(fields, rows.stream());
            } else {
                return new Result(List.of(), Stream.of());
            }
        }
    }

    /**
     * The blocks of the message, keyed by identifier - {@code "1"} through
     * {@code "5"}, and only those that are there.
     * <p>
     * A block the message does not carry is absent from the map rather than
     * present with a null, so a selector naming it resolves the same way as one
     * whose pattern does not match: no value, no exception.
     * <p>
     * That group <em>n</em> of {@link #MT_PATTERN} holds block <em>n</em> is the
     * one place the numbering is baked in, and the only thing standing between
     * this and a message whose blocks are read as they come.
     */
    private static Map<String, String> blocksOf(Matcher matcher) {
        var blocks = new LinkedHashMap<String, String>();
        for (int group = 1; group <= matcher.groupCount(); group++) {
            var content = matcher.group(group);
            if (content != null) {
                blocks.put(String.valueOf(group), content);
            }
        }
        return blocks;
    }

    private static List<Row> parseRows(final Map<String, String> blocks, final RecordSelector rs) {
        List<Row> rows = new ArrayList<>();
        // the tag list is never empty: parseTags refuses a spec without one
        var tbMatcher = TEXT_BLOCK_PATTERN.matcher(blocks.getOrDefault(TEXT_BLOCK, ""));
        List<String> tmpTags = new ArrayList<>();
        int index = 0;
        while (tbMatcher.find()) {
            if (rs.tags.get(index).matcher(tbMatcher.group(1)).matches()) {
                tmpTags.add(tbMatcher.group(2));
                index++;
            }
            if (index == rs.tags.size()) {
                rows.add(new Row() {
                    // local copy of the tags just found
                    final List<String> data = List.copyOf(tmpTags);

                    @Override
                    public @Nullable Object get(String name) {
                        return ofNullable(rs.fieldSelectors().get(name))
                                .flatMap(this::parseField)
                                .orElse(null);
                    }

                    private Optional<Object> parseField(FieldSelector fs) {
                        // the text block is the one addressed by tag; every
                        // other block is handed over whole, and one the message
                        // does not carry is simply not in the map
                        var segment = TEXT_BLOCK.equals(fs.block())
                                // a tag beyond the record's sequence names
                                // nothing, which is a null field rather than a
                                // failed load
                                ? (fs.tag() < data.size() ? data.get(fs.tag()) : null)
                                : blocks.get(fs.block());
                        if (segment == null) {
                            return Optional.empty();
                        }
                        var matcher = fs.pattern.matcher(segment);
                        return matcher.matches() ? Optional.of(matcher.group(fs.group)) : Optional.empty();
                    }
                });
                // reset buffered input
                index = 0;
                tmpTags.clear();
            }
        }
        return rows;
    }
}
