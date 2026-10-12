package dev.codewitness.verilog;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/**
 * A module declaration found in a Verilog source file.
 *
 * <p>Verilog has no functions, no packages and no manifest: a design is a set of modules, and the
 * thing that makes one of them the design under test or a testbench is its port list and what its
 * body does. This record is the small amount of that structure the adapter reads to decide how to
 * compile, simulate and generate against a tree.
 *
 * @param name      the module identifier, for example {@code ledger}
 * @param file      the file the module was declared in
 * @param ports     the ports declared in the module header, in declaration order
 * @param testbench {@code true} when this module is a testbench rather than design logic
 */
public record VerilogModule(String name, Path file, List<Port> ports, boolean testbench) {

    public VerilogModule {
        ports = List.copyOf(ports == null ? List.of() : ports);
    }

    public boolean hasPorts() {
        return !ports.isEmpty();
    }

    /** @return the ports whose direction is {@code input}. */
    public List<Port> inputs() {
        return ports.stream().filter(Port::isInput).toList();
    }

    /** @return the ports whose direction is {@code output}. */
    public List<Port> outputs() {
        return ports.stream().filter(Port::isOutput).toList();
    }

    /**
     * A port declared in a module header.
     *
     * @param name      the port identifier
     * @param direction {@code input}, {@code output}, {@code inout}, or {@code unknown} for a header
     *     that names ports without declaring their direction (the non-ANSI style)
     * @param netType   {@code wire}, {@code reg}, {@code logic}, {@code bit}, or {@code null} when the
     *     header names no net type
     * @param packedRange the packed dimension as written, for example {@code [31:0]}, or {@code null}
     *     when the port is one bit wide
     * @param signed    whether the declaration carries the {@code signed} keyword
     */
    public record Port(String name, String direction, String netType, String packedRange, boolean signed) {

        public Port {
            name = name == null ? "" : name.trim();
            direction = direction == null ? "unknown" : direction.toLowerCase(Locale.ROOT);
            netType = netType == null ? null : netType.toLowerCase(Locale.ROOT);
        }

        public boolean isInput() {
            return "input".equals(direction);
        }

        public boolean isOutput() {
            return "output".equals(direction);
        }

        public boolean isInout() {
            return "inout".equals(direction);
        }

        /**
         * @return the declaration the adapter writes for this port when it generates a testbench:
         *     {@code reg} for an input (a testbench drives inputs procedurally) and {@code wire} for
         *     an output, with the declared range and signedness carried over.
         */
        public String testbenchDeclaration() {
            StringBuilder sb = new StringBuilder(isInput() ? "reg" : "wire");
            if (signed) {
                sb.append(" signed");
            }
            if (packedRange != null && !packedRange.isBlank()) {
                sb.append(' ').append(packedRange);
            }
            return sb.toString();
        }
    }
}
