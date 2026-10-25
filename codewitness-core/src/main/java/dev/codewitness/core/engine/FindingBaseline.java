package dev.codewitness.core.engine;

import dev.codewitness.core.model.Finding;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/**
 * Findings that were already present in an earlier run, read from that run's canonical report.
 *
 * <p>A code-scanning consumer shows every result it is given, so without a baseline the second run of a
 * project reports the same eleven findings as the first and the reviewer learns to ignore all twelve. A
 * baseline answers the only question that matters on a pull request: <em>what did this change introduce?</em>
 *
 * <p>It is a filter, not a memory: nothing is deleted and nothing is remembered between runs except the file
 * the caller names. The count of findings a baseline accounted for is stated in the run's limitations, so a
 * baseline cannot be used to quietly hide a growing backlog.
 *
 * <p>The identity of a finding is its {@link Finding#fingerprint()}, which is derived from the analyzer, the
 * rule, the file, the line and the message. A finding that moved to a different line is therefore new, which
 * is the conservative choice: re-reporting a moved finding is noise, but hiding a new one is a miss.
 */
public final class FindingBaseline {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Path source;
    private final Set<String> fingerprints;

    private FindingBaseline(Path source, Set<String> fingerprints) {
        this.source = source;
        this.fingerprints = fingerprints;
    }

    /**
     * Reads the fingerprints of findings from a stored report.
     *
     * @param report path to a previous {@code report.json}
     * @return the baseline
     * @throws IllegalArgumentException when the file cannot be read or is not a CodeWitness JSON report
     */
    public static FindingBaseline load(Path report) {
        Objects.requireNonNull(report, "report");
        String text;
        try {
            text = Files.readString(report, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalArgumentException("cannot read the baseline report " + report + ": " + e.getMessage(), e);
        }
        JsonNode root;
        try {
            root = MAPPER.readTree(text);
        } catch (IOException e) {
            throw new IllegalArgumentException("the baseline " + report + " is not valid JSON: " + e.getMessage(), e);
        }
        JsonNode findings = root.path("findings");
        if (!findings.isArray() && !root.has("runId")) {
            throw new IllegalArgumentException("the baseline " + report + " does not look like a CodeWitness "
                    + "report: it has no findings array and no runId");
        }
        Set<String> fingerprints = new LinkedHashSet<>();
        for (JsonNode finding : findings) {
            String fingerprint = finding.path("fingerprint").asText(null);
            if (fingerprint != null && !fingerprint.isBlank()) {
                fingerprints.add(fingerprint);
            }
        }
        return new FindingBaseline(report, fingerprints);
    }

    /** @return an empty baseline, which treats every finding as new. */
    public static FindingBaseline empty() {
        return new FindingBaseline(null, Set.of());
    }

    /** @return the file the baseline came from, or {@code null} when it is empty. */
    public Path source() {
        return source;
    }

    /** @return how many findings the baseline accounts for. */
    public int size() {
        return fingerprints.size();
    }

    /**
     * @param finding finding to test
     * @return {@code true} when this finding was already present in the baseline
     */
    public boolean contains(Finding finding) {
        return finding != null && fingerprints.contains(finding.fingerprint());
    }
}
