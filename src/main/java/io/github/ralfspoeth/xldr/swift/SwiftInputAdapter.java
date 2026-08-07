package io.github.ralfspoeth.xldr.swift;

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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
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

    private static final Pattern TB_PATTERN = Pattern.compile(
            "(?m)^(:[0-9]{2}[A-Z]?:)([\\s\\S]*?)(?=^:[0-9]{2}[A-Z]?:|$)\n");

    record ParsedMessage(
            String basicHeader,
            String applicationHeader,
            @Nullable String userHeader,
            String textBlock,
            @Nullable String trailer
    ) {}

    record FieldSelector(int block, int tag, Pattern pattern, int group) {
        FieldSelector {
            if (block < 1 || block > 5) {
                throw new IllegalArgumentException("Block number must be between 1 and 5");
            }
        }
    }

    record RecordSelector(List<String> tags, Map<String, FieldSelector> fieldSelectors) {}

    private final Map<String, RecordSelector> recordSelectors;

    public SwiftInputAdapter(InputSpec inputSpec) {
        recordSelectors = inputSpec.recordSelectors()
                .stream()
                .collect(toMap(
                        RecordSelectorSpec::name,
                        rs -> new RecordSelector(
                                parseTags(rs.selector()),
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

    private FieldSelector parseFields(String selector) {
        int firstSlash = selector.indexOf('/');
        int secondSlash = selector.indexOf('/', firstSlash + 1);
        int lastSlash = selector.lastIndexOf('/');
        if (firstSlash == -1 || lastSlash == -1 || firstSlash + 1 == secondSlash || lastSlash == firstSlash) {
            throw new IllegalArgumentException("Selector %s must match [1-5]?/([0-9]/)?<pattern>/[\\d]*");
        } else {
            return new FieldSelector(
                    firstSlash == 0 ? 4 : parseInt(selector.substring(0, firstSlash)),
                    secondSlash == lastSlash ? 0 : parseInt(selector.substring(firstSlash + 1, secondSlash)),
                    Pattern.compile(
                            secondSlash == lastSlash ?
                                    selector.substring(firstSlash + 1, lastSlash) :
                                    selector.substring(secondSlash + 1, lastSlash)
                    ),
                    lastSlash == selector.length() - 1 ? 0 : parseInt(selector.substring(lastSlash + 1))
            );
        }
    }

    private List<String> parseTags(@Nullable String selector) {
        return ofNullable(selector)
                .map(s -> s.split("/"))
                .map(List::of)
                .orElse(List.of());
    }

    @Override
    public Result parse(InputStream source, String recordSelector, Set<String> fieldSelectors) throws IOException {
        try (var ais = new AsciiReader(source)) {
            var contents = ais.readAllAsString();
            var matcher = MT_PATTERN.matcher(contents);
            var rs = recordSelectors.get(recordSelector);
            if (rs != null && matcher.matches()) {
                var parsed = new ParsedMessage(
                        matcher.group(1),
                        matcher.group(2),
                        matcher.group(3),
                        matcher.group(4),
                        matcher.group(5)
                );
                var fields = rs.fieldSelectors.keySet()
                        .stream()
                        .filter(fieldSelectors::contains)
                        .map(s -> new Field(s, String.class))
                        .toList();
                List<Row> rows = parseRows(parsed, rs);
                return new Result(fields, rows.stream());
            } else {
                return new Result(List.of(), Stream.of());
            }
        }
    }

    private static List<Row> parseRows(ParsedMessage parsed, final RecordSelector rs) {
        List<Row> rows = new ArrayList<>();
        var tbMatcher = TB_PATTERN.matcher(parsed.textBlock);
        List<String> tags = new ArrayList<>();
        int index = 0;
        while (tbMatcher.find()) {
            if (tbMatcher.group(1).equals(rs.tags.get(index))) {
                tags.add(tbMatcher.group(2));
                index++;
            }
            if (index == rs.tags.size()) {
                rows.add(new Row() {
                    final List<String> data = List.copyOf(tags);
                    @Override
                    public @Nullable Object get(String name) {
                        return ofNullable(rs.fieldSelectors().get(name))
                                .map(fs -> {
                                    var m = fs.pattern.matcher(data.get(fs.tag));
                                    return m.matches() ? m.group(fs.group) : null;
                                })
                                .orElse(null);
                    }
                });
                index=0;
                tags.clear();
            }
        }
        return rows;
    }
}
