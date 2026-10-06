# R-001 Fee charged above the free limit

A fee of exactly 2.00 is charged when the withdrawal amount is strictly greater than the free limit
of 1,000. An amount of 1,000 or less is free.

# R-002 Zero fees are rendered as free

Rendering a fee of zero must produce the text "free". Rendering a positive fee must produce its plain
decimal text, without an exponent.
