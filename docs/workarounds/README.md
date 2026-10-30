# CodeWitness Workarounds

This directory documents environmental limitations and their workarounds for specific CodeWitness adapters and configurations.

## Available Workarounds

### TypeScript Parameter Properties

**File:** [`typescript-parameter-properties.md`](typescript-parameter-properties.md)

**Issue:** Node.js's experimental `stripTypeScriptTypes` function does not support TypeScript parameter properties (`constructor(private x: T)`), throwing "TypeScript parameter property is not supported in strip-only mode".

**Impact:** TypeScript projects without a pinned TypeScript compiler that use parameter properties will fail CodeWitness build checks.

**Workaround:** Add TypeScript compiler and `tsconfig.json` to the project, which makes CodeWitness use `tsc --noEmit` instead of Node's type stripper.

**Status:** Documented in `docs/status.md` and this guide.

---

## Related Documentation

- **CodeWitness Status:** [`docs/status.md`](../status.md) - Comprehensive status of all language adapters
- **TypeScript Adapter:** [`codewitness-adapter-typescript/`](../../codewitness-adapter-typescript/) - TypeScript adapter implementation
- **Languages Guide:** [`docs/languages.md`](../languages.md) - Language support overview

---

## How to Contribute

If you discover an environmental limitation and workaround, please:

1. Create a new markdown file in this directory
2. Document the issue, impact, and workaround(s)
3. Update the README.md index above
4. Reference the limitation in `docs/status.md` where appropriate
