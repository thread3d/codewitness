package com.example.grade;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The project's own tests.
 *
 * <p>This is the fixture's deliberate defect: the suite is misleading rather than wrong. Both tests
 * pass, and both are worthless as evidence.
 *
 * <ul>
 *   <li>{@link #computesBoundaryGrades()} calls the code under test but never asserts anything, so
 *       it passes whatever {@code letterGrade} returns. CodeWitness reports this as
 *       {@code CW-TEST-001}.</li>
 *   <li>{@link #returnsAStringForAnyScore()} asserts something that is true for every possible
 *       implementation of a method returning {@code String}, so it cannot fail either.</li>
 * </ul>
 */
class GradeCalculatorTest {

    /**
     * Calls the boundary scores but asserts nothing: a green test that cannot fail. This is the test
     * CodeWitness flags as {@code CW-TEST-001}.
     */
    @Test
    void computesBoundaryGrades() {
        GradeCalculator calculator = new GradeCalculator();
        calculator.letterGrade(90);
        calculator.letterGrade(89);
        calculator.letterGrade(60);
        calculator.letterGrade(59);
    }

    /**
     * Asserts something trivially true. {@code String.length()} is never negative, so the assertion
     * holds no matter what the method returns, including {@code null} (for which it would throw
     * rather than assert anything meaningful).
     */
    @Test
    void returnsAStringForAnyScore() {
        GradeCalculator calculator = new GradeCalculator();
        assertTrue(calculator.letterGrade(75).length() >= 0);
    }
}
