package dev.codewitness.python;

import dev.codewitness.core.spi.VerificationContext;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * The small Python programs CodeWitness runs inside a project.
 *
 * <p>They exist so that Python verification does not depend on any third-party package: syntax
 * checking, standard-library test execution with a JUnit XML report, and AST mutation are all done
 * by the interpreter the project already has. The scripts are written into the run directory, never
 * into the project, and are recorded as artefacts so a run can be replayed.
 *
 * <p>Each script speaks a line protocol that the Java side parses, so the engine never has to scrape
 * human-readable console output.
 */
public final class PythonScripts {

    private PythonScripts() {
    }

    /** Writes the scripts into the run's artefact store and returns their paths. */
    public static Scripts write(VerificationContext context) {
        Path directory = context.artifacts().artifactsDirectory().resolve("python");
        return new Scripts(
                writeScript(directory, "cw_build_check.py", BUILD_CHECK),
                writeScript(unittestRunnerPath(context), UNITTEST_RUNNER),
                writeScript(directory, "cw_mutation.py", MUTATION));
    }

    /**
     * Where {@link #write} puts the standard-library unittest runner.
     *
     * <p>Deriving the path without writing the script lets a caller name the command that will be run
     * - for evidence, say - without side effects on the run directory.
     */
    public static Path unittestRunnerPath(VerificationContext context) {
        return context.artifacts().artifactsDirectory().resolve("python").resolve("cw_unittest_runner.py");
    }

    private static Path writeScript(Path directory, String fileName, String content) {
        return writeScript(directory.resolve(fileName), content);
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
     * Syntax-checks every Python file and prints one {@code CWDIAG|} line per compile error.
     *
     * <p>The engine reports a failed build as {@code BLOCKED}, so this script must distinguish "the
     * code does not compile" from "the checker could not run": it exits non-zero only for compile
     * errors and prints a summary line the Java side records.
     */
    static final String BUILD_CHECK = """
            import os
            import sys

            SKIP = {"__pycache__", ".venv", "venv", ".tox", ".nox", ".git", "node_modules", ".eggs",
                    "build", "dist", ".codewitness", ".pytest_cache", ".mypy_cache", ".ruff_cache", ".hypothesis"}


            def collect(roots):
                files = []
                for root in roots:
                    if os.path.isfile(root):
                        files.append(root)
                        continue
                    for dirpath, dirnames, filenames in os.walk(root):
                        dirnames[:] = [d for d in dirnames if d not in SKIP and not d.endswith(".egg-info")]
                        for name in filenames:
                            if name.endswith(".py"):
                                files.append(os.path.join(dirpath, name))
                return sorted(set(files))


            def check(path):
                with open(path, "rb") as handle:
                    source = handle.read()
                compile(source, path, "exec", dont_inherit=True)


            def main(argv):
                roots = [argument for argument in argv[1:] if argument]
                if not roots:
                    print("CWDIAG|FATAL|.|0|0|no source root was supplied")
                    return 2
                checked = 0
                errors = 0
                for path in collect(roots):
                    checked += 1
                    try:
                        check(path)
                    except SyntaxError as error:
                        errors += 1
                        line = error.lineno or 0
                        column = error.offset or 0
                        message = (error.msg or "syntax error").replace("|", "/").replace("\\n", " ")
                        print("CWDIAG|ERROR|%s|%d|%d|%s" % (path, line, column, message))
                    except (ValueError, TypeError, MemoryError) as error:
                        errors += 1
                        print("CWDIAG|ERROR|%s|0|0|%s" % (path, str(error).replace("|", "/")))
                print("CWSYNTAX|checked=%d|errors=%d" % (checked, errors))
                return 1 if errors else 0


            if __name__ == "__main__":
                sys.exit(main(sys.argv))
            """;

    /**
     * Runs standard-library tests and writes a JUnit XML report.
     *
     * <p>pytest writes the same format through its built-in {@code --junitxml} option, so one parser
     * covers both runners. The class name is derived from the file path relative to the root the run
     * was told about, which is what makes requirement-to-test mapping work without importing the
     * project's package structure.
     */
    static final String UNITTEST_RUNNER = """
            import argparse
            import importlib.util
            import os
            import sys
            import time
            import unittest
            import xml.etree.ElementTree as ET


            def flatten(suite):
                for item in suite:
                    if isinstance(item, unittest.TestSuite):
                        for sub in flatten(item):
                            yield sub
                    else:
                        yield item


            class TimingResult(unittest.TestResult):
                def __init__(self):
                    super().__init__()
                    self.started = {}
                    self.durations = {}

                def startTest(self, test):
                    self.started[test.id()] = time.perf_counter()
                    super().startTest(test)

                def stopTest(self, test):
                    start = self.started.pop(test.id(), None)
                    if start is not None:
                        self.durations[test.id()] = time.perf_counter() - start
                    super().stopTest(test)


            def classname_for(test, root):
                module = sys.modules.get(test.__class__.__module__)
                path = getattr(module, "__file__", None)
                if not path:
                    return test.__class__.__module__
                try:
                    relative = os.path.relpath(path, root)
                except ValueError:
                    return test.__class__.__module__
                if relative.startswith(".."):
                    return test.__class__.__module__
                if relative.endswith(".py"):
                    relative = relative[:-3]
                return relative.replace(os.sep, ".").replace("/", ".")


            def outcome_of(result, test):
                for failed, trace in result.failures:
                    if failed.id() == test.id():
                        return "failure", trace
                for failed, trace in result.errors:
                    if failed.id() == test.id():
                        return "error", trace
                for skipped, reason in result.skipped:
                    if skipped.id() == test.id():
                        return "skipped", reason or "skipped"
                return "passed", ""


            def last_line(text):
                lines = [line for line in (text or "").strip().splitlines() if line.strip()]
                return lines[-1].strip() if lines else ""


            def write_xml(path, cases, result, root):
                root_node = ET.Element("testsuites")
                suite_node = ET.SubElement(root_node, "testsuite", {
                    "name": "codewitness",
                    "tests": str(len(cases)),
                    "failures": str(len(result.failures)),
                    "errors": str(len(result.errors)),
                    "skipped": str(len(result.skipped)),
                    "time": "%.6f" % sum(result.durations.values()),
                })
                for test in cases:
                    outcome, text = outcome_of(result, test)
                    case = ET.SubElement(suite_node, "testcase", {
                        "classname": classname_for(test, root),
                        "name": getattr(test, "_testMethodName", "runTest"),
                        "time": "%.6f" % result.durations.get(test.id(), 0.0),
                    })
                    if outcome in ("failure", "error"):
                        child = ET.SubElement(case, outcome, {
                            "type": last_line(text).split(":", 1)[0].strip() or outcome,
                            "message": last_line(text),
                        })
                        child.text = text
                    elif outcome == "skipped":
                        ET.SubElement(case, "skipped", {"message": last_line(text)})
                ET.ElementTree(root_node).write(path, encoding="utf-8", xml_declaration=True)


            def discover_one(start, pattern, root):
                # A test directory without __init__.py is not importable relative to the project root,
                # so discovery is retried with the directory itself as its own top level. A fresh
                # loader is used each time: a TestLoader remembers the top level directory from its
                # first call and would otherwise fail the retry with the same error.
                for top in (root, start):
                    try:
                        return unittest.TestLoader().discover(start, pattern=pattern, top_level_dir=top)
                    except ImportError:
                        continue
                return unittest.TestSuite()


            def load_file(path, root):
                directory = os.path.dirname(os.path.abspath(path))
                stem = os.path.splitext(os.path.basename(path))[0]
                if directory not in sys.path:
                    sys.path.insert(0, directory)
                spec = importlib.util.spec_from_file_location(stem, path)
                module = importlib.util.module_from_spec(spec)
                sys.modules[stem] = module
                spec.loader.exec_module(module)
                return unittest.TestLoader().loadTestsFromModule(module)


            def main():
                parser = argparse.ArgumentParser()
                parser.add_argument("--xml", required=True)
                parser.add_argument("--root", required=True)
                parser.add_argument("--pattern", default="test*.py")
                parser.add_argument("--start", action="append", default=[])
                parser.add_argument("--file", action="append", default=[])
                args = parser.parse_args()
                suite = unittest.TestSuite()
                if args.file:
                    # Explicit files are used when a caller must run exactly the tests it generated,
                    # rather than everything that happens to be discoverable in a directory.
                    for path in args.file:
                        suite.addTests(load_file(path, args.root))
                else:
                    starts = [start for start in args.start if start] or [args.root]
                    for start in starts:
                        suite.addTests(discover_one(start, args.pattern, args.root))
                # Running a suite releases its test references, so the cases must be captured first;
                # otherwise the report would be written from an empty suite.
                cases = list(flatten(suite))
                result = TimingResult()
                suite.run(result)
                write_xml(args.xml, cases, result, args.root)
                print("CODEWITNESS_TESTS|run=%d|failures=%d|errors=%d|skipped=%d" % (
                    len(cases), len(result.failures), len(result.errors), len(result.skipped)))
                return 1 if (result.failures or result.errors) else 0


            if __name__ == "__main__":
                sys.exit(main())
            """;

    /**
     * Applies one-source-line AST mutations and re-runs the test suite for each mutant.
     *
     * <p>Mutants that the suite cannot tell from the original are the most direct evidence that the
     * tests do not constrain the behaviour they appear to constrain. The script is deliberately
     * bounded: a fixed operator set, a mutant cap and a wall-clock budget, all supplied by the Java
     * side. It restores every file it touches in a {@code finally} block, so a crash cannot leave a
     * mutated source tree behind.
     */
    static final String MUTATION = """
            import argparse
            import ast
            import os
            import shutil
            import subprocess
            import sys
            import time


            class Collector(ast.NodeVisitor):
                def __init__(self):
                    self.sites = []

                def visit_BinOp(self, node):
                    self.generic_visit(node)
                    if isinstance(node.op, (ast.Add, ast.Sub, ast.Mult, ast.Div, ast.Mod)):
                        self.sites.append(("arithmetic", node.lineno, type(node.op).__name__))
                    return node

                def visit_Compare(self, node):
                    self.generic_visit(node)
                    for op in node.ops:
                        if isinstance(op, (ast.Lt, ast.LtE, ast.Gt, ast.GtE, ast.Eq, ast.NotEq)):
                            self.sites.append(("boundary", node.lineno, type(op).__name__))
                    return node

                def visit_BoolOp(self, node):
                    self.generic_visit(node)
                    if isinstance(node.op, (ast.And, ast.Or)):
                        self.sites.append(("logical", node.lineno, type(node.op).__name__))
                    return node

                def visit_Constant(self, node):
                    if isinstance(node.value, bool):
                        self.sites.append(("boolean", node.lineno, str(node.value)))
                    return node


            class Mutator(ast.NodeTransformer):
                def __init__(self, target):
                    self.target = target
                    self.index = 0

                def _hit(self):
                    current = self.index
                    self.index += 1
                    return current == self.target

                def visit_BinOp(self, node):
                    self.generic_visit(node)
                    if isinstance(node.op, (ast.Add, ast.Sub, ast.Mult, ast.Div, ast.Mod)) and self._hit():
                        mapping = {ast.Add: ast.Sub, ast.Sub: ast.Add, ast.Mult: ast.Div,
                                   ast.Div: ast.Mult, ast.Mod: ast.Mult}
                        node.op = mapping[type(node.op)]()
                    return node

                def visit_Compare(self, node):
                    self.generic_visit(node)
                    mapping = {ast.Lt: ast.LtE, ast.LtE: ast.Lt, ast.Gt: ast.GtE, ast.GtE: ast.Gt,
                               ast.Eq: ast.NotEq, ast.NotEq: ast.Eq}
                    for position, op in enumerate(node.ops):
                        if isinstance(op, (ast.Lt, ast.LtE, ast.Gt, ast.GtE, ast.Eq, ast.NotEq)):
                            if self._hit():
                                node.ops[position] = mapping[type(op)]()
                                break
                    return node

                def visit_BoolOp(self, node):
                    self.generic_visit(node)
                    if isinstance(node.op, (ast.And, ast.Or)) and self._hit():
                        node.op = ast.Or() if isinstance(node.op, ast.And) else ast.And()
                    return node

                def visit_Constant(self, node):
                    if isinstance(node.value, bool) and self._hit():
                        node.value = not node.value
                    return node


            def run_tests(root, test_dirs, timeout):
                for tests in test_dirs:
                    if not os.path.isdir(tests):
                        continue
                    command = [sys.executable, "-m", "unittest", "discover",
                               "-s", tests, "-p", "test*.py"]
                    try:
                        completed = subprocess.run(command, cwd=root, stdout=subprocess.DEVNULL,
                                                   stderr=subprocess.DEVNULL, timeout=timeout)
                    except subprocess.TimeoutExpired:
                        return "TIMEOUT"
                    if completed.returncode != 0:
                        return "KILLED"
                return "SURVIVED"


            def clear_cache(path):
                cache = os.path.join(os.path.dirname(path), "__pycache__")
                if os.path.isdir(cache):
                    shutil.rmtree(cache, ignore_errors=True)


            def main():
                parser = argparse.ArgumentParser()
                parser.add_argument("--root", required=True)
                parser.add_argument("--file", action="append", default=[])
                parser.add_argument("--test", action="append", default=[])
                parser.add_argument("--max", type=int, default=24)
                parser.add_argument("--max-seconds", type=float, default=120.0)
                parser.add_argument("--per-test-timeout", type=float, default=60.0)
                args = parser.parse_args()

                if not hasattr(ast, "unparse"):
                    print("CMSUMMARY|generated=0|killed=0|survived=0|timedOut=0|note=python-too-old-for-ast-unparse")
                    return 0

                started = time.time()
                generated = killed = survived = timed_out = 0
                for path in args.file:
                    if generated >= args.max or time.time() - started > args.max_seconds:
                        break
                    try:
                        with open(path, "r", encoding="utf-8") as handle:
                            original = handle.read()
                        tree = ast.parse(original, filename=path)
                    except (OSError, SyntaxError, ValueError):
                        continue
                    collector = Collector()
                    collector.visit(tree)
                    if not collector.sites:
                        continue
                    for index, (mutator, line, detail) in enumerate(collector.sites):
                        if generated >= args.max or time.time() - started > args.max_seconds:
                            break
                        try:
                            mutated_tree = Mutator(index).visit(ast.parse(original, filename=path))
                            mutated = ast.unparse(mutated_tree)
                        except Exception:
                            continue
                        generated += 1
                        relative = os.path.relpath(path, args.root)
                        try:
                            with open(path, "w", encoding="utf-8") as handle:
                                handle.write(mutated)
                            clear_cache(path)
                            status = run_tests(args.root, args.test, args.per_test_timeout)
                        finally:
                            with open(path, "w", encoding="utf-8") as handle:
                                handle.write(original)
                            clear_cache(path)
                        description = "%s %s" % (mutator, detail)
                        if status == "KILLED":
                            killed += 1
                        elif status == "TIMEOUT":
                            timed_out += 1
                        else:
                            survived += 1
                        print("CWMUTANT|%s|%s|%d|%s|%s" % (status, relative, line, mutator, detail))
                print("CMSUMMARY|generated=%d|killed=%d|survived=%d|timedOut=%d" % (
                    generated, killed, survived, timed_out))
                return 0


            if __name__ == "__main__":
                sys.exit(main())
            """;

    /**
     * Paths of the helper scripts for one run.
     *
     * @param buildCheck     syntax checker
     * @param unittestRunner standard-library test runner
     * @param mutation       AST mutation tester
     */
    public record Scripts(Path buildCheck, Path unittestRunner, Path mutation) {

        public List<Path> all() {
            return List.of(buildCheck, unittestRunner, mutation);
        }
    }
}
