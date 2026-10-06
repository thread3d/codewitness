package dev.codewitness.cli;

import picocli.CommandLine.Command;

import java.util.concurrent.Callable;

/** {@code codewitness version} - prints the version, the runtime and the build information. */
@Command(name = "version", description = "Print version information.")
public final class VersionCommand implements Callable<Integer> {

    @Override
    public Integer call() {
        System.out.println("codewitness " + dev.codewitness.core.engine.VerificationEngine.VERSION);
        System.out.println("java        " + System.getProperty("java.version")
                + " (" + System.getProperty("java.vendor") + ")");
        System.out.println("os          " + System.getProperty("os.name") + " " + System.getProperty("os.version")
                + " (" + System.getProperty("os.arch") + ")");
        System.out.println();
        System.out.println("CodeWitness is an evidence engine: it reports what it observed and refuses to");
        System.out.println("mark a requirement verified when the evidence does not support it.");
        return 0;
    }
}
