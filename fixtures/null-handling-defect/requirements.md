# R-001 A null username is rejected without throwing

Registering a null username must be rejected explicitly. The registry must report the rejection as
`REJECTED_NULL` (or otherwise refuse the input) and must never throw a `NullPointerException` at the
caller. Rejecting an input is a normal outcome, not an exceptional one.

# R-002 Non-blank usernames are registered

A non-blank username must be accepted and stored. Storage is case-insensitive: the stored form is
the trimmed, lower-cased username, and after registering exactly one non-blank username the
registry contains exactly one entry.
