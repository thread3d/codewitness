package dev.codewitness.cli;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the invariant {@code .github/workflows/ci.yml} states in prose: every action is pinned to a full-length
 * commit SHA, and the trailing comment names the release that SHA came from.
 *
 * <p>A pin is what stops a re-pointed tag or branch from changing what CI runs, and a tag pin is what a hurried
 * "fix the CI" commit writes — {@code uses: actions/checkout@v4} looks tidier than forty hex digits and works
 * until the day the tag moves. Nothing fails when that happens: the workflow keeps running, just not the code
 * that was reviewed. The comment is the second half of the same contract, because
 * {@code .github/dependabot.yml} reads it to decide which release a SHA is; a SHA whose comment disagrees with
 * it misdirects the next update instead of failing it.
 *
 * <p>The third assertion is the one that would have caught a defect this repository nearly shipped: the same
 * action is pinned in more than one workflow file, and bumping one file and not the others leaves two versions
 * of one action running in the same pipeline, with the stale one in whichever job nobody was looking at. It is
 * keyed on the <em>repository</em> rather than on the full reference, because one repository can supply several
 * steps — {@code github/codeql-action/init} and {@code github/codeql-action/analyze} are two actions from one
 * repository, and a bump applied to one and not the other is a pair that was never tested together. Keyed on the
 * full reference they looked unrelated, which is how a half-applied CodeQL bump would have passed this test.
 */
class CiActionPinsTest {

    /** A {@code uses:} line, with or without the list dash, capturing the ref and whatever follows it. */
    private static final Pattern USE = Pattern.compile("^\\s*(?:-\\s*)?uses:\\s*([^\\s#]+)(.*)$");

    /** A ref is {@code owner/repo[/path]@<40 hex digits>} — a full commit SHA, not a tag, branch or short SHA. */
    private static final Pattern PINNED = Pattern.compile("@([0-9a-f]{40})$");

    @Test
    @DisplayName("every workflow action is pinned to a full commit SHA and names the release it came from")
    void everyActionIsPinnedToAShaWithAVersionComment() throws IOException {
        List<String> problems = new ArrayList<>();
        Map<String, Pin> pins = new LinkedHashMap<>();

        for (Path workflow : workflows()) {
            List<String> lines = Files.readAllLines(workflow, StandardCharsets.UTF_8);
            for (int i = 0; i < lines.size(); i++) {
                Matcher use = USE.matcher(lines.get(i));
                if (!use.matches()) {
                    continue;
                }
                String ref = use.group(1);
                String trailing = use.group(2).trim();
                String where = workflow.getFileName() + ":" + (i + 1);

                Matcher pinned = PINNED.matcher(ref);
                if (!pinned.find()) {
                    problems.add(where + ": " + ref + " is not pinned to a 40-character commit SHA. A tag or "
                            + "branch can be re-pointed at different code after review, which is the whole "
                            + "reason these lines are SHAs.");
                    continue;
                }

                if (!trailing.startsWith("#") || trailing.substring(1).isBlank()) {
                    problems.add(where + ": " + ref + " carries no `# <release>` comment. Dependabot reads that "
                            + "comment to know which release the SHA is, and a pin without one stops being "
                            + "tracked.");
                }

                String action = ref.substring(0, ref.indexOf('@'));
                String repository = repositoryOf(action);
                Pin seen = pins.putIfAbsent(repository, new Pin(action, ref, where));
                if (seen != null && !shaOf(seen.ref()).equals(shaOf(ref))) {
                    problems.add(where + ": " + action + " is pinned to " + shaOf(ref) + ", but "
                            + seen.action() + " at " + seen.where() + " pins the same repository to "
                            + shaOf(seen.ref()) + ". One pipeline, two versions of one repository: whichever "
                            + "pin was not bumped is running code nobody reviewed — and when one repository "
                            + "supplies several steps, a half-applied bump is a pair that was never tested "
                            + "together.");
                }
            }
        }

        assertTrue(!pins.isEmpty(), "no `uses:` lines were found in .github/workflows — the walk-up probably "
                + "located a workspace copy without them, which would make this test vacuous");
        assertTrue(problems.isEmpty(), "the workflow action pins break the supply-chain contract:\n  "
                + String.join("\n  ", problems));
    }

    /** The {@code owner/repo} an action reference names, ignoring any subdirectory after it. */
    private static String repositoryOf(String action) {
        String[] segments = action.split("/");
        return segments.length >= 2 ? segments[0] + "/" + segments[1] : action;
    }

    private static String shaOf(String ref) {
        return ref.substring(ref.indexOf('@') + 1);
    }

    /** One pin, kept with the place it was seen so that a disagreement can name both sides. */
    private record Pin(String action, String ref, String where) {
    }

    /** Every workflow file under the reactor root's {@code .github/workflows}. */
    private static List<Path> workflows() throws IOException {
        Path directory = ReactorRoot.find().resolve(".github").resolve("workflows");
        Set<Path> found = new LinkedHashSet<>();
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(directory, "*.yml")) {
            for (Path entry : entries) {
                if (Files.isRegularFile(entry)) {
                    found.add(entry);
                }
            }
        }
        found.addAll(yamlWorkflows(directory));
        List<Path> ordered = new ArrayList<>(found);
        ordered.sort(Path::compareTo);
        return ordered;
    }

    /** {@code .yml} is the convention here, but {@code .yaml} is equally valid and must not slip past. */
    private static List<Path> yamlWorkflows(Path directory) throws IOException {
        List<Path> found = new ArrayList<>();
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(directory, "*.yaml")) {
            for (Path entry : entries) {
                if (Files.isRegularFile(entry)) {
                    found.add(entry);
                }
            }
        }
        return found;
    }
}
