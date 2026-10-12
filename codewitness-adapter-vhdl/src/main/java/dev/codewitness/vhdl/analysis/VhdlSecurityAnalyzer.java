package dev.codewitness.vhdl.analysis;

import dev.codewitness.core.model.Finding;
import dev.codewitness.core.model.LanguageId;
import dev.codewitness.core.model.ProjectInfo;
import dev.codewitness.core.spi.VerificationContext;
import dev.codewitness.core.spi.VerificationTools;

import java.util.List;

/**
 * The security half of the VHDL rule table.
 *
 * <p>The rules live with the rest of the table in {@link VhdlHeuristicAnalyzer} so that one literal
 * aware scan serves both phases; this class exists because the engine selects a static analyzer and a
 * security analyzer as separate providers, and only a type that implements
 * {@link VerificationTools.SecurityAnalyzer} can be registered as the second. It answers with the
 * security rules alone, and it keeps the analyzer id of the table so both phases report the same
 * provenance.
 */
public final class VhdlSecurityAnalyzer implements VerificationTools.SecurityAnalyzer {

    private final VhdlHeuristicAnalyzer delegate = new VhdlHeuristicAnalyzer();

    @Override
    public String id() {
        return VhdlHeuristicAnalyzer.ID;
    }

    @Override
    public boolean supports(ProjectInfo project) {
        return project.language().equals(LanguageId.VHDL);
    }

    @Override
    public List<Finding> analyze(VerificationContext context) {
        return delegate.analyzeSecurity(context);
    }
}
