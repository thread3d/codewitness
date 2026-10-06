# R-001 Letter grades follow the documented boundaries

The letter grade for a numeric score is exactly:

- 90 and above: `A`
- 80 to 89: `B`
- 70 to 79: `C`
- 60 to 69: `D`
- below 60: `F`

Every integer score from 0 to 100 inclusive must map to one of those letters, and the boundary
scores 60, 70, 80 and 90 belong to the higher band.

# R-002 The boundaries match the institution's official marking policy

The grade boundaries above must match the institution's official marking policy. That policy is
published outside this repository, is revised each academic year, and is the authority for what the
boundaries are; the code cannot establish conformance with it on its own, and the policy is not
available to any automated check in this repository.
