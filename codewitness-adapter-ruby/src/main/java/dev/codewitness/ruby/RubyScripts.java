package dev.codewitness.ruby;

import dev.codewitness.core.spi.VerificationContext;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * The small Ruby program CodeWitness runs inside a project.
 *
 * <p>Ruby has no separate compilation step, but it does have a well defined first failure: a file the
 * interpreter itself cannot parse. Checking that needs no gem at all, only
 * {@code RubyVM::InstructionSequence.compile_file}, so the check is a helper script written into the run
 * directory rather than a dependency the project would have to install. Writing it into the run
 * directory and not into the project is what makes a run repeatable and what keeps a verifier from
 * leaving files in somebody's working tree.
 *
 * <p>The script speaks a line protocol so that the Java side never has to read prose: one
 * {@code CWDIAG|} line per diagnostic and a final {@code CWSYNTAX|} summary.
 *
 * <p>{@code RubyVM} is part of MRI's standard library and is absent from other implementations. That is
 * not papered over: on such an interpreter the script reports a warning per file and exits zero, because
 * "this interpreter cannot parse-check" is a fact about the checker, and reporting it as a parse error
 * would be a code defect the project does not have.
 */
public final class RubyScripts {

    private RubyScripts() {
    }

    /** Writes the helper scripts into the run's artefact store and returns their paths. */
    public static Scripts write(VerificationContext context) {
        return new Scripts(writeScript(lintScript(context), LINT));
    }

    /**
     * Where {@link #write} puts the syntax checker.
     *
     * <p>Deriving the path without writing the script lets a caller name the command that will be run -
     * for evidence, say - without a side effect on the run directory.
     */
    public static Path lintScript(VerificationContext context) {
        return context.artifacts().artifactsDirectory().resolve("ruby").resolve("ruby-lint.rb");
    }

    private static Path writeScript(Path file, String content) {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot write helper script " + file, e);
        }
        return file;
    }

    /**
     * Checks the paths given as argv with the interpreter's own parser and prints one {@code CWDIAG|}
     * line per diagnostic.
     *
     * <p>The engine reports a failed build as {@code BLOCKED}, so the script must distinguish "the code
     * does not parse" from "the checker could not run": it exits non-zero only for parse errors and
     * prints a summary line the Java side records. A file it cannot read is a warning about the check,
     * not an error about the code. Any directory argument is walked with an explicit stack, so the script
     * works both when the caller passes the files it collected and when it passes a tree, and it skips the
     * same directories the Java side would have skipped.
     *
     * <p>{@code compile_file} reports a syntax error with a message that begins with the file and line,
     * for example {@code /srv/lib/ledger.rb:12: syntax error, unexpected ...}; the line is taken from that
     * prefix and defaults to zero when the interpreter's message does not carry one. The message itself is
     * flattened onto the protocol line, because a diagnostic that spans lines would let a project forge
     * additional {@code CWDIAG|} lines with its own source text.
     */
    static final String LINT = """
            # CodeWitness Ruby syntax check. Diagnostics go to stdout as
            #   CWDIAG|<SEVERITY>|<absolute path>|<line>|<message without newlines or pipes>
            # followed by one CWSYNTAX|files=<n>|errors=<n> summary. Exit status is 1 when at least one
            # file failed to parse, so a non-zero exit means "the project does not parse" and never
            # "the checker could not run".
            excluded = %w[vendor node_modules build dist .git .codewitness .bundle coverage tmp log
                          .rspec_status .yardoc]
            extensions = %w[rb rake gemspec]

            def cw_collect(argument, excluded, extensions)
              files = []
              if File.file?(argument)
                return [File.expand_path(argument)]
              end
              return files unless File.directory?(argument)
              stack = [argument]
              until stack.empty?
                directory = stack.pop
                entries = begin
                  Dir.children(directory)
                rescue SystemCallError
                  nil
                end
                next if entries.nil?
                entries.each do |entry|
                  path = File.join(directory, entry)
                  if File.directory?(path)
                    stack << path unless excluded.include?(entry)
                  elsif File.file?(path)
                    extension = File.extname(path).delete_prefix('.').downcase
                    files << File.expand_path(path) if extensions.include?(extension)
                  end
                end
              end
              files
            end

            def cw_diag(severity, file, line, message)
              message = message.to_s.gsub(/[\\r\\n|]/, ' ')
              puts "CWDIAG|#{severity}|#{file}|#{line}|#{message}"
            end

            files = []
            ARGV.each do |argument|
              next if argument.empty?
              cw_collect(argument, excluded, extensions).each { |file| files << file }
            end
            files = files.uniq.sort

            errors = 0
            files.each do |file|
              begin
                RubyVM::InstructionSequence.compile_file(file)
              rescue SyntaxError => error
                errors += 1
                match = /:([0-9]+):/.match(error.message.to_s)
                cw_diag('ERROR', file, match ? match[1].to_i : 0, error.message)
              rescue Exception => error
                # A file the checker cannot read says nothing about the project's code, so it is a warning
                # and does not by itself fail the build.
                cw_diag('WARNING', file, 0, "the file could not be checked (#{error.class})")
              end
            end
            cw_diag('WARNING', '.', 0, 'no Ruby source file was supplied') if files.empty?
            puts "CWSYNTAX|files=#{files.size}|errors=#{errors}"
            exit(errors > 0 ? 1 : 0)
            """;

    /**
     * Paths of the helper scripts for one run.
     *
     * @param lintScript syntax checker, run once by the build provider
     */
    public record Scripts(Path lintScript) {

        /** @return every script of this run, so that the caller can record them as artefacts. */
        public List<Path> all() {
            return List.of(lintScript);
        }
    }
}
