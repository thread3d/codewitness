package dev.codewitness.languages;

import java.util.List;
import java.util.Optional;

/**
 * Recognises Verilog and SystemVerilog projects, which an adapter now verifies.
 *
 * <p>This detector exists for the classpath that does not carry the Verilog adapter: it then reports
 * Verilog as present and not verified, which is honest when nothing can verify it. With the adapter
 * present it declares as much through {@link #verifiedAsAdapterLanguage()}, so a tree the adapter
 * claimed is not described a second time as unverifiable. Keeping it registered means that removing
 * the adapter from the classpath degrades to a named limitation rather than to silence.
 *
 * <p>Its evidence is narrower than the adapter's claim in one direction only: a file that merely looks
 * like Verilog by its suffix, in a tree whose adapter-side inspection found no module declaration. The
 * detector cannot compile or elaborate anything, so naming the tree is the whole of what it can say.
 */
public final class VerilogLanguageDetector extends DescriptorLanguageDetector {

    public VerilogLanguageDetector() {
        super("verilog", "Verilog", List.of(), List.of(".v", ".sv", ".vh", ".svh"));
    }

    @Override
    public Optional<String> verifiedAsAdapterLanguage() {
        return Optional.of("verilog");
    }
}
