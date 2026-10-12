package dev.codewitness.pascal;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * One Pascal compilation unit as the adapter read it: a program, a library or a unit.
 *
 * <p>The distinction matters because only a program (or a library) is something the compiler can link
 * into an executable. A tree of units alone still has a well defined first failure - the compiler
 * rejects a unit it cannot compile - so the adapter compiles each unit as a check, but it never tries to
 * <em>run</em> one: a unit has no entry point.
 *
 * <p>{@code dependencies} are the identifiers named in the source's {@code uses} clauses, which is how
 * the adapter finds a test runner without compiling anything: a program that uses a test unit's own
 * suite is a runner even when its file name does not say so.
 *
 * @param name         the identifier the source declares after {@code program}, {@code unit} or
 *     {@code library}, or the file's stem when it declares none
 * @param file         the source file
 * @param dependencies identifiers named in every {@code uses} clause, in source order and without
 *     duplicates
 * @param program      {@code true} when the source is a {@code program} or a {@code library}, which is
 *     to say something the compiler links into an executable
 * @param test         {@code true} when the source is part of the project's test suite rather than of
 *     the code under test
 */
public record PascalModule(String name, Path file, List<String> dependencies, boolean program, boolean test) {

    public PascalModule {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(file, "file");
        file = file.toAbsolutePath().normalize();
        dependencies = List.copyOf(dependencies == null ? List.of() : dependencies);
    }

    /** @return {@code true} when this source names {@code module} in one of its {@code uses} clauses. */
    public boolean uses(String module) {
        for (String dependency : dependencies) {
            if (dependency.equalsIgnoreCase(module)) {
                return true;
            }
        }
        return false;
    }

    /** @return the file's stem, for example {@code ledger} for {@code ledger.pas}. */
    public String stem() {
        String fileName = file.getFileName().toString();
        int dot = fileName.lastIndexOf('.');
        return dot <= 0 ? fileName : fileName.substring(0, dot);
    }
}
