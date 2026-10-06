package dev.codewitness.core.llm;

/**
 * A labelled section of a prompt.
 *
 * <p>The labels exist for a security reason: project files and source code are untrusted input and
 * may contain text such as "ignore all previous instructions". Every section states whether it is
 * trusted, and untrusted sections are fenced so that a model cannot confuse content with
 * instructions.
 */
public record PromptSection(Kind kind, String title, String content) {

    public enum Kind {
        /** Instructions written by CodeWitness itself. */
        SYSTEM_INSTRUCTIONS(true),
        /** Instructions written by the user running CodeWitness. */
        USER_INSTRUCTIONS(true),
        /** Requirements extracted from the requirements file. Untrusted only in the sense that a
         *  requirements file may itself be model generated; treated as data. */
        REQUIREMENTS(false),
        /** Project source code. Untrusted. */
        SOURCE_CODE(false),
        /** Raw test or tool output. Untrusted. */
        TEST_RESULTS(false),
        /** Verifier output such as static analysis findings or surviving mutants. */
        ANALYSIS_RESULTS(false),
        /** Content previously produced by a language model. Untrusted. */
        LLM_OUTPUT(false);

        private final boolean trusted;

        Kind(boolean trusted) {
            this.trusted = trusted;
        }

        public boolean isTrusted() {
            return trusted;
        }
    }

    public PromptSection {
        if (kind == null) {
            kind = Kind.SOURCE_CODE;
        }
        title = title == null ? kind.name() : title;
        content = content == null ? "" : content;
    }

    public static PromptSection system(String content) {
        return new PromptSection(Kind.SYSTEM_INSTRUCTIONS, "System instructions", content);
    }

    public static PromptSection user(String content) {
        return new PromptSection(Kind.USER_INSTRUCTIONS, "User instructions", content);
    }

    public static PromptSection requirements(String content) {
        return new PromptSection(Kind.REQUIREMENTS, "Requirements", content);
    }

    public static PromptSection source(String content) {
        return new PromptSection(Kind.SOURCE_CODE, "Source code", content);
    }

    public static PromptSection testResults(String content) {
        return new PromptSection(Kind.TEST_RESULTS, "Test results", content);
    }

    public static PromptSection analysis(String content) {
        return new PromptSection(Kind.ANALYSIS_RESULTS, "Analysis results", content);
    }

    public static PromptSection llmOutput(String content) {
        return new PromptSection(Kind.LLM_OUTPUT, "Previous model output", content);
    }
}
