# Smarter Categorization — Design

> Status: Spec for review. After approval, plan via `superpowers:writing-plans`.

## Motivation

ISSUE.md #4 asks for better auto-categorize / auto-describe. Investigation
confirmed most of the asked-for behavior is already shipped
(`CategorizationEngine` + `DescriptionEngine` run in `TxIngestor.ingest`,
`MerchantNote` is per-merchant by data model). The real gaps for the reporting
user — who has renamed/added/deleted built-in categories and reassigned their
semantics — are:

1. **Merchant variants don't merge.** `MerchantMapping` lookup is exact-string
   on the normalized merchant. `STARBUCKS` and `STARBUCKS KLCC LEVEL 3` are
   distinct keys, so labeling one doesn't help the other.
2. **Description suggestions miss too often.** Same root cause as #1, plus
   the (category, any-bucket) generalization is missing — only
   (category, *exact* bucket) is consulted.
3. **Hardcoded `KeywordRules` is useless under a customized taxonomy.** Rules
   return category *names* like `"Food"`, which the engine looks up via
   `categoryDao.getAll().firstOrNull { it.name == name }`. With renamed or
   deleted built-ins, that lookup fails silently → uncategorized. Worse, the
   user can't redirect a built-in rule family to a custom category without
   editing source.

Approach C from the brainstorm: fuzzy merchant matching + retroactive backfill
+ per-category user-editable keyword patterns + removal of the hardcoded
runtime rules. This is a single coherent improvement — each piece supports the
others — and is scoped as one spec.

## Out of scope

- Pattern-authoring helpers (regex tester previewing matches against recent
  merchants). Useful but deferred until pattern authoring proves error-prone.
- Bulk re-categorization of *already-categorized* rows. Risk of overwriting
  deliberate user choices.
- A toggle to opt back into runtime built-in rules. Built-in patterns become
  editable defaults (data-on-rows), not a separate code path.
- "Smarter" prediction using transaction amount, cross-merchant patterns, or
  ML over notification metadata. Separate future item.
- Filtering non-transaction notifications under CAPTURE_ALL (ISSUE.md #4's
  fourth sub-ask). That is "approach A" from the brainstorm, scoped
  separately.

## Section 1 — Engine logic

### CategorizationEngine lookup order (new)

For a given normalized merchant string `M`:

1. **Exact `MerchantMapping`.** `merchantMappingDao.get(M)?.categoryId`.
   Unchanged from today. User's explicit voice always wins.
2. **Longest-prefix `MerchantMapping` (NEW).** Load all mappings, find the one
   whose `merchantNormalized` is the longest token-aligned prefix of `M`.
   - Token-aligned: `stored` is a prefix of `captured` iff
     `captured == stored` OR `captured.startsWith(stored + " ")`. Byte-prefix
     `"STARB"` does not match `"STARBUCKS KLCC"` for our purposes; the
     boundary is whitespace.
   - Longest-prefix tie-breaking: with stored `"STARBUCKS"` and
     `"STARBUCKS RESERVE"`, captured `"STARBUCKS RESERVE BANGSAR"` resolves
     to `"STARBUCKS RESERVE"`.
   - If two stored mappings tie at the same length (rare in practice),
     deterministic order is `learnedAt` descending — most recently learned
     wins.
3. **User category `keywordPattern` (NEW).** For each category whose
   `keywordPattern` is non-null, compile it as a `Regex` with
   `RegexOption.IGNORE_CASE` and test `pattern.containsMatchIn(M)`. First
   match wins. Iteration order: by `sortOrder` **ascending** (lower
   `sortOrder` = higher priority, matching the existing display order in
   the categories list), so the user controls priority via the existing
   category-ordering UI.

   **Conflict semantics:** if the same merchant token appears in multiple
   categories' patterns (e.g., `STARBUCKS` in both `Coffee` and `Dining`),
   the lower-`sortOrder` category wins for that capture. The shadowed
   category's pattern is silently inactive for the conflicting merchant.
   The UI surfaces this at pattern-save time (see Section 3) so it's not
   silent in practice — only at runtime.
4. **Null** — uncategorized.

The legacy step that consulted hardcoded `KeywordRules.match(M)` is **removed**.

### DescriptionEngine lookup order (new)

For `(merchant M, categoryId C, bucket B)`:

1. `MerchantDescriptionMapping(M, B)` exact.
2. `MerchantDescriptionMapping(M, *)` exact merchant, any bucket.
3. **NEW:** longest-prefix merchant match → repeat steps 1+2 against the
   matched stored merchant. Implemented as: find longest-prefix
   `MerchantMapping`-style match in the description mapping table, then look
   up that stored merchant for `(B)` and `(*)`.
4. `CategoryDescriptionMapping(C, B)`.
5. **NEW:** `CategoryDescriptionMapping(C, *)` — same category, any bucket.
   Most-recently-learned wins.
6. Null.

### Cost / performance

`MerchantMapping` and `MerchantDescriptionMapping` tables are small (typical
user <1k rows, heavy user <10k). Each ingest does one extra DAO call to load
mappings + an in-memory prefix scan. Negligible vs. existing dedupe and
tier-check work in `TxIngestor.ingest`.

If profiling later shows this is hot, the fix is to add a small in-memory
LRU cache on the engine — out of scope for v1.

## Section 2 — Schema and data model

### Category entity

```kotlin
@Entity(tableName = "categories")
data class Category(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val name: String,
    val color: Int,
    val isCustom: Boolean,
    val sortOrder: Int,
    val keywordPattern: String? = null,  // NEW: raw regex, IGNORE_CASE applied at use
)
```

`keywordPattern` is stored as the raw regex string. The engine wraps it with
`RegexOption.IGNORE_CASE` at compile time so users don't need to think about
the uppercase normalization. Empty string is normalized to `null` at save.

### Migration v6 → v7

```sql
ALTER TABLE categories ADD COLUMN keywordPattern TEXT DEFAULT NULL
```

Existing rows get NULL. Existing-install users (the reporting user, and
anyone with custom categories) get null patterns. New users see the seeded
defaults (below).

### Backup format v6 → v7

- `Backup.CURRENT_VERSION = 7`.
- `BackupCategory` gains `keywordPattern: String? = null`. The default keeps
  older v5/v6 backups readable as before.
- `BackupImporter.SUPPORTED_VERSIONS = 5..7`.
- `applyBackup` upserts `keywordPattern` with the same merge policy as the
  rest of the category fields (categories don't carry an `updatedAt`, so the
  current behavior is "import overwrites existing"; this is preserved).

### Seeding for fresh installs

`TxDatabase.seedCategories` is updated to write `keywordPattern` for each
seeded built-in. Patterns come from a new `DefaultKeywordPatterns` table
(see Section 4) — a one-to-one port of the current `KeywordRules` regex
strings.

Existing users are not retroactively reseeded; their categories were created
under v6 and keep `keywordPattern = NULL` until they opt in via the editor.

## Section 3 — UI

### Categories settings screen

Each category row gains an "Auto-match keywords" affordance. The existing
edit dialog (today: name, color, sortOrder) grows a multi-line text input.

Behavior:
- Empty input persists as `NULL`.
- Save validates by compiling as `Regex(input, RegexOption.IGNORE_CASE)`.
  On failure, an inline error appears under the field and Save is blocked.
- **Conflict check on save.** Split the new pattern by `|`, normalize each
  alternation token (uppercase, whitespace-stripped). For each token, scan
  other categories' patterns for the same token (same split + normalize).
  If any overlap is found, show a non-blocking warning dialog before save:
  *"\<TOKEN\> also auto-matches \<Other category\>. Captures will go to
  whichever category sorts first (currently: \<winner\>). Save anyway?"*
  with `[Save]` and `[Cancel]`. Pure literal-overlap detection — regex
  intersection is intentionally out of scope; false negatives (e.g.,
  `STARB.*` vs literal `STARBUCKS`) are acceptable. Catches the common
  user mistake.
- Placeholder text for NULL patterns: `e.g. STARBUCKS|TEALIVE|MIXUE`.
- Small "?" tooltip explaining: "Regex matched against the captured
  merchant name. Case-insensitive. Merchants are uppercase with trailing
  SDN BHD / ENT / SVC removed."
- The same input appears in the "Add category" dialog, defaulted empty.

### Backfill action

Settings → new "Data" section (or fold into "Cloud sync" if it fits) → a
"Re-categorize using learnings" button.

Behavior:
- Runs the new engine over every transaction with `categoryId == null` and
  separately over every transaction with `description == null`.
- Updates the row only if the engine returns a non-null result.
- Single confirmation summary at the end: "Updated category on N rows,
  description on M rows."
- No progress UI — the operation is fast (<1s for realistic transaction
  counts; the engine is in-memory after the one initial mapping load).
- One-tap, no auto-trigger. The user runs it after authoring patterns or
  doing a batch of labeling.

### Match-count cue (included in v1)

A small chip next to each category in the list showing two numbers:
`learned: N · auto: M` where:
- `learned` = count of `MerchantMapping` rows whose `categoryId` is this
  category (i.e., explicit user-taught merchant→category links).
- `auto` = count of *distinct merchant strings* among rows captured in the
  last 30 days that would match this category's `keywordPattern` (regex
  test, dedup by `merchantNormalized`). 0 if `keywordPattern` is null.

Implementation: two cheap queries per category — `COUNT(*) FROM
merchant_mappings WHERE categoryId = :id` and a single one-shot scan of
recent transactions in-memory against compiled patterns. Cost is bounded by
(#categories × #recent-merchants-distinct), trivially under a millisecond
for any realistic dataset.

## Section 4 — Built-in rules removal

### Runtime change

`CategorizationEngine.categorize()` removes its call to
`KeywordRules.match(M)`. After the fuzzy `MerchantMapping` lookup and the
user-pattern scan, it returns null. No silent fallback to a hardcoded
category name.

### Source-of-truth rename

`KeywordRules.kt` is renamed to `DefaultKeywordPatterns.kt`. Same data,
repurposed: a `Map<String, String>` of category name → regex string. Lives
in the `domain` package next to the seed.

`TxDatabase.seedCategories` reads from `DefaultKeywordPatterns` and writes
both `name` AND `keywordPattern` on each built-in seed row.

The legacy `KeywordRules.match()` function and `KeywordRules.Rule` data
class are deleted along with the file rename. No callers remain after the
engine change.

### Why this is safe

For existing users with customized taxonomies (the reporting user): runtime
`KeywordRules` already returned null for them in nearly all cases (because
the names it returned no longer matched any category). Removing the lookup
produces identical behavior for new captures.

For fresh installs: default categories ship with the same patterns as the
old hardcoded rules, just stored as data. First-capture experience identical
to today, with the new affordance that users can now edit/delete/refine
those patterns.

### Test coverage migration

Any existing tests exercising `KeywordRules.match(...)` get deleted along
with the function. Their coverage is replaced by tests on the new engine
pipeline that runs through fuzzy match + user pattern scan against a
synthetic category table seeded with the same patterns. Net test count
likely similar or slightly higher.

## Files most likely to touch

- `app/src/main/java/cy/txtracker/data/Entities.kt` — add `keywordPattern`.
- `app/src/main/java/cy/txtracker/data/TxDatabase.kt` — `@Database(version = 7)`, update seed.
- `app/src/main/java/cy/txtracker/di/DatabaseModule.kt` — `MIGRATION_6_7`.
- `app/src/main/java/cy/txtracker/data/CategoryDao.kt` — query helpers if needed
  (e.g., `observeAllOrderedBySortOrder` if not present).
- `app/src/main/java/cy/txtracker/domain/CategorizationEngine.kt` — new lookup order.
- `app/src/main/java/cy/txtracker/domain/DescriptionEngine.kt` — new lookup order.
- `app/src/main/java/cy/txtracker/domain/KeywordRules.kt` → renamed
  `DefaultKeywordPatterns.kt`.
- `app/src/main/java/cy/txtracker/export/Backup.kt` — version bump, field add.
- `app/src/main/java/cy/txtracker/export/BackupImporter.kt` — `SUPPORTED_VERSIONS`.
- `app/src/main/java/cy/txtracker/export/BackupExporter.kt` — write `keywordPattern`.
- `app/src/main/java/cy/txtracker/data/TransactionRepository.kt` — `applyBackup`
  upsert + a new `recategorizeUncategorized()` and `redescribeUncategorized()`
  for the backfill action.
- `app/src/main/java/cy/txtracker/ui/settings/categories/CategoryEditDialog.kt`
  (or wherever the existing edit dialog lives) — new text input.
- `app/src/main/java/cy/txtracker/ui/settings/SettingsScreen.kt` +
  `SettingsViewModel.kt` — the backfill button.
- Tests: new `CategorizationEngineTest`, `DescriptionEngineTest`,
  `MerchantPrefixMatchTest` (the fuzzy helper, if extracted), backfill
  unit-level tests on the repository methods.

## Effort estimate

Roughly 2.5 days, matching the brainstorm:
- Schema + migration + backup + seeding: ~half day.
- Engine logic + fuzzy helper + tests: ~half day.
- Category edit UI + validation: ~half day.
- Backfill action + UI + tests: ~half day.
- Cross-cutting (Hilt wiring, deleting old code paths, polish): ~half day.

## Open questions

None. All design decisions are pinned:
- Backfill scope: both null-category and null-description rows.
- Match-count chip: included in v1 (`learned: N · auto: M` format).
- Pattern conflict handling: warn-but-allow at save time, lower-`sortOrder`
  wins at runtime.
