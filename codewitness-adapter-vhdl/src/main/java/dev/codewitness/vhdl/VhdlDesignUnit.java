package dev.codewitness.vhdl;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * An entity declared in a VHDL source file.
 *
 * <p>VHDL has no functions and no manifest: a design is a set of design units, and the entity is the
 * interface a testbench instantiates. This record is the small amount of that structure the adapter
 * reads to decide how to analyse, simulate and generate against a tree.
 *
 * <p>A VHDL entity carries the richest port description of any language the adapter drives: each port
 * has a mode ({@code in}, {@code out}, {@code inout}) and a subtype ({@code std_logic},
 * {@code unsigned(31 downto 0)}, {@code integer}, ...). The generator uses both, because VHDL is
 * strongly typed and a scenario's integer boundary values must be converted into the design's own port
 * subtype rather than assigned raw.
 *
 * @param name      the entity identifier, for example {@code ledger}
 * @param file      the file the entity was declared in
 * @param ports     the ports declared in the entity's port clause, in declaration order
 * @param testbench {@code true} when this entity is a testbench rather than design logic
 */
public record VhdlDesignUnit(String name, Path file, List<Port> ports, boolean testbench) {

    public VhdlDesignUnit {
        ports = List.copyOf(ports == null ? List.of() : ports);
    }

    public boolean hasPorts() {
        return !ports.isEmpty();
    }

    /** @return the ports whose mode is {@code in}. */
    public List<Port> inputs() {
        return ports.stream().filter(Port::isInput).toList();
    }

    /** @return the ports whose mode is {@code out} or {@code inout}, which a generated bench reads. */
    public List<Port> outputs() {
        return ports.stream().filter(port -> port.isOutput() || port.isInout()).toList();
    }

    /** @return the port called {@code name}, case-insensitively, or {@code null}. */
    public Port port(String name) {
        if (name == null) {
            return null;
        }
        for (Port port : ports) {
            if (port.name().equalsIgnoreCase(name)) {
                return port;
            }
        }
        return null;
    }

    /**
     * A port declared in an entity's port clause.
     *
     * @param name    the port identifier
     * @param mode    {@code in}, {@code out}, {@code inout}, {@code buffer}, or {@code linkage}
     * @param subtype the subtype as written, for example {@code std_logic} or {@code unsigned(31 downto 0)}
     */
    public record Port(String name, String mode, String subtype) {

        /** {@code unsigned(31 downto 0)}: the subtype is a vector whose bounds may be expressions. */
        private static final Pattern VECTOR = Pattern.compile(
                "^(unsigned|signed|std_logic_vector|bit_vector)\\s*\\(\\s*(.+?)\\s+downto\\s+(.+?)\\s*\\)$",
                Pattern.CASE_INSENSITIVE);
        private static final Pattern VECTOR_ASCENDING = Pattern.compile(
                "^(unsigned|signed|std_logic_vector|bit_vector)\\s*\\(\\s*(.+?)\\s+to\\s+(.+?)\\s*\\)$",
                Pattern.CASE_INSENSITIVE);

        public Port {
            name = name == null ? "" : name.trim();
            mode = mode == null ? "in" : mode.toLowerCase(Locale.ROOT).trim();
            subtype = subtype == null ? "" : subtype.trim();
        }

        public boolean isInput() {
            return "in".equals(mode);
        }

        public boolean isOutput() {
            return "out".equals(mode) || "buffer".equals(mode);
        }

        public boolean isInout() {
            return "inout".equals(mode) || "linkage".equals(mode);
        }

        /** @return the subtype with all whitespace removed and lower case, for comparisons. */
        private String normalizedSubtype() {
            return subtype.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
        }

        public boolean isStdLogic() {
            String type = normalizedSubtype();
            return type.equals("std_logic") || type.equals("std_ulogic") || type.equals("bit");
        }

        public boolean isBoolean() {
            return normalizedSubtype().equals("boolean");
        }

        public boolean isIntegerLike() {
            String type = normalizedSubtype();
            return type.equals("integer") || type.equals("natural") || type.equals("positive")
                    || type.equals("integer_range") || type.startsWith("integer range");
        }

        /** @return the vector family this subtype belongs to, or {@code null} when it is not a vector. */
        public String vectorKind() {
            Matcher matcher = VECTOR.matcher(subtype.trim());
            if (!matcher.matches()) {
                matcher = VECTOR_ASCENDING.matcher(subtype.trim());
            }
            return matcher.matches() ? matcher.group(1).toLowerCase(Locale.ROOT) : null;
        }

        /**
         * @return the number of bits the subtype holds, best effort.
         *
         * <p>A bound written as a literal integer gives the exact width. A bound written as an
         * expression or a constant ({@code WIDTH-1 downto 0}) cannot be evaluated here, so the width
         * falls back to 64 - the same fallback the Verilog generator makes - and a conversion that is
         * too wide is still a legal assignment in VHDL, unlike one that is too narrow.
         */
        public int width() {
            if (isStdLogic()) {
                return 1;
            }
            if (isBoolean()) {
                return 1;
            }
            if (isIntegerLike()) {
                return 32;
            }
            String kind = vectorKind();
            if (kind == null) {
                return 64;
            }
            Matcher matcher = VECTOR.matcher(subtype.trim());
            String left;
            String right;
            if (matcher.matches()) {
                left = matcher.group(2).trim();
                right = matcher.group(3).trim();
            } else {
                matcher = VECTOR_ASCENDING.matcher(subtype.trim());
                if (!matcher.matches()) {
                    return 64;
                }
                left = matcher.group(3).trim();
                right = matcher.group(2).trim();
            }
            Integer high = parseBound(left);
            Integer low = parseBound(right);
            if (high == null || low == null) {
                return 64;
            }
            int bits = high - low + 1;
            return bits <= 0 ? 64 : bits;
        }

        private static Integer parseBound(String text) {
            try {
                return Integer.valueOf(text.trim());
            } catch (NumberFormatException _) {
                return null;
            }
        }

        /**
         * @return the declaration a generated testbench writes for this port's connected signal, which
         *     is the port's own subtype so the instantiation is type correct.
         */
        public String signalDeclaration() {
            return subtype.isBlank() ? "std_logic" : subtype;
        }

        /**
         * @return the VHDL expression that drives this (input) port from an integer boundary value.
         *
         * <p>VHDL is strongly typed, so an integer cannot be assigned to {@code unsigned} or
         * {@code std_logic} directly. This is the conversion the generated testbench writes for each of
         * the port kinds a synthesizable design uses; an unrecognised subtype falls back to the
         * {@code std_logic_vector} conversion, which at worst fails to compile and is reported as an
         * ERROR rather than silently producing a test that never runs.
         *
         * @param integerExpression a VHDL expression of type {@code integer}, such as {@code cw_balance}
         */
        public String driveExpression(String integerExpression) {
            if (isStdLogic()) {
                return "'1' when " + integerExpression + " /= 0 else '0'";
            }
            if (isBoolean()) {
                return integerExpression + " /= 0";
            }
            if (isIntegerLike()) {
                return integerExpression;
            }
            int width = width();
            String kind = vectorKind();
            if ("unsigned".equals(kind)) {
                return "to_unsigned(" + integerExpression + ", " + width + ")";
            }
            if ("signed".equals(kind)) {
                return "to_signed(" + integerExpression + ", " + width + ")";
            }
            if ("bit_vector".equals(kind)) {
                return "bit_vector(to_unsigned(" + integerExpression + ", " + width + "))";
            }
            return "std_logic_vector(to_unsigned(" + integerExpression + ", " + width + "))";
        }
    }
}
