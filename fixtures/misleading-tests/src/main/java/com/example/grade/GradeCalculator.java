package com.example.grade;

/**
 * Maps a numeric score to a letter grade.
 *
 * <p>This is a CodeWitness fixture. Unlike the other fixtures in this repository, this
 * implementation is deliberately <em>correct</em>: the deliberate defect lives in the accompanying
 * test suite, which is misleading. {@code GradeCalculatorTest} contains a test with no assertion at
 * all and a test whose assertion cannot fail, so a green suite is presented as evidence that the
 * code is correct when it is not evidence of anything.
 *
 * <p>The class is intentionally stateless, which also makes it a clean subject for the scenario in
 * {@code codewitness.yaml}.
 */
public final class GradeCalculator {

    /**
     * Returns the letter grade for {@code score}.
     *
     * @param score score in the inclusive range 0 to 100
     * @return one of {@code A}, {@code B}, {@code C}, {@code D} or {@code F}
     * @throws IllegalArgumentException when {@code score} is outside 0 to 100
     */
    public String letterGrade(int score) {
        if (score < 0 || score > 100) {
            throw new IllegalArgumentException("score must be between 0 and 100 inclusive: " + score);
        }
        // The documented bands. Note that each boundary belongs to the higher band, which is what the
        // requirement states; this implementation is correct on purpose.
        if (score >= 90) {
            return "A";
        }
        if (score >= 80) {
            return "B";
        }
        if (score >= 70) {
            return "C";
        }
        if (score >= 60) {
            return "D";
        }
        return "F";
    }
}
