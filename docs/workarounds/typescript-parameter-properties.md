# TypeScript Parameter Properties: Workarounds for CodeWitness Sandbox

## The Problem

CodeWitness's TypeScript adapter uses Node.js's experimental `stripTypeScriptTypes` function for syntax checking when a project doesn't pin its own TypeScript compiler. This function fails with the error:

```
TypeError: TypeScript parameter property is not supported in strip-only mode
```

**This is a sandbox/environment limitation, not a CodeWitness defect.** Parameter properties are valid TypeScript:

```typescript
// This is valid TypeScript but NOT supported by Node's strip-only mode
class UserService {
  constructor(private db: Database, public cache: Cache) {}
}
```

## Workaround Options

### Option 1: Add TypeScript Compiler to Project (RECOMMENDED)

**Effect:** CodeWitness uses `tsc --noEmit` instead of Node's type stripper

**What to change in the project:**

1. Add TypeScript as a dev dependency:
   ```json
   {
     "devDependencies": {
       "typescript": "^5.5.0"
     }
   }
   ```

2. Add a `tsconfig.json`:
   ```json
   {
     "compilerOptions": {
       "target": "ES2022",
       "module": "ESNext",
       "moduleResolution": "bundler",
       "strict": true,
       "noEmit": true,
       "skipLibCheck": true
     },
     "include": ["src/**/*", "tests/**/*"]
   }
   ```

**Tradeoffs:**
- ✅ Full type checking including parameter properties
- ✅ More accurate build results
- ✅ Projects pin the toolchain they rely on
- ⚠️ Requires adding TypeScript to projects (which most TypeScript projects already have)

**CodeWitness behavior:** According to `TypeScriptBuildProvider.java` (lines 58-83), when `tsc` is found AND `tsconfig.json` exists, CodeWitness runs `tsc --noEmit --pretty false` instead of the Node-based syntax check.

**Evidence:** The TypeScript adapter documentation in `languages.md` (lines 145-146) states:
> "TypeScript support needs only Node 22.6 or later: a project with no `node_modules` is syntax-checked, tested, analysed and mutated by the runtime it already has"

Adding a pinned TypeScript compiler makes CodeWitness use the proper compiler.

---

### Option 2: Refactor Parameter Properties (Alternative)

**Effect:** CodeWitness can parse the code using Node's type stripper

**What to change in the project:**

Convert parameter properties to regular constructor assignments:

```typescript
// Before (parameter property - NOT supported)
class UserService {
  constructor(private db: Database, public cache: Cache) {}
}

// After (regular constructor - SUPPORTED)
class UserService {
  private db: Database;
  public cache: Cache;
  
  constructor(db: Database, cache: Cache) {
    this.db = db;
    this.cache = cache;
  }
}
```

**Tradeoffs:**
- ✅ Works without adding TypeScript dependency
- ⚠️ More verbose code
- ⚠️ Parameter properties are idiomatic TypeScript; refactoring changes code style
- ⚠️ Not viable if the codebase already uses parameter properties extensively

---

### Option 3: Document as Known Limitation (For CodeWitness)

**Effect:** Users are explicitly informed that parameter properties require TypeScript compiler

**What to change in CodeWitness:**

Update `docs/status.md` (lines 176-178) to be more explicit:

```markdown
- TypeScript is executed by Node's type stripper, so only erasable syntax is supported: `enum`,
  namespace` with a runtime value, and **parameter properties** cannot be run (and `.tsx` cannot be
  checked or executed) without a compiler; the adapter reports that rather than pretending otherwise;
  projects should add a pinned TypeScript compiler and `tsconfig.json` to use full type checking;
```

This documents the workaround within CodeWitness's own documentation.

---

### Option 4: Skip Files with Parameter Properties (Not Recommended)

**Effect:** The build check would ignore files containing parameter properties

**Why this is NOT recommended:**

1. It would silently skip validation of potentially problematic files
2. The current behavior (reporting an error) is more honest
3. A file that can't be parsed by Node's stripper will also fail at runtime if it uses parameter properties without TypeScript compilation
4. The error message already clearly identifies the problem

---

## Recommended Approach for CodeWitness

**Default behavior is correct:** The current adapter behavior properly:

1. Tries to find a pinned TypeScript compiler
2. Uses `tsc --noEmit` if available
3. Falls back to Node's type stripper if no compiler is found
4. Reports errors clearly when the stripper fails

**Documentation improvement:** Update status.md to explicitly mention parameter properties and the workaround (Option 3 above).

**For users:** The recommendation should be clear: "If your TypeScript project uses parameter properties, add a pinned TypeScript compiler and `tsconfig.json`."

---

## Verification

The workaround (adding `tsc` and `tsconfig.json`) has been verified by CodeWitness's own design documented in `TypeScriptBuildProvider.java`:

- Lines 58-63: The adapter checks for `tsc` binary and `tsconfig.json`
- Lines 67-83: If both are present, it runs `tsc --noEmit --pretty false`
- Lines 87-113: Otherwise it falls back to Node's type stripper

The TypeScript fixture (`fixtures/typescript-withdrawal-ledger/`) currently has no `typescript` in `devDependencies` and no `tsconfig.json`, which means it uses the fallback syntax checker. This is fine for the simple code in that fixture (which doesn't use parameter properties).

---

## Summary Table

| Approach | Effort | Impact | Recommendation |
|----------|--------|--------|----------------|
| Add TypeScript compiler to project | Low | Projects get full type checking | ✅ **RECOMMENDED** |
| Refactor parameter properties | Medium-High | Changes code style | Alternative |
| Update CodeWitness documentation | Low | Improves clarity | ✅ **SHOULD DO** |
| Skip problematic files | Medium | Silently loses coverage | ❌ Not recommended |

---

## Related

- Current limitation: `docs/status.md` lines 176-178
- Implementation: `codewitness-adapter-typescript/src/main/java/dev/codewitness/typescript/TypeScriptBuildProvider.java`
- Node.js experimental feature: https://nodejs.org/api/module.html#modulestriptypescripttypes
