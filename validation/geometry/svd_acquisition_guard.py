"""Refuse verification-writer/bypass modes before invoking the audited Gradle command.

This validates verification options, not arbitrary Gradle scripts or network behavior. The caller must still use the reviewed task, seeded metadata, scoped environment and bounded acquisition bill.
"""


def validate_command(arguments):
    strict = False
    options = iter(arguments)
    for argument in options:
        if argument in ("-M", "--write-verification-metadata") or argument.startswith("--write-verification-metadata="):
            raise ValueError("Verification writer mode cannot enforce seeded hashes")
        if argument in ("-F", "--dependency-verification"):
            mode = next(options, None)
        elif argument.startswith("--dependency-verification="):
            mode = argument.split("=", 1)[1]
        else:
            continue
        if mode != "strict":
            raise ValueError("Only strict dependency verification is permitted")
        strict = True
    if not strict:
        raise ValueError("Explicit strict dependency verification is required")
