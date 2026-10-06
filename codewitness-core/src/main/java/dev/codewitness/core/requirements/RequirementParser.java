package dev.codewitness.core.requirements;

import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses requirements from Markdown or YAML.
 *
 * <p>Two things matter here. First, requirement ids must be <em>stable</em>: the same file must
 * always yield the same ids, because evidence from one run is compared with evidence from another.
 * Second, the natural language text is kept verbatim as the authoritative statement; anything
 * structured that CodeWitness derives from it is recorded as an interpretation.
 *
 * <p>Supported Markdown shape:
 * <pre>
 * # R-001
 * A customer may withdraw at most €1,000 during any rolling 24-hour period.
 * </pre>
 * or {@code ## R-001 Rolling withdrawal limit} followed by free text.
 */
public final class RequirementParser {

    private static final Pattern HEADING = Pattern.compile("^(#{1,6})\\s+(.*)$");
    private static final Pattern ID = Pattern.compile("\\b([A-Z][A-Z0-9]{0,7}-\\d{1,4})\\b");

    private RequirementParser() {
    }

    /** Parses a requirements file, choosing the format from its extension. */
    public static RequirementSet parseFile(Path file) {
        try {
            String content = Files.readString(file, StandardCharsets.UTF_8);
            String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
            if (name.endsWith(".yaml") || name.endsWith(".yml")) {
                return parseYaml(content, file.toString());
            }
            return parseMarkdown(content, file.toString());
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read requirements file " + file, e);
        }
    }

    /** Parses Markdown requirements. */
    public static RequirementSet parseMarkdown(String content, String sourceName) {
        List<Requirement> requirements = new ArrayList<>();
        String currentId = null;
        String currentTitle = null;
        StringBuilder body = new StringBuilder();
        int currentLine = 0;
        int lineNumber = 0;

        for (String line : content.split("\\R", -1)) {
            lineNumber++;
            Matcher heading = HEADING.matcher(line);
            if (heading.matches()) {
                String headingText = heading.group(2).trim();
                Matcher idMatcher = ID.matcher(headingText);
                if (idMatcher.find()) {
                    if (currentId != null) {
                        requirements.add(build(currentId, currentTitle, body.toString(), sourceName, currentLine));
                    }
                    currentId = idMatcher.group(1);
                    String remainder = headingText.substring(idMatcher.end()).replaceFirst("^[\\s:.-]+", "").trim();
                    currentTitle = remainder.isEmpty() ? null : remainder;
                    body = new StringBuilder();
                    currentLine = lineNumber;
                    continue;
                }
            }
            if (currentId != null) {
                body.append(line).append('\n');
            }
        }
        if (currentId != null) {
            requirements.add(build(currentId, currentTitle, body.toString(), sourceName, currentLine));
        }

        if (requirements.isEmpty() && !content.isBlank()) {
            // No identifiers at all: assign stable positional ids so the run still has something to
            // reason about, and say so in the report through the requirement attributes.
            List<String> paragraphs = splitParagraphs(content);
            int index = 1;
            for (String paragraph : paragraphs) {
                String id = String.format(Locale.ROOT, "R-%03d", index++);
                Requirement requirement = build(id, null, paragraph, sourceName, 0);
                requirements.add(requirement);
            }
            return new RequirementSet(withGeneratedIdAttribute(requirements));
        }
        return new RequirementSet(requirements);
    }

    @SuppressWarnings("unchecked")
    public static RequirementSet parseYaml(String content, String sourceName) {
        Object root = new Yaml().load(content == null ? "" : content);
        List<Requirement> requirements = new ArrayList<>();
        List<Map<String, Object>> entries = new ArrayList<>();
        if (root instanceof Map<?, ?> map) {
            Object value = map.get("requirements");
            if (value instanceof List<?> list) {
                for (Object element : list) {
                    if (element instanceof Map<?, ?> entry) {
                        entries.add((Map<String, Object>) entry);
                    }
                }
            }
        } else if (root instanceof List<?> list) {
            for (Object element : list) {
                if (element instanceof Map<?, ?> entry) {
                    entries.add((Map<String, Object>) entry);
                }
            }
        }

        int index = 1;
        for (Map<String, Object> entry : entries) {
            String id = string(entry, "id");
            if (id == null || id.isBlank()) {
                id = String.format(Locale.ROOT, "R-%03d", index);
            }
            index++;
            String title = string(entry, "title");
            String description = string(entry, "description");
            if (description == null) {
                description = string(entry, "text");
            }
            if (description == null) {
                description = title == null ? "" : title;
            }
            Requirement.Builder builder = Requirement.builder(id, description.trim())
                    .title(title)
                    .source(sourceName, -1);
            Object properties = entry.get("properties");
            if (properties instanceof List<?> propertyList) {
                for (Object property : propertyList) {
                    if (property instanceof Map<?, ?> propertyMap) {
                        builder.property(toProperty((Map<String, Object>) propertyMap));
                    } else if (property instanceof String type) {
                        builder.property(RequirementProperty.of(type.trim().toLowerCase(Locale.ROOT), Map.of()));
                    }
                }
            }
            Object paths = entry.get("paths");
            if (paths instanceof List<?> pathList) {
                builder.attribute("paths", join(pathList));
            }
            Object tests = entry.get("tests");
            if (tests instanceof List<?> testList) {
                builder.attribute("tests", join(testList));
            }
            String entrypoints = string(entry, "entrypoints");
            if (entrypoints != null) {
                builder.attribute("entrypoints", entrypoints);
            }
            Object manual = entry.get("manual");
            if (manual instanceof Boolean manualFlag && manualFlag) {
                builder.property(RequirementProperty.of("manual", Map.of()));
            }
            requirements.add(builder.build());
        }
        return new RequirementSet(requirements);
    }

    private static RequirementProperty toProperty(Map<String, Object> map) {
        String type = string(map, "type");
        if (type == null) {
            type = "generic";
        }
        Map<String, Object> values = new LinkedHashMap<>(map);
        values.remove("type");
        return new RequirementProperty(type.trim().toLowerCase(Locale.ROOT), values, null);
    }

    private static String join(List<?> values) {
        List<String> strings = new ArrayList<>();
        for (Object value : values) {
            if (value != null) {
                strings.add(String.valueOf(value));
            }
        }
        return String.join(",", strings);
    }

    private static List<Requirement> withGeneratedIdAttribute(List<Requirement> requirements) {
        List<Requirement> result = new ArrayList<>();
        for (Requirement requirement : requirements) {
            result.add(Requirement.builder(requirement.id(), requirement.description())
                    .title(requirement.title())
                    .source(requirement.sourceFile(), requirement.sourceLine())
                    .attribute("id_source", "assigned by CodeWitness from document order")
                    .build());
        }
        return result;
    }

    private static Requirement build(String id, String title, String body, String source, int line) {
        String text = body.strip();
        Requirement requirement = Requirement.builder(id, text)
                .title(title)
                .source(source, line)
                .build();
        return HeuristicPropertyExtractor.enrich(requirement);
    }

    /**
     * Splits a document that carries no requirement identifiers into candidate requirements.
     *
     * <p>Headings are dropped rather than treated as requirements: a file of headings with no text
     * under them states nothing, and inventing a requirement from it would put a claim in the report
     * that nobody made. A document that yields nothing here produces an empty requirement set.
     */
    private static List<String> splitParagraphs(String content) {
        List<String> paragraphs = new ArrayList<>();
        for (String block : content.split("\\R\\s*\\R")) {
            StringBuilder text = new StringBuilder();
            for (String line : block.split("\\R")) {
                if (!HEADING.matcher(line.strip()).matches()) {
                    text.append(line).append('\n');
                }
            }
            String stripped = text.toString().strip();
            if (!stripped.isEmpty()) {
                paragraphs.add(stripped);
            }
        }
        return paragraphs;
    }

    private static String string(Map<String, Object> map, String key) {
        Object value = map.get(key);
        return value == null ? null : String.valueOf(value);
    }
}
