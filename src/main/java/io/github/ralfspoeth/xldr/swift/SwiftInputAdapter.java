package io.github.ralfspoeth.xldr.swift;

import io.github.ralfspoeth.xldr.ia.InputAdapter;
import io.github.ralfspoeth.xldr.ia.Result;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

class SwiftInputAdapter implements InputAdapter {
    private static final Pattern MT_PATTERN = Pattern.compile(
            "^\\{1:(.+?)}\\s*\\{2:([IO].+?)}\\s*(?:\\{3:(.+?)}\\s*)?\\{4:(.*?)-}\\s*(?:\\{5:(.+?)})?$",
            Pattern.DOTALL
    );

    private static final Pattern TB_PATTERN = Pattern.compile(
            "(?m)^(:[0-9]{2}[A-Z]?:)([\\s\\S]*?)(?=^:[0-9]{2}[A-Z]?:|$)\n");

    @Override
    public Result parse(InputStream source, String recordSelector, Set<String> fieldSelectors) throws IOException {
        try (var ais = new AsciiReader(source)) {
            var contents = ais.readAllAsString();
            var matcher = MT_PATTERN.matcher(contents);
            if(matcher.matches()) {
                var basicHeader = matcher.group(1);
                var applicationHeader = matcher.group(2);
                var userHeader = matcher.group(3);
                var textBlock = matcher.group(4);
                var trailer  = matcher.group(5);
                return new Result(List.of(), Stream.of());
            } else {
                return new Result(List.of(), Stream.of());
            }
        }
    }
}
