# Notes & Knowledge Workspace

## New Project Context Handoff

Document status: project-level context and decision baseline  
Project type: clean rebuild for a public portfolio repository  
Current phase: Product Vision and Target Flagship Requirements, ADR-001 Architecture Style and Deployables, High-Level Architecture, Technology Stack and Compatibility, amended Security Architecture, Threat Model, Domain Model, amended Schema & Migration Design, Search / AI / Retrieval Design, amended API Design, Backend Low-Level Design, Frontend Low-Level Design, Testing Strategy, Deployment & Operations, CI/CD & Quality Gates, Observability, and Implementation Roadmap are seventeen Approved Baselines; `AGENTS.md` is accepted separately as the subordinate Implementation Agent Operating Contract; the authoritative Schema catalog contains 38 relations, including the single approved `identity.security_email_delivery` relation; Roadmap Phase 0 is COMPLETE following human completion approval on 2026-09-19, Phase 1 is IN PROGRESS and ready for human completion review, Work Packages 1A, 1B, and 1C-A are ACCEPTED following human review, Work Package 1C-B is COMPLETE pending human review upon this protected validation PR's successful merge and exact-main verification, and Phases 2 through 14 remain NOT STARTED

---

## 1. Purpose of this document

This document transfers the approved product direction, architecture-discovery conclusions, security principles, retrieval expectations, scope boundaries, and documentation workflow into the new workspace for **Notes & Knowledge Workspace**.

It is the durable context file for future design and implementation work. It is not a Product Requirements Document, architecture specification, implementation roadmap, backlog, or permission to generate an application skeleton.

The seventeen authoritative design documents are Approved Baselines: `docs/product/Product_Vision_and_Target_Flagship_Requirements.md`, `docs/architecture/ADR-001_Architecture_Style_and_Deployables.md`, `docs/architecture/High_Level_Architecture.md`, `docs/architecture/Technology_Stack_and_Compatibility.md`, `docs/security/Security_Architecture.md`, `docs/security/Threat_Model.md`, `docs/domain/Domain_Model.md`, `docs/data/Schema_and_Migration_Design.md`, `docs/knowledge/Search_AI_and_Retrieval_Design.md`, `docs/api/API_Design.md`, `docs/backend/Backend_Low_Level_Design.md`, `docs/frontend/Frontend_Low_Level_Design.md`, `docs/testing/Testing_Strategy.md`, `docs/operations/Deployment_and_Operations.md`, `docs/cicd/CI_CD_and_Quality_Gates.md`, `docs/observability/Observability.md`, and `docs/roadmap/Implementation_Roadmap.md`. Security Architecture includes its approved durable security-email material amendment, and Schema & Migration Design includes its approved security-email amendment. The authoritative relational catalog is 38, with exactly one added Identity-owned relation. Together with this handoff, those Approved Baselines govern any later explicitly authorized implementation entry. Accepted `AGENTS.md` supplies subordinate procedural implementation guidance and is not an eighteenth Approved Baseline.

Technology Stack and Compatibility owns the initial framework/toolchain and frontend dependency versions, dependency/BOM management strategy, Java/Maven/Node/npm and PostgreSQL/pgvector baselines, Spring AI compatibility findings, the Knowledge-owned multimodal embedding adapter requirement, compatibility constraints, mandatory implementation-time smoke tests, and version/update policy. ADR-001 remains authoritative for architecture style and deployables; the High-Level Architecture remains authoritative for high-level logical architecture and flows. Security Architecture owns the mandatory trust boundaries, authentication/session architecture, authorization invariants, CSRF/OIDC/MFA/recovery security, provider/AI boundaries, upload/rendering security, secure degradation, and security-testing invariants; its release-blocking isolation rules remain binding. Its approved security-email amendment preserves one-way verifier authority while permitting temporary authenticated-encrypted delivery material only for narrow Identity retry, with keys outside PostgreSQL, no work-row authority, mandatory current-state revalidation before send, terminal recoverable-material clearing, and no exactly-once external-mail promise. The single Identity durable delivery relation supports only capability links and approved security notices; `security_event_id` is technical correlation/deduplication only; lease-token fencing protects reclaimed work; ready and reclaim paths are separate; provider I/O stays outside Identity database transactions; and both old and new email-change recipients are event-bound, protected, worker-only, unlogged, and temporary. No generic notification system exists. Threat Model owns adversaries, attack paths, abuse cases, qualitative risk, validation obligations, residual risk, and release-blocker forward traces; its central register contains 55 stable threats, its release-blocking security and isolation failures remain binding, and it creates mandatory forward requirements for later Domain, Data, Search/AI/Retrieval, API, Backend, Frontend, Testing, Deployment, CI/CD, and Observability work. Domain Model owns the approved conceptual domain, module ownership, aggregates, entities, value objects, lifecycles, stable identities, concurrency semantics, publication boundary, AI participation semantics, moderation scope, cross-module consistency responsibilities, and 50 stable invariants (`DM-INV-001` through `DM-INV-050`); no Domain amendment was required. Schema & Migration Design owns the approved PostgreSQL relational representation, seven module-owned namespaces, ownership and authorization-scope structures, constraints, concurrency and generation foundations, durable-work model, private/public persistence separation, and Flyway evolution policy; its authoritative catalog contains 38 relations—11 Identity, 3 Profile, 6 Notes, 8 Knowledge, 4 Publishing, 3 Discovery, and 3 Moderation—and forward-traces all 50 Domain Model invariants. Search / AI / Retrieval Design owns the four internal query classes, authorization-safe candidate generation, lexical/fuzzy retrieval, exact initial private vector strategy, hybrid fusion, corpus-wide extraction, multimodal derivation, grounded answers, provenance/citations, provider dispatch/privacy gates, public retrieval separation, and evaluation obligations. It consumes the approved 38-relation baseline but did not require or use the Identity security-email relation. Threat Model release blockers remain binding.

API Design owns the external HTTP resource, command, security, error, pagination, idempotency, and concurrency contract. Its amended Approved Baseline uses the unversioned `/api` backend namespace, HTTP/JSON with camelCase and RFC 9457 Problem Details, opaque cursor pagination, opaque server-side browser sessions with mandatory CSRF and no browser JWT, and enumeration-safe private-resource behavior. Its 91 sequential endpoints preserve strong revision-backed core ETag/`If-Match` semantics for Note, Attachment, and Publication while placing volatile AI-processing in `GET /api/notes/{noteId}/ai-processing` and volatile owner Publication source status in `GET /api/me/publications/{publicationId}/source-status`. Both projections are authorization-scoped, no-store reads and are not write preconditions. The API also defines explicit contracts for Note lifecycle, versions, Attachments, Profile, Publishing, private Search/Knowledge, public Discovery, Reports, and Moderation; processing-policy acknowledgement remains Knowledge-owned and idempotent. Public media is backend-mediated and checked against the current Publication/current snapshot, public author and tag navigation are supported, and moderation includes begin-review, immutable terminal decisions, public removal, and narrow abusive-account suspension without private Note access. The API strong-ETag/volatile-projection amendment required no Schema change; the separately approved Identity durable security-email amendment produces the current authoritative 38-relation catalog without changing the API contract. The API document retains six Mermaid diagrams. All 50 Domain Model invariants and all Threat Model release blockers remain binding.

Backend Low-Level Design is the eleventh authoritative Approved Baseline. Status: **Approved Baseline**. Original date: **2026-09-13**. Last revised: **2026-09-14**. Baseline approval date: **2026-09-14**. It specifies one Spring MVC/Servlet Spring Boot deployable containing seven Spring Modulith modules in an acyclic dependency DAG, with provider-owned APIs, consumer-owned SPIs whose implementations live in provider modules, module-private repositories, and no cross-module JPA/SQL persistence access. Aggregate-oriented persistence uses JPA while conditional writes and PostgreSQL-specific search, vector, claim, and bulk mechanics use `JdbcClient`/native SQL. Critical same-deployable invariants use short local ACID transactions; provider, object-storage, and email I/O remain outside database transactions.

The Backend LLD maps all 91 API endpoints and all 38 relations. It preserves strong core ETags with volatile no-store projections at endpoints 65 and 77; Spring Session JDBC, mandatory CSRF, and pre-MFA/full-session rotation; ordinary email/password and policy-permitted Google OIDC self-registration without Okta or an admin-created-user prerequisite; Knowledge-owned AI gates and narrow provider capability ports; bounded PostgreSQL durable work in same-deployable executors; and the relation-38 Identity security-email executor. Required authority/public eligibility denial is synchronous while physical cleanup may lag. Moderation remains narrow and revalidates live privilege assignments. All 50 Domain invariants and every Threat Model release blocker remain binding.

Frontend Low-Level Design is the twelfth authoritative Approved Baseline. Status: **Approved Baseline**. Original date: **2026-09-14**. Baseline approval date: **2026-09-15**.

The Frontend LLD specifies a React/Vite SPA using same-origin relative `/api` calls and a feature-oriented `app → features → shared` organization. TanStack Query owns memory-only remote state, React Hook Form owns ordinary forms, and local reducers own bounded workflows; `NoteEditorSession` exclusively owns draft, ETag, dirty/conflict, and 412 reconciliation semantics, including same-tab Note-command ETag adoption. Private state is not persisted in `localStorage`, `sessionStorage`, or IndexedDB. Authentication uses the opaque HttpOnly server session plus memory-only CSRF, with only anonymous, MFA-required, and authenticated API session states; `AuthorityLost` is derived locally, security-link tokens follow fragment → memory → immediate scrub, and the MFA continuation is memory-only. Consumer signup/login remains independent of Okta provisioning.

The Frontend LLD maps 27 browser routes, all 91 API endpoints, and 13 Mermaid diagrams. Server-derived Related Notes, suggestions, Publishing, Search, and Ask operate on saved Note state; endpoint 65 remains separate AI-processing state. It preserves four Attachment modalities with upload/AI-state separation, the dedicated synchronous/asynchronous Ask operation model, strict Publication/private-cache separation, endpoint 77 source-status separation, immediate known-public-cache denial after authoritative removal, `ViewerCacheScope` isolation where current Like state exists, targeted public-profile projection invalidation, hardened Markdown rendering with non-fetching arbitrary image URLs, and narrow moderation with typed recent-auth/MFA, CSRF, and capability-loss handling. All 50 Domain invariants and every Threat Model release blocker remain binding.

Testing Strategy is the thirteenth authoritative Approved Baseline. Status: **Approved Baseline**. Original date: **2026-09-15**. Baseline approval date: **2026-09-16**. It defines seven test layers—pure unit, module/application, real PostgreSQL integration, HTTP/API integration, frontend component/integration, browser E2E, and offline retrieval/AI evaluation—and uses only the approved test technology: JUnit Jupiter with Spring Boot and Spring Modulith test support, narrow Mockito use, Testcontainers with PostgreSQL 18.6/pgvector 0.8.6, Vitest/jsdom, Testing Library/user-event/jest-dom, and Playwright. Database-specific truth requires real PostgreSQL/pgvector Testcontainers; deterministic provider adapters keep mandatory CI independent of live Google, Gemini, Groq, external email, cloud object storage, production Redis, and the internet.

The Testing Strategy makes cross-user isolation, AI-OFF exclusion, unauthorized citation/provenance, and public/private separation zero-tolerance suites. It traces 234 functional and 47 non-functional requirements and `AS-01` through `AS-40`; maps all 50 Domain invariants; keeps all 55 Threat Model rows binding, including executable evidence for the 15 explicit release-blocking rows and the global blocker classes; covers all 38 relations, 91 sequential endpoints, and 27 sequential frontend routes; and contains eight Mermaid diagrams. Identity/OIDC/session/CSRF/MFA and relation-38 security-email behavior, strong ETags, and controlled concurrency remain mandatory evidence. Note AI state remains independent from processing-policy acknowledgement through four explicit cases: AI ON without current acknowledgement, later acknowledgement, policy-version change, and AI OFF despite valid acknowledgement.

Initial Attachment validation is synchronous and returns `201` after unreachable staging, bounded validation, reauthorization, and a short accepted-Attachment transaction; there is no initial validation-`202` or authoritative pre-byte pending-row workflow, and upload validation remains separate from Knowledge AI processing. Account and Note deletion tests require logical authority/public/AI denial before success while physical cleanup may lag. Publication/public-media immediate denial, narrow moderation with live privilege revocation, a frozen synthetic deterministic retrieval corpus, and separate lexical/fuzzy, exact-vector, hybrid, focused-fact, semantic-extraction, deterministic-extraction, and multimodal metrics remain binding. `ViewerCacheScope`, editor/concurrency, and frontend-security tests remain mandatory; Playwright stays a focused 12-journey suite; accessibility retains automated/manual responsibilities; and flaky or rerun-until-green tests cannot satisfy release blockers. `FAST`, `DATABASE`, `API`, `SECURITY`, `RETRIEVAL`, `FRONTEND`, `E2E`, and `EVALUATION` retain their approved suite meanings. Approval of the baselines does not authorize implementation.

Deployment & Operations is the fourteenth authoritative Approved Baseline. Status: **Approved Baseline**. Original date: **2026-09-16**. Baseline approval date: **2026-09-16**. Its initial goal is the complete portfolio deployment at ₹0/$0 recurring cost under revalidated free tiers, with no automatic paid resource, overage, provider purchase, or paid failover. The primary target is one OCI Ampere A1 Always Free VM planned at approximately 2 OCPUs/12 GB subject to live entitlement/capacity verification. Linux ARM64 is mandatory; one Spring Boot application deployable and one replica contain the seven modules and bounded workers. The Vite-built React SPA is packaged into that artifact and uses same-origin `/api`; a verified free hostname plus Let's Encrypt serves the canonical HTTPS origin. Public application traffic is limited to 80/443 while Bastion or strictly restricted key-only/source-allowlisted operator access remains separate.

The deployment baseline selects self-hosted PostgreSQL 18/pgvector on persistent block storage for authoritative data, JDBC sessions, vectors, and durable work, with separate migration/runtime/recovery roles, Flyway-only forward evolution, layered backup/restore rehearsal, and no unproven PITR/RPO/RTO claim. Application rollback is distinct from disaster restore. Same-VM Redis is transient and non-authoritative. Non-public OCI Object Storage is reached through the S3-compatible port with backend-mediated media authorization. Attachment bytes remain unreachable through staging and bounded validation until reauthorization and a short accepted-state commit; `201` follows that commit, with no validation `202` or post-`201` correctness move. Copied public media remains independent from later private Attachment deletion.

Security email uses the application Mail port and `JavaMailSender`/Jakarta Mail to an authenticated TLS Brevo SMTP relay; there is no Brevo HTTP API dependency and relation 38 remains durable authority. Google OIDC retains exact redirects, PKCE/state/nonce/issuer-subject protections, and an actual-project free-hostname/authorized-domain/TXT/Search Console readiness gate; DuckDNS or an equivalent is acceptable only after real verification. Gemini free tier is the first deployment provider family for synthetic/public-safe demonstration under the preserved unpaid-provider privacy limitation; `gemini-embedding-2` is the multimodal candidate and the stable free-tier chat model is selected during live smoke validation. AI quota/outage degrades AI only. Groq remains a non-blocking local experiment after successful deployment; embedding model/version/dimension changes require separate lineage and controlled reindexing.

Secrets remain external and purpose-separated. PostgreSQL is required for readiness; optional providers degrade their features. Graceful shutdown stops new work and preserves PostgreSQL claim recovery. The zero-cost guard covers compute, block storage, Object Storage capacity/requests, outbound transfer, email, and AI with soft limits below billable boundaries. The baseline contains 21 operational runbooks and 12 Mermaid diagrams. All 50 Domain invariants and every Threat Model release blocker remain binding, and approval grants no implementation or provisioning authority.

CI/CD & Quality Gates is the fifteenth authoritative Approved Baseline. Status: **Approved Baseline**. Original date: **2026-09-16**. Baseline approval date: **2026-09-16**. It selects GitHub, GitHub Actions, public GHCR, and the eventual public `notes-knowledge-workspace` repository; protected `main`, short-lived pull-request branches, and preferred squash merge apply without GitFlow, permanent `develop`, routine release branches, or automatic Continuous Deployment. Continuous Integration and Continuous Delivery use standard public `ubuntu-24.04` x64 runners for ordinary CI and the native public `ubuntu-24.04-arm` runner for the production artifact while current free capability remains verified. The ₹0/$0 target requires account/repository billing inspection, enforceable applicable hard spending controls, additional paid spend of ₹0/$0, and **STOP AND REVIEW** if zero-spend protection cannot be maintained.

Every Action is full-SHA pinned; permissions remain least-privilege; forks receive no publication, attestation, OIDC, cloud, or production authority; and privileged `pull_request_target` execution of untrusted content is forbidden. All eight Testing Strategy suites retain their meanings. PostgreSQL 18/pgvector Testcontainers, zero-tolerance security, Spring Modulith boundaries, Flyway clean/upgrade verification, 38-relation Schema protection, 91 API contracts, and 27 frontend routes remain gated. CodeQL, dependency review, reviewed weekly Dependabot updates, public secret scanning plus an explicit pinned scanner, and exact-image container scanning remain complementary controls.

Release builds one Linux ARM64 image once, smokes and scans it, generates its SBOM, pushes that same image to public GHCR, captures the canonical `sha256` digest, attests that exact digest, verifies provenance, records the release manifest, and only then permits human-authorized OCI promotion. Production identity is the digest, never `latest` or a mutable tag; SemVer does not imply `/api/v1`. OCI is neither a GitHub self-hosted runner nor a source-build host, and OCI/cloud/runtime secrets and live providers remain absent from mandatory deterministic CI. Artifacts and caches are bounded and correctness-neutral; rerun-until-green is forbidden; rollback remains schema-compatibility dependent; and release publication, production migration, and production promotion are serialized. The approved CI/CD document contains 19 quality-gate entries, five workflow classes, and 12 Mermaid diagrams. All 50 Domain invariants and every Threat Model release blocker remain binding.

Observability is the sixteenth authoritative Approved Baseline. Status: **Approved Baseline**. Original date: **2026-09-17**. Baseline approval date: **2026-09-17**. It selects the Spring Boot Actuator/Micrometer foundation, built-in structured JSON logging, private Prometheus-format exposition scraped by the least-privilege OCI host agent without a Prometheus server/local TSDB, OCI Monitoring/Logging/Console Dashboards/Alarms/Notifications, and one zero-cost external HTTPS synthetic while the current free entitlement remains verified. Application/Micrometer metrics stay distinct from OCI-native, host, deployment, synthetic, and bounded operator evidence. Metric dimensions are bounded and exclude user/resource/trace IDs, raw paths/queries, and private content; safe request correlation is non-authoritative.

The Observability baseline preserves exactly six security-email states—`queued`, `claimed`, `retry_wait`, `submitted`, `failed`, and `obsolete`—with eligibility expiry becoming `obsolete`; lease tokens and their fingerprints never enter telemetry. AI observability uses a verified local soft budget plus provider 429/resource-exhausted outcomes without claiming a provider remaining-quota percentage. PostgreSQL is readiness-critical while optional providers feature-degrade. Internal objectives remain 99.0% rolling core availability with a 1% error budget, the approved core/Search/retrieval latency targets, and Knowledge/security-email freshness targets; security/correctness boundaries remain zero-tolerance. Release identity appears in structured logs, annotations, the CI/CD manifest, and restricted dashboard metadata—not metric dimensions. The baseline defines seven dashboards, 27 alerts, 17 structured event classes, and 12 Mermaid diagrams; it preserves the 14-day operational log-use target while disclosing OCI's current 30-day physical minimum. It selects no dedicated tracing backend; future OpenTelemetry requires measured need and explicit review. No automatic paid telemetry is authorized, and all upstream counts remain binding.

Implementation Roadmap is the seventeenth authoritative Approved Baseline. Status: **Approved Baseline**. Original date: **2026-09-17**. Baseline approval date: **2026-09-17**. Document: **17**.

The Roadmap defines 15 dependency-ordered vertical-slice phases, Phase 0 through Phase 14, all initially **NOT STARTED**. Tests, security, observability, and CI accrue continuously rather than following backend-first layering. Phase 0 requires this Roadmap's approval, a separately authorized/created/reviewed/accepted `AGENTS.md`, and explicit implementation authorization; Phase 1 bootstraps repository/toolchain/compatibility; Phase 2 establishes persistence/runtime security; Phase 3 Identity; Phase 4 Notes; Phase 5 Profile/Attachments; Phase 6 ordinary deterministic Search; Phase 7 AI gates/durable Knowledge/multimodal; Phase 8 Ask/Related/Suggestions; Phase 9 Publishing/public Discovery; Phase 10 Reports/Moderation; Phase 11 operationalization; Phase 12 complete quality closure; Phase 13 zero-cost OCI production/recovery qualification; and Phase 14 public portfolio release/stabilization. Only after Phase 14 may a local evaluation harness compare Groq-hosted inference against the frozen synthetic/public-safe corpus; Groq is non-blocking and never an automatic fallback.

Its mechanically binding coverage is 38 relation assignments, 91 endpoint assignments, 27 route assignments, seven modules, 50 invariant traces, 55 threat traces, the 15 explicit row-level Threat release blockers plus the independent global blocker rule, 234 FRs, 47 NFRs, `AS-01` through `AS-40`, eight Testing suite categories, 19 CI/CD quality gates, five workflow classes, 21 Deployment runbooks, seven Observability dashboards, 27 alerts, 17 structured log-event classes, and 12 Roadmap Mermaid diagrams. It preserves the ₹0/$0 guard, Gemini-first production, STOP AND REVIEW instead of architectural drift or paid fallback, and the rule that Roadmap approval itself does not authorize implementation.

`AGENTS.md` status: **Accepted**. Original date: **2026-09-17**. Acceptance date: **2026-09-17**. Role: **Implementation Agent Operating Contract**. It is subordinate procedural implementation guidance under all seventeen Approved Baselines and is not an Approved Baseline. Roadmap Phase 0 is **COMPLETE**; Phase 1 is **IN PROGRESS**; Work Packages 1A and 1B are **ACCEPTED** following human review; Phases 2 through 14 remain **NOT STARTED**.

Accepted `AGENTS.md`, procedurally revised 2026-09-20, requires a clean local review ZIP after any authorized work package that creates or modifies two or more repository files. Review ZIPs live under Git-ignored `review-packages/`, exclude `.git/`, generated output, secrets, credentials, private data, and earlier review archives, and contain an in-archive `REVIEW_MANIFEST.txt` with file hashes and verification context. They exist only for human/ChatGPT inspection and never authorize the next work package.

### Phase 0 completion state

**Status:** COMPLETE  
**Human completion approval date:** 2026-09-19

Human review accepted the Phase-0 implementation-entry evidence. Authority verification confirmed that all 17 Approved Baselines and Accepted `AGENTS.md` remain intact. The workspace public-safety audit passed; no unresolved baseline amendment and no active **STOP AND REVIEW** blocker were found. The approved technology stack remains plausibly implementable, with required executable compatibility proof retained for Phase 1. The zero-cost GitHub, OCI, and provider model remains plausible subject to its already-recorded account-specific, live-qualification, and production rechecks. The Gemini-first capability and synthetic/public-safe/non-sensitive unpaid-tier privacy posture remain viable. At Phase 0 completion, Git remained uninitialized, and no implementation or scaffolding existed.

Local Phase-1 prerequisites are:

- **READY:** Java/Javac 25.0.4; Maven 3.9.16; Git executable present.
- **MUST ALIGN BEFORE PHASE-1 EXECUTION:** Node.js 24.21.0 LTS; npm 11.19.0; Docker Engine and Docker Compose to supported-current releases satisfying the approved Technology baseline.
- **PHASE-1 EXECUTABLE SMOKES STILL REQUIRED:** Java 25 / Spring Boot 4.1.1; the Boot-managed dependency set; Spring Modulith 2.1.1; Spring AI 2.0.1; springdoc 3.1.1 / Jackson 3; PostgreSQL 18 / pgvector / UUIDv7 / `pg_trgm`; Testcontainers; frontend clean install, typecheck, build, and tests; Playwright; the Micrometer Prometheus adapter; and ARM64 feasibility.

All later GitHub, OCI, Brevo, Google OIDC/hostname, Gemini capability/privacy, Redis/license, and production/ARM64 rechecks remain binding. Phase 1 Work Packages 1A and 1B are **ACCEPTED** following human review. Work Package 1B accepted Docker Engine 29.8.0, Docker Compose 5.5.1, Testcontainers 2.0.5, PostgreSQL 18.6, pgvector 0.8.6, qualified `pg_trgm`, qualified native PostgreSQL `uuidv7()`, and Flyway 12.4.0 from zero with no Product migrations. Spring AI 2.0.1 dependency compatibility passed without a live provider; the bounded synthetic `PgVectorStore` smoke passed with automatic schema initialization disabled; springdoc 3.1.1/Jackson 3 and the Prometheus-format Micrometer adapter passed; approved frontend dependency imports passed; Playwright 1.63.0 Chromium bootstrap passed; Node.js/npm remain 24.21.0/11.19.0; and the ARM64 feasibility checkpoint passed. No live provider or cloud credential was used, no baseline amendment was required, and no active **STOP AND REVIEW** item remains.

### Phase 1 current implementation state

**Status:** IN PROGRESS — READY FOR HUMAN COMPLETION REVIEW  
**Work Package 1A:** ACCEPTED  
**Work Package 1B:** ACCEPTED following human review on 2026-09-20  
**Work Package 1C-A:** ACCEPTED following human review on 2026-09-20  
**Work Package 1C-B:** COMPLETE — PENDING HUMAN REVIEW upon successful protected squash merge and exact-main verification

Current implemented Product counts remain zero: **0 Product relations**, **0 Product API endpoints**, and **0 Product browser routes**. The canonical public repository is `https://github.com/Arijit634/notes-knowledge-workspace`, with `origin` set to `https://github.com/Arijit634/notes-knowledge-workspace.git`. Initial `main` commit `771c1409660f6065dd838724299d401705b83bd7` was published without force. Initial MAIN CI run `35512958234` preserved a real failure: frontend and secret scan passed, while Maven-backed jobs failed because the Windows-created `mvnw` lacked executable Git mode. Initial CodeQL run `35512958261` likewise passed JavaScript/TypeScript and failed Java/Kotlin at the wrapper permission check. Validation commit `e4a566c910f80ee53c0e5dc4dbb4420d1231b8c7` records executable wrapper mode and current-state synchronization; PR `#7` (`https://github.com/Arijit634/notes-knowledge-workspace/pull/7`) qualified PR CI run `35513114227` and CodeQL run `35513114240` successfully before the final handoff update. Main protection requires pull requests and the eight observed Phase-1 PR/CodeQL contexts, enforces current-with-main checks for administrators, blocks force pushes and deletion, and requires no human approval that would deadlock the single maintainer. Dependency graph, Dependabot alerts/security updates, public secret scanning and push protection are enabled; Actions defaults to read-only and cannot approve pull requests; repository Actions secrets and environments remain empty; merge policy is squash-only with automatic head-branch deletion. The exact protected-main SHA is the squash-merge result of PR #7 and is recorded by Git and the Work Package review manifest rather than self-embedded in the commit whose hash it would change. Work Package 1C-B is complete pending human review only after that protected squash merge and exact-main MAIN CI/CodeQL verification succeed. Phase 1 remains IN PROGRESS; Product implementation, Phase 1 closure, Phase 2, release/deployment, GHCR publication, OCI, and live providers remain unauthorized.

These approvals, including approval of the implementation-time technology baseline, do not authorize source code, build files, schemas, migrations, containers, CI workflows, tests, implementation scaffolding, or design documents beyond the next explicitly authorized step. The mandatory compatibility smoke-test constraints remain binding when implementation is separately authorized.

---

## 2. Workspace verification

Verification performed when this handoff was created:

- Current project root: `G:\Notes Knowledge Workspace`
- The workspace was empty at a shallow inspection.
- Git was not initialized.
- No legacy Notes App source code was present.
- No files from the legacy repository were copied into this project.

The filesystem folder name differs from the preferred eventual repository slug only in formatting. The approved repository slug is:

`notes-knowledge-workspace`

Do not rename or move the current directory without explicit user direction.

---

## 3. Clean-rebuild and public-repository boundary

A legacy Notes App exists elsewhere. It has already undergone read-only codebase audit, architecture discovery, architecture reconciliation, product-scope analysis, and naming review.

That repository contains substantial technical and security debt and is **not** the implementation foundation for this project. This application is a clean rebuild from first principles.

Do not:

- Search outside this workspace for the legacy repository.
- Copy legacy source code, tests, configuration, dependency files, database schema, deployment assets, or Git history.
- Reuse legacy authentication or authorization code.
- Import credentials, secrets, private notes, or production data.
- Modify, clean, migrate, or otherwise operate on the legacy repository.
- Treat the earlier audit as an instruction to modernize the old code in place.

This repository is intended to become public. All committed documentation, examples, fixtures, configuration, and history must therefore be safe to publish. Do not include employer-confidential information, client identities, proprietary documents, private infrastructure details, real credentials, or personal note content.

A separate engineering project already demonstrates distributed systems and microservices. Its confidential details do not belong here. This project should deliberately tell a different engineering story: a carefully bounded modular monolith, consumer security, personal knowledge management, authorization-aware retrieval, polished product engineering, and complete public-project ownership.

---

## 4. Approved project identity

Formal display name:

**Notes & Knowledge Workspace**

Approved repository slug:

`notes-knowledge-workspace`

Approved concise subtitle:

> Private-first notes with semantic retrieval, cited AI answers, and controlled public discovery.

Do not invent or promote an acronym. In particular, do not call the project NKW, Notes App V2, Smart Notes, AI Notes, Knowledge Platform, Semantic Knowledge Workspace, or AI-Assisted Knowledge Platform.

The naming hierarchy is intentional:

`Notes -> accumulated personal knowledge -> retrieval and rediscovery -> grounded AI assistance -> optional controlled publication`

AI is a major capability, but it is not the product category and does not belong in the formal title.

---

## 5. Product thesis and priority hierarchy

Notes & Knowledge Workspace is a **private-first notes and personal knowledge application** in which users can:

1. Capture and deliberately save high-quality Markdown notes with bounded supported media attachments.
2. Organize their personal knowledge.
3. Reliably rediscover information despite imperfect memory, shorthand, and typos.
4. Ask questions across authorized notes.
5. Receive evidence-backed answers with source provenance.
6. Control which notes AI-backed features may use.
7. Deliberately publish selected snapshots into a lightweight public discovery surface.

Refined positioning after multimodal scope approval: a private-first notes workspace for capturing and rediscovering personal knowledge across text and supported media, with grounded AI assistance and deliberate public sharing.

The core product progression is:

`CAPTURE -> ORGANIZE -> REDISCOVER -> UNDERSTAND -> OPTIONALLY PUBLISH`

Publication is not the destination of every note.

Feature priority is not flat:

1. **Core product:** excellent private note capture and management.
2. **Core differentiator:** reliable rediscovery and grounded AI over the user's own authorized knowledge.
3. **Supporting capabilities:** secure identity, account, session, and profile management.
4. **Secondary feature:** deliberately small public-note discovery.

The project will fail as a product if the editor and ordinary search are weak, even if its AI features are impressive.

The project is not novel at the broad category level. Its portfolio value must come from engineering rigor: authorization enforced inside retrieval, transparent AI boundaries, grounded answers, safe publication semantics, deterministic tests, and unusually complete execution.

---

## 6. Scope terminology

Do not split the intended finished product into artificial V1, V1.1, and V2 definitions.

Use these classifications:

- **Target Flagship:** committed behavior of the finished product.
- **Optional / Future:** genuinely undecided or intentionally deferred capability.
- **Out of Scope:** deliberately rejected for the current product.

Implementation will occur in phases later, but a feature scheduled late in the roadmap is not a separate product version.

---

## 7. Target Flagship scope

### 7.1 Notes and editor

- Markdown-based notes.
- Create and edit notes.
- High-quality editor experience rather than a title field, textarea, and save button.
- Deliberate explicit Save for title, Markdown body, and comparable editor-owned fields.
- Visible Unsaved Changes, Saving, Saved, and Save Failed states.
- Ctrl+S, Cmd+S, or the platform-appropriate equivalent.
- Retention of current editor state and Retry Save after transient failure.
- Appropriate warning before abandoning dirty content where technically possible.
- Explicit commands such as pin, archive, restore, trash, tag changes, publish, update-public-copy, and unpublish may persist immediately without a second editor Save.
- Optimistic concurrency and explicit conflict handling.
- Tags, pinning, archive, trash, restore, and confirmed permanent deletion.
- Bounded immutable note-version history and restore.
- Cursor or other appropriate pagination.
- Responsive, keyboard-friendly workflows.
- Accessibility fundamentals.
- Designed loading, empty, error, offline/transient-failure, and recovery states.

Manual Save does not justify a primitive editor. The product still requires a polished Markdown authoring experience rather than a title field, undifferentiated textarea, and Save button alone.

### 7.1A Bounded multimodal attachments

- A note may contain zero or more supported images, audio/voice recordings, bounded video clips, and PDF attachments. Office files, ebooks, and other non-PDF document formats remain Optional / Future unless explicitly promoted later.
- Users can upload, view/play/open/download as appropriate, and remove authorized attachments.
- Upload state and AI-processing state are distinct and visible.
- AI-processing failure does not destroy a successfully stored attachment or make it unavailable to its owner.
- Supported types, sizes, durations, page limits, per-note limits, codecs, and storage quotas are downstream bounded design decisions; unlimited media and arbitrary file storage are not promised.
- Attachment access inherits private-note authorization.
- File acceptance requires size/type and content validation appropriate to the modality rather than trust in client metadata alone.
- Safe display/playback, generated object identity, lifecycle cleanup, and sensitive logging restrictions are mandatory product constraints.
- Object storage does not make private media public. Publication explicitly selects which supported attachments, if any, enter a public snapshot.
- The product remains a notes workspace, not a drive, file manager, media editor, or video-streaming service.

### 7.2 Search and rediscovery

- Fast ordinary search independent of generative AI.
- PostgreSQL full-text retrieval.
- Typo-tolerant and fuzzy matching, including evaluation of `pg_trgm`.
- Semantic/vector retrieval using pgvector.
- Hybrid ranking/fusion.
- Related-note discovery.
- Honest ranking and completeness semantics.
- Authorization constraints applied before retrieval, never only as a post-filter.

### 7.3 Ask My Knowledge and AI assistance

- Ask My Knowledge as a dedicated knowledge workflow, not a generic floating chatbot.
- All retrieval operates only on the authenticated user's authorized content. AI-dependent stages additionally require the note's independent AI state to be ON, while routed deterministic non-AI operations may return authorized data from AI-disabled notes.
- Source-note and source-section provenance.
- Insufficient-evidence behavior instead of unsupported answers.
- Multi-note synthesis where the question requires it.
- AI tag/topic suggestions that require user confirmation.
- A future-note AI default, independent per-note AI controls, and deliberate bulk actions for existing notes.
- Visible indexing, reindexing, deindexing, exclusion, and failure status.
- Provider abstraction, failure isolation, quotas, and deterministic test adapters.
- Normal notes functionality remains useful if the generative provider is unavailable.

### 7.4 Identity and account security

- Application-managed email/password registration and login.
- Email verification.
- Google OAuth/OIDC using the authorization-code flow.
- Secure server-side browser sessions backed by PostgreSQL.
- Opaque session identifiers in secure cookies.
- `HttpOnly`, `Secure`, and appropriate `SameSite` attributes.
- CSRF protection for state-changing browser requests.
- Session rotation after authentication.
- Logout and individual/other/all session revocation.
- Enumeration-resistant password recovery with hashed, single-use, expiring tokens.
- Optional-per-user TOTP MFA.
- Authenticator-app QR enrollment with verification before activation.
- Hashed, one-time recovery codes shown in plaintext only once.
- Recent-authentication checks for sensitive account and security actions.
- Rate limiting for authentication, recovery, MFA, and AI abuse paths.

Do not reproduce a tutorial-style custom JWT architecture or store access/refresh tokens in browser storage.

### 7.5 Profile management

- Immutable internal user ID used for ownership and authorization.
- A unique public handle required before first publication or public-profile activation, but not during registration for a private-only user.
- Display name and short bio.
- Custom avatar/profile image.
- Public profile and published-note view.
- Private account settings separated from public profile data.
- Separate security and session settings.

Public DTOs must never leak email, authentication identities, MFA state, recovery data, session data, moderation internals, or other private account fields.

Public handles are routing/display metadata only. Immutable internal identity remains the ownership and authorization key.

S3-compatible object storage supports sanitized avatars, private note attachments, and deliberately approved public attachment derivatives.

### 7.6 Public notes and discovery

- Explicit publish, update-public-copy, and unpublish actions.
- Stable public note URLs.
- A public snapshot that does not silently change through ordinary private editing or saving.
- Public author handle, display name, and sanitized avatar.
- Explore, Latest, Trending, public search, and relevant filters.
- Authenticated likes.
- Approximate privacy-conscious view counts.
- Basic reason-coded reports and narrow moderation.
- Strictly separate public search/vector structures from private-note search/vector structures.

Public functionality must remain small. Do not let it expand into a social network.

### 7.7 Engineering quality

- Domain-oriented modular architecture with enforced boundaries.
- Versioned database migrations.
- Authorization and isolation tests covering every retrieval path.
- PostgreSQL/pgvector integration testing with Testcontainers.
- Deterministic AI and retrieval fixtures.
- Reproducible Docker-based local development.
- CI/CD and documented quality gates.
- Secret scanning and public-repository hygiene.
- Restricted health/metrics endpoints, structured logs, and request correlation.
- Markdown sanitization and safe rendering.
- Operational visibility for durable background jobs.

---

## 8. Optional / Future scope

- GitHub OAuth.
- Import/export.
- Unlisted share links.
- Cheat-sheet and interview-revision transformations.
- Notifications and weekly digests.
- Flashcards and spaced repetition.
- Multiple external AI providers.
- Personalized recommendations.
- A separately deployed background worker.
- A dedicated AI/knowledge service if extraction criteria are later satisfied.
- A true client-side/E2EE Vault.
- Local-only Vault search or local-Ollama retrieval over client-decrypted Vault content.
- Unlimited/general file storage, arbitrary file types, rich media editing, a video-streaming service, and an elaborate transcription archive.
- Automatic URL previews or remote-content fetching.
- Deterministic sensitive-content warnings.

The Vault concepts are architecture candidates, not Target Flagship commitments.

---

## 9. Out of scope

- Comments, followers, direct messages, and social graphs.
- Team workspaces and collaborative editing.
- A generic chatbot mode unrelated to the user's notes.
- Autonomous AI edits, organization, or publication without confirmation.
- Creator analytics dashboards.
- A large administration or editorial-approval system.
- A recommendation-engine feed.
- Password-manager positioning or a credential-management subsystem.
- A committed E2EE Vault in the current flagship.
- Automatic fetching, crawling, opening, or previewing of arbitrary saved URLs.
- Elasticsearch or OpenSearch at the expected scale.
- RabbitMQ in the target architecture.
- Artificial microservices, an API gateway, or Kubernetes without measured need.
- Raw JWTs in `localStorage` or other browser storage.

---

## 10. Privacy model and AI-access semantics

Three different concerns must remain conceptually separate:

- **Visibility:** private or explicitly published snapshot.
- **AI access:** enabled or excluded.
- **Storage privacy:** conventional server-readable storage versus a possible future client-side/E2EE Vault.

For the Target Flagship, notes are conventional server-managed private data protected in transit and at rest. The server can process their plaintext as required to provide authorized note features.

Turning AI access off does **not** make a note end-to-end encrypted.

Each new account starts with **Default AI access for new notes** set to OFF. This setting supplies only the initial value of the AI control in future Create Note flows; it is not a separate enablement gate, pause control, or override for existing notes. The normal Target Flagship model has no additional account-wide AI processing gate or pause switch.

Create Note exposes **Use this note with AI**. The control initializes from the account default, and the user may change it before saving. The resulting ON/OFF value is persisted as that note's own independent AI state. A note may remain AI ON while the future-note default is OFF, or AI OFF while that default is ON.

Changing **Default AI access for new notes** affects only notes created afterward. It never changes existing notes retroactively. Changing existing notes requires either an explicit per-note action or one of two separately confirmed bulk actions: **Enable AI for existing notes** or **Disable AI for existing notes**. Bulk operations do not change the future-note default.

Before any note content or supported attachment is processed by AI for the first time, the product must present a brief understandable AI/privacy disclosure explaining what AI enablement permits, what remains available while a note is AI OFF, and the meaningful provider/processing policy. The disclosure applies regardless of whether AI was enabled through Create Note, a later per-note action, or a bulk action, and remains accessible later in AI/Privacy settings.

An AI-disabled note still participates in authorized non-AI functionality such as:

- Editing and explicit Save.
- Ordinary lexical search.
- Fuzzy/typo search.
- Tags and filters.
- Version history and restore.
- Authorized attachment viewing, playback, opening, or download.
- Deterministic structured extraction, including exhaustive supported-pattern extraction.

Its content must not participate in AI-dependent processing, even if a unified knowledge-query surface initiated the request:

- Embedding/vector indexing.
- Semantic AI retrieval.
- AI-dependent Ask My Knowledge stages.
- Semantic related-note suggestions.
- AI tag/topic suggestions.
- Model context sent to a local or external provider.
- Semantic category extraction or AI verification.
- Image, audio/voice, bounded-video, or PDF embedding and multimodal reasoning.

Turning a note from AI ON to AI OFF must schedule reliable deindex/deletion behavior. Every retrieval or indexing job must also recheck the note's current AI state before reading or writing, so a stale vector or queued job cannot bypass the disabled state.

Attachment AI access follows the parent note in Target Flagship across images, audio/voice recordings, bounded video clips, and PDFs. Do not introduce per-attachment AI permissions unless later evidence justifies the additional product complexity.

The visible surface does not determine whether processing is AI. A unified knowledge-query request may route to a deterministic non-AI operation and return authorized data from AI-excluded notes. The same excluded content may not enter embeddings, semantic retrieval, AI reranking/classification, generative context, media reasoning, suggestions, or semantic related-note retrieval.

If an AI-disabled note is later turned ON, independently of the future-note default, the application may schedule text and supported-media indexing again under the currently configured and disclosed provider policy. This is a consent and processing-state transition, not an encryption transition.

The architecture must never silently send private note content to an external model or embedding provider. Provider identity, processing location, data handling, configuration, and failure behavior must be documented and visible to the user. Local Ollama support is valuable, but Ollama is a model runtime/provider; it is not itself RAG.

---

## 11. Sensitive user content

This is a general-purpose notes workspace. Users may store arbitrary lawful personal text, including account information, credentials, reminders, lists, snippets, URLs, shorthand, misspellings, and other sensitive or unusual material.

The product must not market itself as a password manager or encourage credential storage. Deterministic sensitive-content warnings remain Optional / Future and are not a blocking product decision. If later promoted, such warnings must not silently change a note's settings.

If an authenticated owner has intentionally left a note AI-enabled and accessible, Ask My Knowledge may retrieve and return sensitive information from it to that same owner. The system should not invent a content-category refusal that prevents users from retrieving their own authorized data.

The critical guarantee is isolation: no other user may retrieve the note or any derived chunk, vector, citation, cache entry, job result, log content, or public projection.

---

## 12. Retrieval is not one algorithm

Do not reduce Ask My Knowledge to:

`question -> top-K vector chunks -> LLM answer`

At least four conceptual query classes must be designed and tested separately. They are internal behaviors of a general personal-knowledge retrieval system, not separate user-facing products or hard-coded domain features.

Conceptual routing direction:

`understand requested outcome -> select authorized strategy -> gather candidates/evidence -> rerank or verify where required -> extract and deduplicate -> preserve provenance -> answer or return results`

Users should not need to select technical modes such as Vector Search, RAG, Full Corpus Scan, Image Search, or Audio Search. Whether Search and Ask My Knowledge remain separate or partially unified surfaces is a downstream UX decision.

### 12.1 Ranked relevance retrieval

Examples:

- `Notes about Spring Security token rotation.`
- `Where did I write about preventing one user from reading another user's resource?`

Ranking quality matters; exhaustive coverage is not required. Hybrid top-K candidate retrieval may be appropriate.

### 12.2 Focused fact retrieval

Example: `What was my PlayStation password?`

The source note may say only `ps login`. Use authorized lexical, fuzzy, and semantic signals to find a small high-quality evidence set, then inspect or verify it as required. Answer only for the authenticated owner and preserve provenance.

### 12.3 Corpus-wide semantic extraction or aggregation

Examples:

- `List the movies I wrote down to watch later.`
- `Which restaurants did I want to try?`
- `Which libraries did I mention evaluating?`

These are examples of arbitrary user-defined semantic categories, not dedicated features. Evidence may be scattered across hundreds or thousands of notes and supported media. A small fixed top-K candidate set is inadequate when the requested outcome implies broad coverage.

The later design must evaluate established retrieve-rerank/verify-synthesize patterns, including query decomposition/expansion, lexical/fuzzy/vector candidate union, broad or batched inspection, semantic classification, second-pass verification, deduplication, stopping criteria, and provenance. Embeddings provide candidates, not final classification truth. Difficult classification should be allowed to produce match, non-match, or uncertain outcomes.

Complete eligible-corpus inspection does not prove perfect semantic classification. The product must distinguish **corpus coverage** from **semantic classification correctness** and communicate uncertainty honestly.

### 12.4 Corpus-wide deterministic extraction

Example: `Show me every URL I saved in my notes.`

URLs are one canonical example of a general capability for supported deterministic patterns. Do not create a URL-specific product subsystem. For deterministic patterns or explicit occurrences, top-K RAG is the wrong mechanism. The system must inspect the complete authorized searchable corpus or a complete structured index derived from that corpus.

Expected conceptual flow:

`all authorized notes -> non-AI deterministic extraction -> collect matches -> deduplicate/organize -> preserve every occurrence and source -> optionally use AI only for eligible post-processing`

Because this path can be non-AI, AI-excluded notes may participate in deterministic extraction. They must not be sent through embeddings, semantic classification, or model reasoning.

The result should report the count and source-note provenance. Identical URLs may be deduplicated for display only if all occurrence/source information remains available.

Saved URLs are user-owned text. Discovery does not authorize the system to fetch, visit, crawl, preview, or open them. Any future URL-preview feature requires a separate security design because saved links may target private, expired, malicious, tracking, adult, or otherwise unsafe resources.

### 12.5 Completeness vocabulary

- **Relevance question:** ranking quality matters; completeness is not promised.
- **Exhaustive deterministic question:** complete authorized coverage is the design goal and should be measured.
- **Semantic aggregation question:** high recall is required, but absolute completeness may be inherently uncertain and must be described honestly.

### 12.6 Multimodal rediscovery and media-grounded reasoning

Target Flagship commits to semantic rediscovery across AI-enabled text, images, audio/voice recordings, bounded video clips, and PDFs. Downstream technical validation chooses how each committed modality is fulfilled; a modality may be removed only through an explicit approved product-scope change based on evidence such as privacy, cost, quality, or feasibility.

A modality-neutral text query may return any eligible supported modality. A query naming a voice memo, picture, video, or PDF may use that as a constraint or boost. Users should not need separate search products for each modality.

Finding media and answering a detailed question about its contents are distinct:

- A `where` request may be satisfied by cross-modal retrieval and source navigation.
- A `why` or content question may require eligible transcription, extraction, or modality-capable reasoning over the retrieved evidence.

Route after evidence where possible. Do not invoke multimodal generation merely to return a source, and do not assume an embedding vector can reproduce or verify exact media content. Media-grounded answers require provenance to the note, attachment, and relevant segment/location where practical.

---

## 13. Authorization and provenance invariants

Authorization must constrain retrieval before candidate selection.

Rejected pattern:

`global mixed-user retrieval -> filter unauthorized results afterward`

Required pattern:

`authenticated internal user ID -> owner/visibility/AI-eligibility constraints -> retrieval -> evidence construction -> answer`

This applies to ordinary search, fuzzy search, vector search, hybrid search, related notes, Ask My Knowledge, multi-note synthesis, exhaustive extraction, caching, indexing, and background jobs.

Tests must eventually prove that User A cannot retrieve User B's private content through any direct or derived path.

Every material answer claim or extracted item should be traceable to the original authorized note and, where practical, the relevant section/version. Citations must never leak another user's identifiers, titles, snippets, URLs, or existence.

---

## 14. Approved publication model

Use a single stable, snapshot-bearing `Publication` derived from an immutable private `NoteVersion` checkpoint.

Conceptual lifecycle:

`private Note -> explicit preview and publish -> Publication containing approved snapshot`

The `Publication` owns its stable public identity/URL, source note/version provenance, approved public title/content/tags, state, timestamps, author reference, and justified engagement aggregates.

Do not use `Note.visibility` as the entire publication model. Ordinary private editing or saving must not alter public content. Do not introduce a separate `PublicationRevision` history unless a later approved requirement justifies it.

Behavioral rules:

- Publish creates the stable publication and copies only approved public fields.
- Editing or explicitly saving the private note does not change the public snapshot.
- The UI shows when the private source differs from the published snapshot.
- Updating the public note requires preview and explicit confirmation.
- Unpublish immediately removes public reads, public search/discovery, and public vector eligibility.
- Republish may reuse the stable publication identity.
- Trashing/deleting a published source note requires explicit confirmation and unpublishes it.
- Account deletion unpublishes all publications before asynchronous cleanup.
- Active publication provenance may pin the referenced note version against compaction.
- Publishing or updating requires an explicit decision about which supported attachments, if any, enter the public snapshot; later private attachment changes do not alter public media automatically.

Public queries must read only active publication snapshots and separate public indexes, never private note or private embedding tables.

---

## 15. Architecture baseline

Approved `docs/architecture/ADR-001_Architecture_Style_and_Deployables.md` formally owns the architecture-style and deployable decision: a domain-oriented modular monolith with Spring Modulith boundary support, exactly one initial Spring Boot backend deployable, one PostgreSQL physical database initially, internal Knowledge/AI, and a bounded same-deployable background executor. It introduces no initial RabbitMQ, API gateway, Kubernetes, dedicated AI service, or separate worker. Changing any of these decisions requires a superseding approved ADR.

Approved `docs/architecture/High_Level_Architecture.md` owns the high-level system and logical interaction picture while ADR-001 continues to own architecture style and deployable topology. Its baseline covers the browser/backend authority relationship; the seven logical modules and module-owned durable-work interactions; supporting infrastructure and provider boundaries; synchronous and asynchronous flows; authorization-before-retrieval; private/public separation; per-note AI and multimodal processing boundaries; publication flows; and degraded modes. It does not authorize implementation or override downstream decisions it explicitly defers.

### 15.1 Overall style

Use a domain-oriented modular monolith assisted by Spring Modulith.

Initial backend deployable count: exactly one.

The exact artifact/deployable name is **TO BE DECIDED** in the appropriate downstream architecture/naming document. This does not change the approved one-deployable decision.

AI remains an internal knowledge module. A bounded background executor runs in the same deployable initially. Extraction to a separate worker or service is an operational option only after measured need.

Spring Modulith should enforce and document genuine module boundaries through verification, module tests, generated diagrams/documentation, and selective internal domain events. It must not be used to imitate networked microservices inside one process.

### 15.2 Proposed modules and durable ownership

| Module | Durable ownership |
|---|---|
| Identity | Users, credentials, external identities, sessions, verification/recovery, MFA |
| Profile | Public profile metadata and avatar object references |
| Notes | Notes, versions, tags, attachments, and private note/attachment lifecycle |
| Publishing | Publication snapshots and lifecycle |
| Discovery | Likes, approximate view aggregates, reports, and public query projections |
| Knowledge | Private/public text/media representations, vectors, index status, and indexing jobs |
| Moderation | Reports and moderation actions |

PostgreSQL is one physical database, but cross-module repository access is prohibited. Modules collaborate through explicit application interfaces, projections, and selective events.

### 15.3 Important transaction boundaries

- Explicit note Save plus durable indexing-job scheduling.
- Version checkpoint plus publication create/update.
- Unpublish plus public-deindex scheduling.
- Like relation plus count update.
- MFA activation plus recovery-code creation.
- Security change plus required session revocation/rotation.
- Avatar/attachment reference changes plus asynchronous cleanup of superseded or deleted objects.

### 15.4 Background work

Use PostgreSQL-backed durable jobs for text and supported-media embedding/indexing/reindexing, transcription/extraction where selected, deindexing, retryable email delivery, avatar/attachment cleanup, public projection refresh where needed, and batched durable view-count updates.

Required job properties include explicit state, transactional scheduling with the originating mutation, bounded claims, retry/backoff, idempotency, coalescing, current-state revalidation, failure visibility, and recovery tooling.

Spring Modulith events may communicate domain changes and drive noncritical observers. They do not replace explicit workload state for critical asynchronous processing.

### 15.5 Service-extraction criteria

Do not extract a module merely to demonstrate microservices. Extraction requires several of the following:

- Independently significant scaling or resource profile.
- Failure isolation that cannot be achieved acceptably in-process.
- Stable and narrow API/event contracts.
- Independent deployment cadence or ownership.
- Clear data ownership without cross-database joins.
- Measurements showing that operational cost is justified.

Knowledge/AI is the most plausible first candidate, but it remains internal in the approved baseline.

---

## 16. Technology classification baseline

The approved Technology Stack and Compatibility document owns exact initial versions, dependency classifications, compatibility constraints, mandatory smoke tests, and update policy. The following is a concise technology-role summary and does not override that approved baseline:

| Technology | Classification | Purpose |
|---|---|---|
| Java 25 | Required direction | Modern backend baseline |
| Spring Boot | Required | Backend platform |
| Spring Security | Required | Authentication and authorization |
| Spring Session JDBC | Required | Revocable server-side sessions |
| Spring Modulith | Required | Boundary verification, module tests, documentation |
| Spring Data JPA | Required | Relational persistence |
| Flyway | Required | Versioned schema migrations |
| Spring AI | Required | Model abstractions and RAG integration |
| PostgreSQL | Required | Source of truth, relational filters, search, and durable jobs |
| pgvector | Required | Semantic retrieval |
| Redis | Optional / Conditional, never authoritative | Distributed rate limits, transient deduplication, optional measured cache |
| RabbitMQ | Not needed | PostgreSQL jobs satisfy current durability needs |
| React | Required | Web client |
| TypeScript | Required | Maintainable client contracts |
| Vite | Required direction | Frontend build and development |
| TanStack Query | Required direction | Server-state ownership |
| React Router | Required direction | Client navigation |
| React Hook Form | Recommended | Account/profile forms |
| Testcontainers | Required | Real PostgreSQL/pgvector integration tests |
| Docker and Docker Compose | Required | Reproducible build/runtime and local development |
| GitHub Actions | Required direction | Public-repository CI/CD |
| S3-compatible object storage | Required | Sanitized avatars, authorized private attachments, and approved public derivatives |
| OpenAPI | Recommended | Reviewable and testable API contract |
| Spring Boot Actuator | Required | Restricted health and metrics |
| Playwright | Required | Critical-path end-to-end testing |
| Managed email provider | Required production capability | Verification, recovery, security notices |
| Elasticsearch/OpenSearch | Not needed | PostgreSQL is sufficient at current scale |
| Kubernetes/API gateway | Not needed | No deployment requirement justifies them |
| Redux | Not needed by default | TanStack Query plus local state is sufficient |

Redis must not become the durable owner of sessions, likes, notes, publication state, permanent counts, or embeddings. PostgreSQL remains authoritative.

Object storage must use bounded modality-appropriate limits, content validation rather than trusting MIME type, safe display/playback handling, generated keys, lifecycle cleanup, and public exposure only of explicitly approved safe derivatives. Private attachments must not rely on guessable public object locations.

---

## 17. Search, AI, and provider design constraints

Approved `docs/knowledge/Search_AI_and_Retrieval_Design.md` owns the four internal query classes; authorization-safe lexical, fuzzy, exact-vector, hybrid, focused-fact, corpus-wide semantic, and deterministic retrieval; textual-surrogate-first multimodal derivation; grounding, provenance, citations, provider dispatch, public retrieval separation, and evaluation obligations. It selects separate `simple` and `english` FTS signals, owner-scoped `pg_trgm`, exact owner/current-eligibility-filtered private vectors initially, and RRF as the unified hybrid baseline. Private ANN is not initially approved and requires measured performance plus isolation, recall, plan, side-channel, and provider-capture evidence.

Query-only and source-bearing AI dispatches are distinct. Knowledge owns processing-policy acknowledgement and provider-processing eligibility coordination; Identity retains immutable UserId, Account, session, and security authority. AI-OFF content and AI-OFF-derived private aliases cannot enter AI-dependent stages. Corpus-wide semantic completeness is relative to a successfully validated operation coverage boundary and remains truthful under concurrent mutation. Old public generations become logically unreachable before commit. The approved relational catalog contains 38 relations; the added Identity security-email relation has no search, retrieval, vector, AI, or provider-context role and was not required by Search / AI / Retrieval Design. All 50 Domain Model invariants and Threat Model release blockers remain binding, and retrieval-design approval does not authorize implementation.

The exact production chat/embedding provider and model, vector dimensions, optional reranker adoption, external provider deployment, and production-private processing policy remain deliberately downstream within the approved technology, security, and retrieval boundaries.

Business and domain code must not couple directly to one provider. Model calls require timeouts, bounded concurrency, quotas, failure isolation, observability without private-content logging, and deterministic fake/test adapters.

Local development should support Ollama where practical. Production/provider choices must be evidence-based and must state what content leaves the user's device or deployment boundary.

### 17.1 Non-binding provider and model evaluation direction

- **Local text development:** Qwen through Ollama is a useful candidate for text RAG, prompts, classification, citations, and low-cost iteration.
- **Repeated multimodal development:** a current Gemini Flash-Lite family model is a candidate for image, audio, video, PDF, and routing experiments.
- **Difficult multimodal evaluation:** stronger current Flash-family models may be benchmarked selectively.
- **Hosted text deployment:** a hosted Qwen provider such as Groq is a later candidate, not a commitment.
- **Cross-modal embeddings:** Gemini Embedding 2 is a leading candidate to evaluate for a shared semantic space across supported text and media, not a frozen product requirement or model ID contract.

Multimodal reasoning feasibility has been manually validated in Google AI Studio using non-sensitive test media. That single experiment proves feasibility only, not accuracy or production suitability. No test media or API key belongs in repository content.

Provider selection is likely capability-based rather than a one-provider replacement sequence. It may vary by modality, requested task, privacy, availability, measured quality, and cost. Route reasoning based on retrieved evidence where possible.

Embedding-model changes are not assumed to be trivial configuration changes: spaces and dimensions may differ, and migration may require corpus re-embedding. Exact endpoints, versions, dimensions, integration, routing, and provider deployment belong downstream.

Current Free-tier availability is a development fact, not a privacy, quota, or production guarantee. Cloud experimentation is appropriate only for synthetic fixtures, harmless test media, and public-safe demos until the exact selected provider/tier data-handling terms are verified. Never equate free with private, and never silently send private text or media to an external provider.

### 17.2 Measurable retrieval and AI quality

The future testing strategy must provide a reproducible offline evaluation corpus with multiple synthetic users, hundreds or more notes, distractors, similar names, misspellings, shorthand, duplicates, scattered semantic categories, AI exclusions, public/private states, and supported media.

Report task-specific metrics rather than a blanket accuracy claim. Downstream evaluation may use Recall@K, Precision@K, MRR/NDCG, focused-answer correctness, semantic extraction precision/recall/F1, aggregation item recall, citation precision/recall, cross-modal retrieval recall, and insufficient-evidence correctness.

The engineering ambition is at least 90% on an appropriate approved quality metric for key non-security retrieval/semantic tasks before calling them mature, where that threshold is realistically meaningful. Exact metrics and thresholds must be designed and measured; no benchmark result exists yet.

Security is not a 90% metric. Supported tests require zero cross-user isolation violations, zero AI-excluded-content use on AI paths, and zero unauthorized citation/provenance leakage. One leak is failure.

The evaluation framework should compare pipeline improvements such as embedding-only baseline, hybrid retrieval, query expansion, reranking, model verification, and second-pass verification, and should support provider/model comparisons on the same corpus. Multimodal reporting must separate text-to-text, text-to-image, text-to-audio, text-to-video, text-to-PDF retrieval, media-grounded answers, provenance, AI exclusion, and cross-user isolation.

---

## 18. Public discovery details to preserve

Likes should use a durable relation with a uniqueness constraint on user and publication. Like/unlike operations should be idempotent; any cached/stored count remains reconcilable from the authoritative relation.

Views are approximate metrics. A recommended design uses Redis SET-if-absent keys with TTL for short-lived deduplication and periodically persists bounded aggregates to PostgreSQL. Authenticated viewers use internal identity; anonymous viewers should use a random non-identifying browser/session value. Do not retain raw IP addresses solely to count views. If Redis is unavailable, skipping a view is preferable to blocking the public page or creating uncontrolled duplicates.

Trending is a transparent time-decayed engagement ranking, not a personalized recommendation system. Exact formula, windows, weights, caps, and minimum thresholds belong in later design and require tests.

Minimal moderation includes author unpublish, reason-coded reports, moderator hide/remove, account suspension for abuse, and auditable actions. Do not build editorial approval, automated content scoring, an appeals platform, or a large administrative dashboard.

---

## 19. Quality and acceptance themes

Later requirements and test strategy must preserve scenarios equivalent to:

- A misspelled stored term such as `goohle` has a reasonable opportunity to match a later search for `Google`.
- Shorthand such as `ps` can contribute to an authorized answer to a query using `PlayStation` through hybrid signals.
- Arbitrary semantic categories scattered across many notes are extracted with high recall, verification/deduplication, provenance, and honest classification uncertainty, without hard-coded category features.
- Every supported deterministic match in the complete authorized corpus can be extracted without requiring generative AI; discovered URLs are never visited automatically.
- AI-excluded notes may contribute to authorized non-AI deterministic extraction but not embeddings, semantic classification, multimodal processing, or model context.
- Text queries can semantically rediscover AI-enabled images, audio/voice recordings, bounded video clips, and PDFs. These are committed Target Flagship modalities; technical validation chooses how to fulfill them and cannot silently remove one.
- Media-grounded questions use eligible content understanding after retrieval and preserve attachment/segment provenance.
- User A's private notes, attachments, and derived representations remain inaccessible to User B through every search, AI, indexing, caching, citation, or job path.
- Attachment access survives AI-processing failure with truthful processing state.
- Disabling AI access removes derived AI representations and stale jobs cannot recreate them.
- Publishing creates a deliberate snapshot; later private editing/saving or attachment changes do not leak into public content.
- Unpublishing removes public reads and all public retrieval eligibility promptly.
- AI/provider failure does not break ordinary note editing or search.

Claims such as secure, grounded, exhaustive, private-first, and authorization-aware must be backed by executable evidence, not only README language.

---

## 20. Documentation workflow

Create authoritative design documents in this order, reviewing each before moving to the next when it freezes consequential decisions:

1. Product Vision and Target Flagship Requirements.
2. Architecture Style and Deployables ADR.
3. High-Level Architecture.
4. Technology Stack and Compatibility.
5. Security Architecture.
6. Threat Model.
7. Domain Model.
8. Schema and Migration Design.
9. Search, AI, and Retrieval Design.
10. API Design.
11. Backend Low-Level Design.
12. Frontend Low-Level Design.
13. Testing Strategy.
14. Deployment and Operations.
15. CI/CD and Quality Gates.
16. Observability.
17. Implementation Roadmap.
18. `AGENTS.md`.

This sequence may be adjusted only for a clear dependency reason. Do not allow later implementation convenience to silently rewrite approved product behavior.

The first document must define the finished target, user/problem framing, product hierarchy, functional behavior, non-goals, constraints, and acceptance criteria. It must not prematurely freeze database tables, ranking formulas, cloud vendors, model IDs, or other downstream implementation choices.

---

## 21. Decision ledger

### Approved baseline decisions

- Clean rebuild; legacy code is not an implementation source.
- Formal name: Notes & Knowledge Workspace; no acronym.
- One finished Target Flagship scope delivered through phased implementation.
- Private notes and trustworthy retrieval are primary; public discovery is secondary.
- One Spring Boot backend deployable organized as a domain-oriented modular monolith.
- Spring Modulith is recommended for enforceable internal boundaries.
- Technology Stack and Compatibility is authoritative for the approved initial framework/toolchain versions, dependency/BOM strategy, frontend dependency baseline, PostgreSQL/pgvector baseline, Spring AI compatibility findings, Knowledge-owned multimodal embedding adapter requirement, compatibility constraints, mandatory implementation-time smoke tests, and version/update policy.
- Security Architecture, including its approved durable security-email material amendment, is authoritative for mandatory security trust boundaries, controls, and invariants. Capability validity remains one-way-verifier authority; temporary authenticated-encrypted delivery material is non-authoritative, externally keyed, narrowly decryptable, revalidated before send, and terminally cleared. Identity durable email is limited to capability links and already-approved security notices; both old and new email-change recipients are event-bound and protected. Exactly-once external mail is not promised, no generic notification system exists, and moderation authority still does not grant private-note browsing.
- Threat Model is authoritative for adversaries, attack paths, abuse cases, qualitative risk, validation obligations, residual risk, and release-blocker forward traces; it does not authorize implementation.
- Domain Model is authoritative for conceptual domain concepts, identity, ownership, aggregate boundaries, lifecycles, concurrency semantics, and its 50 stable domain invariants; it does not authorize implementation.
- The amended Schema & Migration Design is authoritative for its current 38-relation representation, constraints, persistence ownership, concurrency/generation state, durable work, private/public data separation, and forward-only data evolution; it does not authorize implementation. Exactly one relation was added: Identity-owned `identity.security_email_delivery`, bringing Identity to 11 relations while every other module count remains unchanged.
- Search / AI / Retrieval Design is authoritative for retrieval strategy, AI eligibility, authorization-safe candidate generation, lexical/fuzzy and exact-vector behavior, hybrid fusion, grounding, multimodal derivation, provider dispatch, provenance/citations, public retrieval separation, and evaluation; it does not authorize implementation.
- The amended API Design is authoritative for the 91-endpoint external HTTP resource, command, authentication/session/CSRF, authorization, error, pagination, idempotency, concurrency, private/public media, Search/Knowledge, Discovery, and Moderation contract, including strong revision-backed Note/Attachment/Publication core ETags and separate no-store volatile processing/source-status projections; it required no Schema change and does not authorize implementation.
- Backend Low-Level Design is the eleventh authoritative Approved Baseline (original date 2026-09-13; last revised and approved 2026-09-14). It is authoritative for the one-deployable seven-module backend structure, acyclic provider-API/consumer-SPI boundaries, module-private persistence, JPA/`JdbcClient` split, short local transaction boundaries, external-I/O separation, 91 endpoint and 38 relation implementation mapping, strong core validators and volatile projections, session/CSRF/OIDC mechanics, Knowledge-owned AI gates, bounded durable executors, relation-38 security-email processing, logical-denial boundary, and live narrow moderation authorization; it does not authorize implementation.
- Frontend Low-Level Design is the twelfth authoritative Approved Baseline (original date 2026-09-14; approved 2026-09-15). It is authoritative for the React/Vite SPA structure, state ownership, 27 browser routes, all 91 endpoint interactions, session/CSRF and memory-only continuation behavior, `NoteEditorSession` concurrency, saved-state feature boundary, Attachment and AI-state separation, Publication/private and viewer-scoped cache behavior, hardened Markdown rendering, accessibility/responsiveness, and narrow moderation UX; it does not authorize implementation.
- Testing Strategy is the thirteenth authoritative Approved Baseline (original date 2026-09-15; approved 2026-09-16). It is authoritative for the seven-layer evidence model, approved testing technologies, zero-tolerance isolation suites, complete Product/Domain/Threat/Schema/API/Frontend traceability, deterministic database/provider evidence, concurrency and AI-state/acknowledgement testing, synchronous initial Attachment validation, retrieval evaluation, focused browser/accessibility coverage, and flaky-test policy; it does not authorize implementation.
- Deployment & Operations is the fourteenth authoritative Approved Baseline (original date and approved 2026-09-16). It is authoritative for the revalidated zero-cost OCI A1/ARM64 one-VM topology, one packaged-SPA application replica, same-origin edge and restricted operator access, PostgreSQL/pgvector/Flyway/backup authority, transient Redis, S3-compatible non-public object storage, authoritative Attachment commit semantics, Brevo SMTP, Google OIDC hostname readiness, Gemini-first/Groq-later rollout, provider/privacy and outbound-transfer guardrails, health/degradation, graceful shutdown, recovery, 21 runbooks, and 12 diagrams; it does not authorize implementation or provisioning.
- CI/CD & Quality Gates is the fifteenth authoritative Approved Baseline (original date and approved 2026-09-16). It is authoritative for GitHub/GitHub Actions/public GHCR, source-control and protected-main policy, reproducible standard x64/native ARM64 workflows, zero-cost billing guards, least-privilege fork-safe automation, Testing Strategy orchestration, merge/release/manual gates, security scans, build-once digest-bound provenance, bounded evidence, and human OCI promotion; it does not authorize Git/GitHub initialization, workflows, implementation, or deployment.
- Observability is the sixteenth authoritative Approved Baseline (original date and approved 2026-09-17). It is authoritative for the Actuator/Micrometer and structured-logging foundation, private OCI-agent-scraped Prometheus-format exposition without a Prometheus server/TSDB, application/platform signal separation, bounded-cardinality and private-data exclusions, health and internal SLO/error-budget semantics, seven dashboards, 27 alerts, 17 structured events, 12 diagrams, zero-cost OCI observability, truthful log retention, and the measured-need gate for future tracing; it does not authorize implementation, configuration, provisioning, or the Implementation Roadmap.
- Implementation Roadmap is the seventeenth authoritative Approved Baseline (original date and approved 2026-09-17; Document 17). It is authoritative for the 15 dependency-ordered Phase 0–14 vertical slices, continuous testing/security/observability/CI accrual, exact relation/API/route/module/Product/Domain/Threat coverage, implementation-entry gates, STOP AND REVIEW boundaries, zero-cost/Gemini-first sequencing, and post-Phase-14 non-blocking Groq-hosted evaluation from a local harness; it does not authorize implementation.
- AI/knowledge remains inside the monolith initially.
- PostgreSQL/pgvector is the source-of-truth and retrieval foundation.
- Durable background work uses PostgreSQL-backed jobs.
- Redis is recommended only for transient deduplication, distributed rate controls, and measured caching.
- RabbitMQ, Elasticsearch/OpenSearch, Kubernetes, and an API gateway are not needed.
- Browser authentication uses revocable server-side sessions, not browser-stored JWTs.
- Google OIDC and optional-per-user TOTP MFA are Target Flagship capabilities.
- S3-compatible storage is required for sanitized avatars, private note attachments, and deliberately approved public derivatives.
- Public notes use explicit snapshot-bearing `Publication` records derived from note versions.
- AI exclusion is distinct from encryption and visibility.
- A complete E2EE Vault is optional/future, not part of the committed target.
- Retrieval must distinguish ranked relevance, focused fact retrieval, corpus-wide semantic extraction/aggregation, and corpus-wide deterministic extraction while routing from the user's requested outcome.
- Authorization is applied before every retrieval path.
- Saved URL extraction never implies remote fetching.
- Editor-owned content uses deliberate explicit Save; explicit commands may persist directly.
- Private-only users do not need a public handle, but first publication/public-profile activation requires a unique public handle.
- New accounts default future notes to AI OFF; Create Note initializes its AI control from that default while allowing a per-note override.
- Each note persists an independent AI ON/OFF state, and changing the future-note default never changes existing notes.
- Existing notes can be changed individually or through deliberate bulk enable/disable actions that do not alter the future-note default.
- Informed provider disclosure is required before the first actual AI processing, regardless of how a note becomes AI ON; attachment AI access follows the parent note.
- Bounded multimodal note attachments are Target Flagship; unlimited/general file storage is not.
- Cross-modal rediscovery and media-grounded reasoning are distinct product behaviors.
- Deterministic sensitive-content warnings remain Optional / Future.
- Bounded version history remains required, while its exact retention count/duration is a downstream policy parameter.
- Retrieval/AI maturity requires reproducible task-specific evaluation with an at-least-90% engineering ambition where appropriate; security paths require zero tested leaks.

### Deliberately open for downstream design

- Exact direct dependency declarations and build-file coordinates consistent with the approved dependency/BOM strategy.
- Exact module package/API boundaries.
- Detailed domain entities and schema.
- Chat and embedding providers/models and vector dimensions.
- Evaluation-tunable chunking parameters, optional reranker adoption, query-planning budgets, and justified evaluation thresholds within the approved Search / AI / Retrieval baseline.
- Search/index update latency objectives and retry policies.
- Exact implementation-time provider/model configuration and final user-facing disclosure wording within the approved privacy and deployment boundaries.
- Exact supported media formats, sizes, duration/page limits, quotas, and public-media support.
- Exact cross-modal embedding, transcription/extraction, multimodal reasoning, and capability-routing choices.
- Exact offline-evaluation datasets, task metrics, and justified quality thresholds.
- Precise Trending formula and view-deduplication rules.
- Any future production-infrastructure vendor/topology change beyond the approved initial OCI deployment requires an explicit downstream decision or baseline amendment as appropriate.
- Whether any Optional/Future capability is promoted through a separately approved scope decision.

Open implementation details are not permission to revisit the product's security and privacy invariants casually.

---

## 22. Guidance for future contributors and agents

Before acting:

1. Read this document in full.
2. Inspect only the current workspace and current Git state unless the user expands scope.
3. Treat files and uncommitted changes already present as user-owned.
4. Identify which decision/document phase is active.
5. Preserve the clean-rebuild and public-safety boundaries.
6. Separate product requirements from architectural mechanisms and from implementation sequencing.
7. Surface conflicts with this handoff rather than silently choosing a different product.

Do not:

- Generate the whole documentation set in one unreviewable pass.
- Scaffold code before the required design decisions and compatibility checks.
- Inflate the architecture to maximize technology count.
- Let AI features bypass ownership or AI-eligibility checks.
- Conflate AI exclusion, private visibility, encrypted-at-rest storage, and E2EE.
- Claim deterministic completeness from top-K semantic retrieval.
- Turn public discovery into a social network.
- Log note bodies, model prompts/responses, secrets, recovery codes, TOTP secrets, session identifiers, or sensitive URL content.

When tradeoffs arise, optimize first for user trust and correctness, then for a polished note-taking experience, then for portfolio clarity. Technology novelty is subordinate to those goals.

---

## 23. Next approved step

The **Product Vision and Target Flagship Requirements**, **ADR-001 — Architecture Style and Deployables**, **High-Level Architecture**, **Technology Stack and Compatibility**, amended **Security Architecture**, **Threat Model**, **Domain Model**, amended **Schema & Migration Design**, **Search / AI / Retrieval Design**, amended **API Design**, **Backend Low-Level Design**, **Frontend Low-Level Design**, **Testing Strategy**, **Deployment & Operations**, **CI/CD & Quality Gates**, **Observability**, and **Implementation Roadmap** are seventeen Approved Baselines. The Technology Stack's mandatory implementation-time smoke-test constraints, the Security Architecture's release-blocking isolation rules and approved narrow delivery-material exception, the Threat Model's 55 stable threats and release-blocker forward traces, the Domain Model's 50 stable invariants, the amended Schema & Migration Design's authoritative 38-relation catalog and authorization-scope constraints, the Search / AI / Retrieval Design's authorization-before-candidate, AI/provider, exact-vector, public/private, provenance, and evaluation obligations, the amended API Design's 91-endpoint external HTTP/security/error/concurrency contracts, the Backend LLD's approved implementation structure and boundaries, the Frontend LLD's approved browser architecture and interaction boundaries, the Testing Strategy's approved evidence model and release-blocking test obligations, Deployment & Operations' approved topology, cost, provider, persistence, recovery, health, and runbook obligations, CI/CD & Quality Gates' approved source-control, workflow, gate, provenance, and manual-promotion obligations, Observability's approved runtime signal, privacy, health, SLO, dashboard, alert, retention, zero-cost, and tracing boundaries, and the Implementation Roadmap's approved dependency ordering, continuous accrual, implementation-entry gates, exhaustive coverage ledgers, STOP AND REVIEW policy, and zero-cost/Gemini-first/Groq-later sequence remain binding.

The Schema & Migration Design security-email amendment is **Approved Baseline**. Exactly one Identity-owned technical relation, `identity.security_email_delivery`, was added; Identity now owns 11 relations and the authoritative catalog total is 38. No Domain Model amendment occurred, all `DM-INV-001` through `DM-INV-050` remain binding, and every Threat Model release blocker remains binding.

Backend Low-Level Design is **Approved Baseline**. Original date: **2026-09-13**. Last revised: **2026-09-14**. Baseline approval date: **2026-09-14**. It maps the finalized 91-endpoint API and 38-relation Schema baselines into the approved one-deployable, seven-module backend while preserving all upstream product, security, domain, retrieval, and threat-model authority.

Frontend Low-Level Design is **Approved Baseline**. Original date: **2026-09-14**. Baseline approval date: **2026-09-15**. It maps 27 browser routes and all 91 API endpoints into the approved React/Vite SPA while preserving memory-only private state, strong concurrency and saved-state boundaries, strict private/public separation, viewer-conditioned cache isolation, hardened rendering, accessibility, and narrow moderation behavior.

Testing Strategy is **Approved Baseline**. Original date: **2026-09-15**. Baseline approval date: **2026-09-16**. It maps 234 functional requirements, 47 non-functional requirements, `AS-01` through `AS-40`, all 50 Domain invariants, all 55 Threat Model rows, all 38 relations, 91 sequential API endpoints, and 27 sequential frontend routes into the approved seven-layer executable-evidence model while preserving zero-tolerance isolation, controlled concurrency, independent AI-state/acknowledgement semantics, synchronous initial Attachment validation, deterministic retrieval evaluation, focused Playwright/accessibility coverage, and the flaky-test release policy.

Deployment & Operations is **Approved Baseline**. Original date: **2026-09-16**. Baseline approval date: **2026-09-16**. It defines the zero-cost OCI A1/ARM64 one-VM production target, one application replica/deployable with packaged same-origin SPA, PostgreSQL/pgvector authority and recovery, transient Redis, S3-compatible object storage, Brevo SMTP, Google OIDC hostname readiness, Gemini-first/Groq-later provider rollout, zero-cost/outbound-transfer controls, truthful degradation, graceful restart/reclaim, 21 operational runbooks, and 12 Mermaid diagrams without authorizing implementation or provisioning.

CI/CD & Quality Gates is **Approved Baseline**. Original date: **2026-09-16**. Baseline approval date: **2026-09-16**. It defines GitHub/GitHub Actions/public GHCR, protected-main and fork-safe workflow policy, zero-cost hard billing guards, deterministic Testing Strategy orchestration, security and architectural gates, a build-once native ARM64 image with digest-bound verified attestation, and human-authorized OCI promotion. It contains 19 quality-gate entries, five workflow classes, and 12 Mermaid diagrams without authorizing Git initialization, workflow creation, implementation, or deployment.

Observability is **Approved Baseline**. Original date: **2026-09-17**. Baseline approval date: **2026-09-17**. It defines the approved Actuator/Micrometer, structured-log, OCI agent/Monitoring/Logging/Dashboard/Alarm/Notification/synthetic, bounded-cardinality, correlation, health, SLO/error-budget, privacy, retention, alert/runbook, zero-cost, and tracing-deferment boundaries. It contains seven dashboards, 27 alerts, 17 structured event classes, and 12 Mermaid diagrams without authorizing implementation or provisioning.

Implementation Roadmap is **Approved Baseline** and the seventeenth authoritative baseline. Original date: **2026-09-17**. Baseline approval date: **2026-09-17**. Document: **17**. It preserves 15 initially NOT STARTED phases, vertical-slice delivery, continuous testing/security/observability/CI accrual, exact approved coverage counts, and the required implementation-entry sequence without authorizing implementation.

The approved security-email relation remains narrow technical Identity work: the one-way capability verifier is authoritative; temporary token material is protected and non-authoritative; `security_event_id` is technical correlation/deduplication only; reclaimed work is lease-token fenced; ready/reclaim paths are separate; provider I/O is outside Identity transactions; exactly-once external delivery is not promised; both email-change destinations are event-bound and protected; and no generic notification system exists.

`AGENTS.md` is **Accepted**. Original date: **2026-09-17**. Acceptance date: **2026-09-17**. Role: **Implementation Agent Operating Contract**. It remains subordinate to all seventeen Approved Baselines and is not an eighteenth Approved Baseline.

Roadmap Phase 0 is **COMPLETE**. Human completion approval date: **2026-09-19**. The Phase-0 evidence remains implementation evidence, not an eighteenth Approved Baseline or a semantic baseline amendment.

Roadmap Phase 1 is **IN PROGRESS — READY FOR HUMAN COMPLETION REVIEW** after successful protected merge and exact-main verification of Work Package 1C-B. Human review has **ACCEPTED Work Package 1A — Repository / Toolchain / Minimal Architecture Bootstrap**, **ACCEPTED Work Package 1B — Database / Container / Compatibility Qualification**, and **ACCEPTED Work Package 1C-A — Local CI / Security / Observability Pre-Publication Preparation**. **Work Package 1C-B — First Publication / Live GitHub CI / Protected Main is COMPLETE — PENDING HUMAN REVIEW** upon the successful protected squash merge and post-merge exact-main checks described in the current-state record above. Phases 2 through 14 remain **NOT STARTED**. No Product behavior, Product relation, Product API endpoint, Product browser route, release/deployment, GHCR publication, OCI resource, live provider, or Phase 2 work was authorized or begun.
