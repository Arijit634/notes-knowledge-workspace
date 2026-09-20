# Notes & Knowledge Workspace — Implementation Agent Operating Contract

**Project:** Notes & Knowledge Workspace  
**Document role:** Implementation Agent Operating Contract  
**Status:** Accepted  
**Date:** 2026-09-17  
**Acceptance date:** 2026-09-17  
**Last procedural revision:** 2026-09-20  

## 1. Role and authority

This file governs **how** future implementation agents work. It does not define product behavior and is not an eighteenth Approved Baseline, Product/Architecture/API/Schema authority, Roadmap replacement, or implementation authorization.

Authority precedence, highest first:

1. Approved Baselines and formally approved amendments.
2. Implementation Roadmap for dependency and phase ordering.
3. `PROJECT_CONTEXT_HANDOFF.md` as durable summary/context.
4. Accepted `AGENTS.md` for working procedure.
5. The current explicitly authorized implementation task/work package.
6. Existing code and tests as evidence of current implementation state.

A task may narrow work; it cannot silently override a baseline. When a task conflicts with approved semantics, stop and use exactly:

> **REQUIRES <NAME> BASELINE AMENDMENT BEFORE IMPLEMENTATION**

Do not make a “reasonable” choice that changes approved behavior.

### Approved Baseline register

| # | Approved Baseline | Path |
|---:|---|---|
| 1 | Product Vision and Target Flagship Requirements | `docs/product/Product_Vision_and_Target_Flagship_Requirements.md` |
| 2 | ADR-001 — Architecture Style / Deployables | `docs/architecture/ADR-001_Architecture_Style_and_Deployables.md` |
| 3 | High-Level Architecture | `docs/architecture/High_Level_Architecture.md` |
| 4 | Technology Stack and Compatibility | `docs/architecture/Technology_Stack_and_Compatibility.md` |
| 5 | Security Architecture | `docs/security/Security_Architecture.md` |
| 6 | Threat Model | `docs/security/Threat_Model.md` |
| 7 | Domain Model | `docs/domain/Domain_Model.md` |
| 8 | Schema & Migration Design | `docs/data/Schema_and_Migration_Design.md` |
| 9 | Search / AI / Retrieval Design | `docs/knowledge/Search_AI_and_Retrieval_Design.md` |
| 10 | API Design | `docs/api/API_Design.md` |
| 11 | Backend Low-Level Design | `docs/backend/Backend_Low_Level_Design.md` |
| 12 | Frontend Low-Level Design | `docs/frontend/Frontend_Low_Level_Design.md` |
| 13 | Testing Strategy | `docs/testing/Testing_Strategy.md` |
| 14 | Deployment & Operations | `docs/operations/Deployment_and_Operations.md` |
| 15 | CI/CD & Quality Gates | `docs/cicd/CI_CD_and_Quality_Gates.md` |
| 16 | Observability | `docs/observability/Observability.md` |
| 17 | Implementation Roadmap | `docs/roadmap/Implementation_Roadmap.md` |

Approved Baselines are read-only during normal implementation. Amendments require a separate human-authorized documentation task.

## 2. Authorization and progression

Required sequence:

`Roadmap Approved → AGENTS.md separately created/reviewed/accepted → explicit implementation authorization → Phase 0 begins`

- Acceptance of this file does not authorize implementation.
- Do not begin or advance a work package or phase without explicit human authorization.
- Package completion does not authorize another package.
- Phase completion does not authorize the next phase.
- Agents report exit evidence; the human records completion and controls progression.

## 3. Workspace and public-repository boundary

Operate only in the current Notes & Knowledge Workspace. This is a clean rebuild.

Do not search for, copy from, edit, or import the legacy Notes App repository, including its source, tests, schema, configuration, Git history, credentials, private Notes, or production data. Do not search outside the workspace unless the authorized task specifically requires an external resource.

Treat every repository artifact as potentially public. Never write real credentials, API/provider/cloud keys, passwords, private keys, cookies, session IDs, TOTP seeds, recovery codes, OAuth/OIDC secrets, security-email capability material, private Notes, personal/production data, confidential employer/client material, infrastructure secrets, dumps, or sensitive logs. Use reserved fake values, synthetic fixtures, and documented environment-variable names.

If secret material is discovered, **STOP AND REPORT**. Adding it to `.gitignore` is not remediation.

## 4. Required pre-work check

At the start of every authorized implementation package:

1. Read this file and `PROJECT_CONTEXT_HANDOFF.md`.
2. Read the relevant Roadmap phase/work-package section.
3. Read every directly governing baseline section; do not trust prior-agent memory.
4. Inspect workspace state and, if present, Git status.
5. Identify pre-existing user-owned changes.
6. Identify the phase, package boundary, governing baselines, relations, endpoints, routes, suites, Domain invariants, and Threat rows.
7. Verify that no STOP AND REVIEW condition is active.

Prefer one coherent package. Normal vertical-slice shape:

`approved migration/data → owner-module backend → persistence adapter → API → frontend where applicable → security → tests → safe telemetry → verification`

Avoid whole-phase or big-bang implementation prompts.

## 5. User-owned changes and destructive actions

Treat every pre-existing file/change as user-owned unless it is clearly generated by the current package. Never silently discard, reset, revert, overwrite, stash, delete, or rewrite unrelated work. Do not opportunistically refactor unrelated code.

Commands such as `git reset --hard`, `git clean -fd`, and `git clean -fdx` require explicit human authorization for that exact destructive action. If unrelated changes interfere, stop and report.

## 6. Git and external-action discipline

Git is initialized locally under the human-approved Phase 1 Work Package 1A. The canonical public GitHub repository and `origin` now exist under the separately authorized Work Package 1C-B, and `main` has received its controlled initial publication. Reviewed short-lived branches and pull requests are the normal path for subsequent changes. Before every package, inspect repository status, branch, remotes, and protection state. Commit, tag, push, PR, release, and repository-setting changes remain separately authorized actions; the existence of the public repository grants none of those permissions by itself.

Once Git exists, inspect status before editing; preserve unrelated work; do not force-push, rewrite protected history, bypass protections, or change repository settings without authorization. Coding and committing are separate permissions. Do not commit, tag, push, open a PR, create a GitHub repository, publish a package, or release without explicit authorization.

Real external changes always require task-specific authority. Do not independently create/modify cloud resources, DNS, certificates, OAuth clients, Brevo, Gemini/Groq credentials, GitHub settings, production deployment, real email, or real secret rotation.

## 7. Scope and architecture guardrails

Do not introduce a requirement, relation/table, module, backend deployable, API endpoint, browser route, AI workflow, provider, role/capability, public surface, or durable subsystem without approved authority. A need for relation 39, endpoint 92, route 28, module 8, or another backend service is STOP AND REVIEW.

Preserve:

- one Spring MVC/Servlet Spring Boot backend deployable;
- seven Spring Modulith business modules: Identity, Profile, Notes, Publishing, Discovery, Knowledge, Moderation;
- the approved acyclic dependency DAG;
- provider-owned APIs and consumer-owned SPIs implemented by provider modules;
- module-private repositories; no cross-module JPA or SQL shortcuts;
- approved short local ACID boundaries;
- provider/network I/O outside database transactions.

Do not introduce WebFlux/R2DBC, microservices, RabbitMQ, Kafka, an API gateway, service mesh, Kubernetes, separate AI/worker service, or separate vector database without a superseding Approved Baseline.

## 8. Technology and dependency discipline

Technology Stack and Compatibility owns versions. Do not upgrade/substitute because something newer or more familiar exists. Apply Roadmap Phase 0/1 compatibility gates. If an approved version cannot work, STOP AND REVIEW.

Before adding a production dependency, verify that it is approved or within an approved implementation boundary, existing platform/JDK/Spring capability is insufficient, version management follows the approved BOM/lock policy, no runtime service is added, ARM64 is supported, and no unavoidable paid infrastructure results. Ambiguity is STOP AND REVIEW.

Do not add Checkstyle, Spotless, PMD, ESLint, Prettier, Stylelint, or another lint/format stack without the review required by Technology and CI/CD baselines.

## 9. Database and migration truth

- Flyway is the sole normal schema authority; production auto-DDL is disabled.
- Never use Hibernate create/update, ad-hoc startup DDL, or PgVectorStore shared-schema mutation.
- Use PostgreSQL 18 semantics and only approved extensions `vector` and `pg_trgm`.
- UUIDv7 is the approved identity strategy; do not add a UUIDv4 fallback.
- Migrations are owner-schema scoped, deterministic, forward-only, authorization-scope preserving, and free of hidden cross-module ownership.
- Do not casually rewrite a released migration; add a corrective forward migration.

PostgreSQL-specific truth must use PostgreSQL 18 with approved pgvector through Testcontainers. H2, SQLite, mocks, and in-memory imitations cannot prove constraints, transactions, concurrency, claim/fencing SQL, FTS, trigram/vector behavior, cursors, uniqueness, partial indexes, or Flyway evolution.

## 10. API, session, and authorization contract

API Design owns method, path, status, payload, authorization, Problem Details, idempotency, concurrency, and ETags. Preserve `/api` with no `/api/v1`, JSON camelCase, RFC 9457, opaque cursors, strong ETags, `428` missing precondition, `412` stale precondition, and `409` true domain conflict. Endpoint 65 AI-processing and endpoint 77 Publication source-status remain volatile no-store projections, not mutation validators. Do not invent convenience endpoints or expose internals.

Preserve opaque server-side Spring Session JDBC/PostgreSQL sessions, HttpOnly/Secure cookies, mandatory CSRF, pre-MFA/full-session separation, fixation-sensitive rotation, and current Account eligibility. No browser JWT or access/refresh tokens in browser storage. Never disable CSRF for convenience.

Authorization is deny-by-default. Immutable UserId is authority; email, handle, locator, object key, client-supplied user ID, and frontend/cache state are not. Authorize before exposing private content and before candidate retrieval. Background work revalidates current eligibility immediately before sensitive effects/provider dispatch. Cached results never grant authority.

## 11. Private/public, Notes, and frontend state

Private Note and Publication are separate aggregates. Publication is not `note.public = true` or a live private Note view. Public data uses approved snapshots/projections; private Save does not mutate the public copy. Unpublish/removal/suspension establishes approved logical denial before success. Public media never falls through to private storage without current-public authorization.

Preserve explicit Save; do not add autosave. `NoteEditorSession` owns draft, baseline, ETag, dirty/saving/conflict state, and reconciliation. Never force-overwrite a `412`. Do not persist private drafts/data in localStorage, sessionStorage, or IndexedDB. TanStack Query owns remote state, React Hook Form ordinary forms, and local reducers bounded workflows. Redux/Zustand requires approved amendment.

Private frontend state is memory-only unless explicitly approved. Security-link tokens follow `URL fragment → memory → immediate history scrub`; MFA continuation and CSRF are memory-only. Raw Markdown HTML is disabled, URL policy is safe, arbitrary remote image loading is blocked, and AI/provider output is never trusted HTML.

## 12. Attachment and media contract

Approved initial upload flow:

`authorize Note → unreachable private staging locator → stream → bounded validation outside DB transaction → reauthorize/revalidate → short accepted-Attachment transaction → COMMIT → metadata authorizes existing stored bytes → 201`

Do not introduce an authoritative pre-byte pending row, initial validation `202`, post-`201` promotion dependency, public private-media bucket, or client object key as authority. Attachment storage/owner access remains separate from AI processing.

## 13. Search, AI, and provider boundaries

Ordinary Search remains AI-independent and owner-scoped through PostgreSQL FTS (`simple` + `english`), `pg_trgm`, and deterministic extraction. Do not replace it with generation or claim exhaustive completeness from top-K semantic retrieval. Saved URLs are data; never automatically fetch, crawl, preview, or open them.

Per-Note AI state is independent. The Account preference initializes future Notes only. AI ON persists independently from processing permission; actual processing also requires current authorization, source eligibility, Note AI state, current processing-policy acknowledgement, and provider/task eligibility. Missing acknowledgement does not toggle AI OFF.

AI-OFF content never reaches embeddings, semantic AI candidates, model context, multimodal derivation, Related Notes AI, AI suggestions, or provider dispatch. Approved deterministic non-AI operations may still use it.

Gemini is first-deployment provider under the approved synthetic/public-safe/non-sensitive unpaid-tier posture. Mandatory CI never requires live Gemini, Groq, Google OIDC, Brevo, OCI Object Storage, production Redis, or OCI; use deterministic adapters/doubles. Do not send arbitrary sensitive private content because a key exists.

Groq is hosted inference, evaluated from a local harness only after Phase 14, non-blocking, and never an automatic fallback. Ollama/local models are separate.

Private vectors use exact-initial retrieval, not ANN by default. Enforce owner, eligibility, AI state, source, revision/generation, and lineage before candidates; never retrieve globally then post-filter by owner. Embedding provider/model/dimension changes require new lineage, re-embedding/reindexing, evaluation, controlled cutover, and cleanup. Never mix incompatible spaces.

## 14. Durable work and security email

Durable work uses PostgreSQL, not in-memory queues, RabbitMQ, Kafka, or Redis Streams. Claims, leases, retries, and fencing follow approved SQL/domain semantics. Provider I/O occurs outside DB transactions; completion writes are conditional/fenced; reclaimed work gets a fresh lease token; current state is revalidated before effects. Never log a lease token, hash, fingerprint, or derivative.

`identity.security_email_delivery` remains narrow Identity-owned work with exactly six states: `queued`, `claimed`, `retry_wait`, `submitted`, `failed`, `obsolete`. There is no persisted `expired`; expiry may transition to `obsolete` with a safe reason.

The one-way capability verifier is authoritative. Recoverable token material is non-authoritative, authenticated-encrypted, externally keyed, worker-only, never logged, and terminally cleared. Brevo uses `JavaMailSender`/Jakarta Mail over authenticated TLS SMTP; do not add a Brevo HTTP API/SDK. Do not promise exactly-once mailbox delivery.

## 15. Moderation boundary

Moderation is narrow and public-target only; privilege is live-revalidated. Moderators cannot browse private Notes, Attachments, Search, vectors, Ask/provider context, or security secrets. Do not add impersonation, broad super-admin, generic user browsing, private investigation, or self-service privilege assignment.

## 16. Observability, errors, and privacy

Use safe server-controlled request/trace correlation and bounded metric dimensions. Never log private Note/tag content, Search/Ask text or answers, chunks/citations, prompts/responses, Attachment/PDF/transcript content, email, passwords, session/CSRF/OAuth/OIDC/MFA/recovery/capability/key material, provider keys, or object credentials.

Never use user/resource/request/trace IDs, raw URLs/query strings, or arbitrary errors as metric dimensions. Release version/SHA/digest belongs in logs, deployment annotations, and restricted dashboard metadata, not ordinary custom metric dimensions.

Do not swallow exceptions to pass tests. Return only approved Problem Details; never expose stack traces, SQL, host paths, provider payloads, private data, or secrets. Internal failure evidence uses a stable safe error code, safe trace ID, and sanitized structured logs.

## 17. Testing, evaluation, accessibility, and performance

Testing accrues with every package, not only Phase 12. Use exactly these suite categories: `FAST`, `DATABASE`, `API`, `SECURITY`, `RETRIEVAL`, `FRONTEND`, `E2E`, `EVALUATION`. Do not create a ninth.

For each package, run the narrowest affected tests, broader relevant module/package suites, and required cross-cutting suites. Report exact commands/results. Do not claim an unexecuted test passed.

No rerun-until-green. Retry only after identifying a concrete infrastructure failure. Investigate/fix/report flaky tests; do not hide them with sleeps. Concurrency tests use deterministic coordination.

Use approved frozen synthetic corpora. Never fabricate scores. Keep lexical/fuzzy, exact-vector, hybrid, focused-fact, semantic-extraction, deterministic-extraction, and multimodal evidence distinct. Security/isolation is zero tolerance; averages cannot excuse violations. Live-provider qualification is separate, non-sensitive, and never mandatory CI.

Accessibility belongs to every frontend slice: semantic HTML, keyboard behavior, focus, labels/error association, dialogs, and responsive usability. Do not defer it or add a library merely to claim compliance.

Measure before optimizing. Do not add caches, ANN, queues, services, or secondary stores speculatively. Preserve simple exact/queryable designs; avoid N+1 and unbounded queries; bound pagination, uploads, batches, and provider context.

## 18. Cost, network, and command discipline

Expected additional recurring spend is **₹0 / $0**. Never enable paid resources/tiers/runners/models, add payment methods, accept overage, provision chargeable services, or increase storage into paid use. If the approved feature cannot fit, STOP AND REVIEW; inconvenience does not authorize drift.

Mandatory tests make no network calls. Do not download and execute arbitrary scripts. Approved build-tool dependency resolution is allowed only within an authorized package. Prefer official sources for external documentation; external mutations remain separately authorized.

Once tooling exists, use repository wrappers/lockfiles—Maven Wrapper and `npm ci`—rather than unpinned workstation tools. Never hardcode `G:\Notes Knowledge Workspace` or another developer-machine path. Keep Windows development, Linux CI, and ARM64 production portable.

## 19. Code, artifact, TODO, and change control

Prefer clear, boring code aligned with Domain/API language. Avoid generic `Utils`, giant services, catch-all repositories, God services, hidden globals, reflection tricks, needless frameworks, and résumé patterns. Comments explain non-obvious invariants, security/concurrency rationale, or approved compatibility workarounds—not obvious lines.

Do not commit generated junk such as `target/`, `node_modules/`, browser output, logs, temporary uploads, local databases, secrets, or IDE state unless explicitly required. Do not delete user files because they look generated.

TODOs may only reference bounded later-phase work that is not falsely reachable and does not weaken authority. Never leave authorization, validation, CSRF, or owner restrictions as reachable TODOs.

Never fake completion with hardcoded success, dead buttons, placeholder security, in-memory persistence presented as durable, fake search, or fake AI. Test doubles must not masquerade as production behavior.

Modify only files reasonably connected to the package. If scope expands unexpectedly, reassess. Report unrelated defects unless they block the package and a bounded fix is clearly authorized.

## 20. Definition of Done and phase control

A work package may be recommended complete only when:

- authorized scope is implemented with no baseline conflict;
- relevant migration/database truth is correct;
- required tests are added/updated and pass, including zero-tolerance evidence;
- architecture/module boundaries pass;
- no private data reaches logs/metrics;
- no unauthorized relation/API/route/dependency exists;
- applicable frontend states and telemetry are truthful;
- no known package blocker remains.

Compilation alone is insufficient. Agents do not declare a Roadmap phase complete; report evidence against its exit criteria and await human decision. Do not begin the next package/phase in the same task without explicit authorization.

## 21. Required end-of-package report

Every implementation package ends with:

1. Phase and work package.
2. Scope completed.
3. Approved Baselines consumed.
4. Files added.
5. Files modified.
6. Relations affected.
7. API endpoints affected.
8. Frontend routes affected.
9. Domain invariants covered.
10. Threat rows/release blockers covered.
11. Tests added/updated.
12. Exact test/build commands executed.
13. Results.
14. Security evidence.
15. Telemetry added/changed.
16. Dependency/version changes.
17. Migration changes.
18. Known limitations/TODOs.
19. STOP AND REVIEW items, if any.
20. Git status.
21. Confirmation that no unrelated user changes were discarded.

If a test did not run, state why.

## 21A. Review package for human inspection

After all intended verification is complete, every authorized work package that creates or modifies two or more repository files must produce a clean review ZIP under `review-packages/`. A one-file package does not require a ZIP unless the human requests one.

The ZIP exists only for human/ChatGPT review. It is not a source artifact, build artifact, release artifact, CI artifact, intended Git content, or evidence that another work package is authorized. Creating it never authorizes or begins the next package.

Each ZIP must contain the human-reviewable repository state relevant to the package and an in-archive `REVIEW_MANIFEST.txt` recording project/phase/package identity, creation time, branch/remotes/status, included files and their SHA-256 hashes, relevant tool versions, executed verification commands and results, unresolved prerequisites, and exclusions. Build output, dependency caches, `.git/`, `review-packages/`, secrets, credentials, private keys, real-value `.env` files, temporary/local artifacts, and personal or production data must be excluded. Never include sensitive material for inspection; potential secret or private material is **STOP AND REVIEW**.

## 22. Mandatory STOP AND REVIEW

Stop and report—never silently work around—any:

- Approved Baseline conflict or ambiguous security behavior;
- need for a new relation, endpoint, route, module, deployable, provider, or durable subsystem;
- version compatibility failure or dependency-classification ambiguity;
- provider capability/terms mismatch or AI privacy ambiguity;
- cross-user isolation, authorization-before-retrieval, or public/private uncertainty;
- migration authority conflict or inability to use real PostgreSQL evidence;
- inability to preserve mandatory session/CSRF design;
- automatic-spending risk or loss of zero-cost viability;
- production-only secret needed by mandatory CI;
- destructive action required against user-owned work.

Forbidden temporary shortcuts include disabling auth/CSRF, allow-all development, browser JWT, public buckets, in-memory durable queues, global retrieval then owner post-filtering, hardcoded owners, mocked database truth, payload logging, or live private-Note AI testing.

## 23. Prohibited helpful drift

Do not add GitHub OAuth, import/export, share links, flashcards, digests, recommendations, URL previews/fetching, comments, followers, DMs, teams, collaboration, recommendation feeds, a general chatbot, autonomous agents, admin super-console, E2EE Vault, RabbitMQ, Kafka, Elasticsearch/OpenSearch, API gateway, Kubernetes, service mesh, separate AI backend, or separate worker service unless a later Approved Baseline promotes it.

## 24. Evidence rule and current state

Claims require executable evidence. Do not claim security, ARM64 compatibility, retrieval scores, production readiness, or zero cost without the corresponding approved test/revalidation. Planned Roadmap interview evidence is not a résumé achievement.

At this file's creation:

- seventeen Approved Baselines exist and the Roadmap is approved;
- all Roadmap phases, including Phase 0, are **NOT STARTED**;
- Git is uninitialized;
- no implementation is authorized;
- no application source exists by authority;
- after human acceptance of this file, the next required step is separate explicit implementation authorization for Phase 0.

Current operational state is maintained in `PROJECT_CONTEXT_HANDOFF.md`. As of the authorized Phase 1 Work Package 1C-B bootstrap, local Git and canonical `origin` exist, public `main` has been published, and reviewed pull requests are the normal change path. This current state does not rewrite the historical creation facts above and does not authorize another work package, Phase 2, a release, or deployment.
