"""The Python half of the ledger. R-002 lives here."""


def total(amounts):
    """R-002: the running total of a list of amounts."""
    running = 0
    for amount in amounts:
        running += amount
    return running
