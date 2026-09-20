# Notes & Knowledge Workspace
# Technology Stack and Compatibility

**Status:** Approved Baseline  
**Date:** 2026-09-10  
**Verification date:** 2026-09-10  
**Baseline approval date:** 2026-09-10

## 1. Purpose and authority

This document freezes the initial implementation technology baseline and records the evidence that its parts can coexist. It is subordinate to the approved Product Vision and Target Flagship Requirements, ADR-001, and High-Level Architecture. It does not change their product behavior, module ownership, deployable count, persistence model, or deferred decisions.

“Selected baseline” means the starting point for implementation, not a permanent prohibition on upgrades. Versions described as managed are visibility information: the owning BOM or platform should select them unless a documented incompatibility requires an explicit override.

This document does not authorize implementation and does not design the domain model, schemas, APIs, security flows, retrieval algorithms, vector indexes, media pipeline, UI/editor architecture, deployment topology, CI workflows, or implementation phases.

## 2. Baseline decisions

- Use Java 25 without preview features and Spring Boot 4.1.1.
- Use Maven 3.9.16 as the single backend build tool. Commit Maven Wrapper during implementation, targeting the selected Maven line; the current wrapper utility release verified for that future setup is 3.3.3.
- Let the Spring Boot 4.1.1 dependency-management platform own its ecosystem. Import the Spring Modulith 2.1.1 and Spring AI 2.0.1 BOMs for their modules.
- Use PostgreSQL 18.6 with pgvector 0.8.6 initially, while keeping production PostgreSQL current within the supported 18.x patch line.
- Use Flyway for all application-owned schema changes. Do not use Hibernate `ddl-auto` or Spring AI vector-store schema initialization to mutate shared application schema.
- Use Spring AI’s provider abstractions where their capabilities fit. Own a narrow Knowledge-module adapter for Gemini Embedding 2 multimodal embeddings because Spring AI 2.0.1’s documented Google GenAI embedding integration is text-only.
- Use Node.js 24.21.0 LTS and its bundled npm 11.19.0. Use one future `package-lock.json` and no second package manager or lockfile.
- Use the client-side React baseline in section 5. Do not introduce SSR, a full-stack React framework, Redux, React Compiler, or experimental/nightly toolchains by default.
- Treat provider and model choices as replaceable runtime configuration. Technical integration does not constitute privacy approval for real note content.
- Use only stable production releases in the initial baseline. The explicitly recorded documentation inconsistencies require narrow smoke tests, not preview dependencies or architecture changes.

## 3. Primary technology matrix

Classification meanings: **REQUIRED** implements an approved baseline; **RECOMMENDED** is the preferred initial choice; **OPTIONAL / CONDITIONAL** is added only when its use case is exercised; **CANDIDATE / EVALUATE** remains a downstream selection; **NOT NEEDED** is deliberately excluded from the initial stack.

| Technology | Role | Classification | Selected baseline | Version-management owner | Compatibility status | Reason | Evidence | Notes / constraints |
|---|---|---|---|---|---|---|---|---|
| Java | Backend language/runtime | REQUIRED | Java 25; verified current update 25.0.4.1 | JDK distribution/toolchain | VERIFIED | Approved language; LTS; Boot supports it | [J1], [J2], [B2] | No preview features; select a maintained JDK distribution during implementation |
| Spring Boot | Backend application platform | REQUIRED | 4.1.1 | Application baseline / Boot parent | VERIFIED | Current stable 4.1.x; supports Java 17–26 | [B1], [B2], [B3] | Clean Boot 4 project; no Boot 3 migration burden |
| Spring Framework | Core framework | REQUIRED, MANAGED | 7.0.9 | Spring Boot 4.1.1 | VERIFIED | Boot-managed foundation | [B2], [B4] | Do not pin independently |
| Maven | Backend build | REQUIRED | 3.9.16; future Wrapper utility 3.3.3 | Build tool / wrapper | VERIFIED | Clear BOM semantics, broad Spring examples, reproducible wrapper workflow | [M1], [M2], [M3] | Maven 4 is not the production baseline; do not add Gradle |
| Spring Modulith | Module verification and documentation | REQUIRED | 2.1.1 | Spring Modulith BOM 2.1.1 | VERIFIED WITH CONSTRAINT | Supports modular-boundary verification, module tests, and documentation | [SM1], [SM2], [SM3] | Tag POM aligns to Boot 4.1.1; published appendix is stale; smoke-test required |
| Spring Security | Servlet authentication/authorization, OAuth2/OIDC, CSRF | REQUIRED, MANAGED | 7.1.1 | Spring Boot 4.1.1 | VERIFIED | Meets approved session-based web security needs | [B4], [SEC1], [SEC2] | No browser JWT/localStorage design; no Authorization Server |
| Spring Session JDBC | PostgreSQL-backed browser sessions | REQUIRED, MANAGED | 4.1.1 | Spring Boot 4.1.1 | VERIFIED | Approved durable server-side session model | [B4], [SES1], [SES2] | JDBC/PostgreSQL only; not Redis sessions |
| Spring Data JPA | Relational persistence access | REQUIRED, MANAGED | 4.1.1 | Spring Data BOM 2026.0.1 via Boot | VERIFIED | Repository/unit-of-work support inside module ownership rules | [B4], [SD1] | Does not permit cross-module repository access |
| Hibernate ORM | JPA implementation | REQUIRED, MANAGED | 7.4.5.Final | Spring Boot 4.1.1 | VERIFIED | Boot-managed JPA runtime | [B4] | Flyway owns schema; no `ddl-auto` mutation |
| Hibernate Validator | Jakarta Validation implementation | REQUIRED, MANAGED | 9.1.3.Final | Spring Boot 4.1.1 | VERIFIED | Bean Validation implementation for request/domain boundaries | [B4] | Use Jakarta namespaces |
| Jackson | JSON binding | REQUIRED, MANAGED | 3.1.5 BOM line | Spring Boot 4.1.1 | VERIFIED | Boot 4’s preferred JSON generation | [B4], [B5] | Jackson 3 uses `tools.jackson` packages except annotations; do not import Boot 3/Jackson 2 assumptions |
| Embedded servlet container | HTTP runtime | REQUIRED, MANAGED | Tomcat 11.0.24; Servlet 6.1 | Spring Boot 4.1.1 | VERIFIED | Default MVC runtime | [B2], [B4] | Exact production proxy/domain/topology remains downstream |
| Spring Boot Actuator | Health and operational endpoints | REQUIRED, MANAGED | 4.1.1 starter | Spring Boot 4.1.1 | VERIFIED | Foundation for approved health/operability expectations | [B1] | Endpoint exposure and access control are downstream security/operations decisions |
| Flyway | Schema migration | REQUIRED, MANAGED | 12.4.0 plus PostgreSQL database module | Spring Boot 4.1.1 | VERIFIED | Versioned ownership of application schema; PostgreSQL 18 supported | [B4], [F1] | Add `flyway-database-postgresql`; design migrations later |
| PostgreSQL JDBC | Database driver | REQUIRED, MANAGED | 42.7.13 | Spring Boot 4.1.1 | VERIFIED | Current Boot-managed driver with PostgreSQL 18 work | [B4], [PGJ1] | Do not pin independently without a driver/security reason |
| PostgreSQL | Authoritative relational database | REQUIRED | 18.6 initially; policy: current supported 18.x patch | Infrastructure baseline | VERIFIED | Approved single physical database | [PG1], [PG2] | PostgreSQL 19 beta is excluded |
| pgvector | PostgreSQL vector extension | REQUIRED | 0.8.6 | Infrastructure baseline | VERIFIED | Stores vectors in the approved PostgreSQL database | [PV1], [PV2] | Extension, not another database; index type remains deferred |
| Spring AI | AI integration framework | REQUIRED | 2.0.1 | Spring AI BOM 2.0.1 | VERIFIED | Current stable line explicitly supports Boot 4.0.x and 4.1.x | [AI1], [AI2] | Provider/model IDs remain runtime configuration |
| Spring AI ChatClient / ChatModel | Generative provider abstraction | REQUIRED | Spring AI 2.0.1 modules | Spring AI BOM | VERIFIED WITH PROVIDER CONSTRAINTS | Common integration surface without erasing provider capability differences | [AI3], [AI4], [AI5] | Do not assume every provider supports every modality or option |
| Spring AI PgVectorStore | General vector-store integration | RECOMMENDED | Spring AI 2.0.1 module | Spring AI BOM | VERIFIED WITH CONSTRAINT | Useful for ordinary vector operations | [AI6] | Disable schema initialization; supplement with owned SQL/adapters for authorization-aware and specialized retrieval |
| Knowledge multimodal embedding adapter | Cross-modal embedding integration | REQUIRED for committed multimodal retrieval | Project-owned narrow adapter; Google client initially Spring-AI-managed 1.65.0 pending smoke test | Project-owned port; provider client stays replaceable | CUSTOM ADAPTER REQUIRED | Spring AI Google GenAI embedding integration is documented as text-only while Gemini API exposes multimodal embeddings | [AI7], [G1], [G2] | Internal to Knowledge; not a service or generic gateway; no key in source |
| Google GenAI client | Candidate Gemini provider client | OPTIONAL / CONDITIONAL | Start with Spring AI-managed `com.google.genai:google-genai` 1.65.0 | Spring AI 2.0.1 dependency management | VERIFIED WITH CONSTRAINT | Avoid an arbitrary client override before the stable model call is proven | [AI8] | Current direct SDK is newer; override only for a demonstrated missing API/fix |
| Testcontainers | Integration-test infrastructure | REQUIRED, MANAGED | 2.0.5 | Spring Boot 4.1.1 | VERIFIED | Reproducible real-service integration tests | [B4], [TC1] | Use pgvector PostgreSQL image; Redis/object storage only when exercised |
| JUnit Jupiter | Test framework | REQUIRED, MANAGED | 6.0.3 | Spring Boot 4.1.1 test stack | VERIFIED | Standard Boot test foundation | [B4] | Do not pin independently |
| Mockito | Test doubles | OPTIONAL / MANAGED | 5.23.0 when supplied by Boot test | Spring Boot 4.1.1 | VERIFIED | Available where isolation is useful | [B4] | No independent dependency/version unless actually needed |
| springdoc-openapi | OpenAPI generation/UI | RECOMMENDED | 3.1.1 | Explicit application dependency | VERIFIED WITH CONSTRAINT | Current 3.x line targets Boot 4 | [OA1], [OA2] | Smoke-test Boot 4.1.1/Jackson 3; do not use springdoc 2.x |
| Redis server | Transient coordination/cache support | OPTIONAL / CONDITIONAL | 8.10.x controlled patch; current verified security-patched release 8.10.1 on 2026-09-10 | Infrastructure baseline | VERIFIED | Supports approved rate-limit, short-lived dedupe, and measured cache roles | [R1], [R2] | Reverify and use the current supported security patch in the compatible 8.10.x line when introduced; never authoritative; no durable jobs, Streams architecture, or sessions |
| Spring Data Redis / Lettuce | Redis client integration | OPTIONAL / MANAGED | Spring Data Redis 4.1.1; Lettuce 7.5.2.RELEASE | Spring Boot 4.1.1 | VERIFIED | Boot-managed client stack if Redis is introduced | [B4], [SD1] | Add only when a measured transient use case exists |
| AWS SDK for Java 2 S3 | S3-compatible object-storage client | RECOMMENDED | BOM 2.54.15; depend only on required S3 modules | AWS SDK BOM | VERIFIED WITH PROVIDER CONSTRAINT | Stable Java S3 API client with endpoint configuration | [S31], [S32], [S33] | S3 API is not an AWS vendor commitment; vendor and upload design deferred |
| Spring Boot mail / Jakarta Mail | Replaceable email integration | REQUIRED capability, CONDITIONAL dependency | Boot starter; Angus Mail 2.0.5 / Jakarta Mail 2.1.5 managed | Spring Boot 4.1.1 | VERIFIED | Standard `JavaMailSender` boundary supports managed providers | [MAIL1], [B4] | Production vendor, templates, bounce handling, and credentials deferred |
| React / React DOM | Browser UI | REQUIRED | 19.3.0 / 19.3.0 | npm lockfile | VERIFIED WITH FRESH-RELEASE SMOKE TEST | Approved client-side UI platform; current stable release | [RE1], [NPM] | No React Compiler or experimental features by default |
| TypeScript | Frontend language | REQUIRED | 7.0.2 | npm lockfile | VERIFIED WITH SMOKE TEST | Current stable compiler | [TS1], [NPM] | Strict direction; exact `tsconfig` deferred; no nightly |
| Node.js | Frontend tool runtime | REQUIRED | 24.21.0 LTS (Krypton) | Toolchain baseline | VERIFIED | Active LTS satisfying Vite, Router, and Playwright constraints | [N1], [N2] | Use a pinned setup in local/CI environments, not “latest” |
| npm | Frontend package manager | REQUIRED | 11.19.0 bundled with Node 24.21.0 | Node distribution / `package-lock.json` | VERIFIED | Single familiar package manager and reproducible lockfile | [N2] | Do not independently jump to npm 12 or add pnpm/yarn lockfiles |
| Vite / React plugin | Frontend build/dev tool | REQUIRED | Vite 8.2.2; `@vitejs/plugin-react` 6.1.1 | npm lockfile | VERIFIED | Current stable Vite release; Node 24 satisfies minimums | [V1], [V2], [NPM] | Vite 8.3.0-beta.1 is a prerelease and is not selected; does not decide frontend hosting |
| TanStack Query | Remote/server state | REQUIRED | 5.102.8 | npm lockfile | VERIFIED | Current v5; React 19 peer support | [TQ1], [NPM] | Not a replacement for all local UI state; no Redux by default |
| React Router | Client-side routing | REQUIRED | 8.3.1 | npm lockfile | VERIFIED | Current stable line; supports React 19 and Node 24 | [RR1], [RR2], [NPM] | Use `react-router` / `react-router/dom`; v8 removed `react-router-dom`; no framework/SSR mode requirement |
| React Hook Form | Structured form state/validation | RECOMMENDED | 7.87.0 | npm lockfile | VERIFIED | React 19-compatible, suitable for auth/account/settings forms | [RHF1], [NPM] | Do not force editor interactions through form abstractions |
| Vitest | Frontend unit/component test runner | RECOMMENDED | 5.0.0 | npm lockfile | VERIFIED WITH SMOKE TEST | Fits Vite toolchain | [NPM] | Exact coverage/test topology belongs to Testing Strategy |
| Testing Library | Accessible UI tests | RECOMMENDED | React 16.3.3; DOM 10.4.1; user-event 14.6.7; jest-dom 7.0.1 | npm lockfile | VERIFIED | User-observable component testing | [NPM] | Use with a DOM environment such as jsdom 30.0.1 |
| Playwright | Browser/end-to-end tests | REQUIRED | 1.63.0 | npm lockfile plus managed browser binaries | VERIFIED | Supports current Node LTS lines and real browser automation | [PW1], [NPM] | Browser cache/version handling belongs to CI design |
| Markdown rendering | Safe Markdown display | REQUIRED | `react-markdown` 10.1.0 + `remark-gfm` 4.0.1; `rehype-sanitize` 6.0.0 only if an HTML/plugin path needs it | npm lockfile | VERIFIED WITH SECURITY CONSTRAINT | Maintained parser/render pipeline with GFM support | [MD1], [MD2], [MD3], [NPM] | Raw HTML disabled by default; sanitize after any unsafe transformation |
| Markdown editor | Authoring component | CANDIDATE / EVALUATE | No library frozen | Frontend LLD | UNRESOLVED BY DESIGN | UX, accessibility, bundle, and React 19 fit require focused evaluation | [MD1] | Shortlist maintained Markdown-focused editors / CodeMirror 6 approach; do not turn renderer into editor architecture |
| Docker Engine / Compose | Local and CI containers | REQUIRED tooling | Verified 29.8.0 / Compose 5.5.1; use supported current releases | Developer/CI environment | VERIFIED | Practical for PostgreSQL/pgvector and integration dependencies | [D1], [D2] | Do not pin every runtime implementation; controlled service image tags, never `latest` |
| GitHub Actions | CI/CD platform | REQUIRED direction | Hosted workflow platform; action revisions selected later | CI design | VERIFIED | Supports Java/Maven, Node, containers, and service containers | [CI1], [CI2] | No workflow is designed here |
| RabbitMQ, API gateway, Kubernetes, separate AI service, separate worker | Excluded initial infrastructure/deployables | NOT NEEDED | None | ADR-001 | NOT APPLICABLE | Approved modular-monolith baseline does not require them | Approved ADR-001 | Addition requires a superseding approved ADR where applicable |

## 4. Important Spring Boot-managed versions

The following are recorded for compatibility visibility from Spring Boot 4.1.1 dependency management. They are not recommendations to repeat versions on individual dependencies.

| Managed component | Boot 4.1.1 value | Application action |
|---|---:|---|
| Spring Framework | 7.0.9 | Inherit |
| Spring Security | 7.1.1 | Inherit |
| Spring Session | 4.1.1 | Inherit |
| Spring Data BOM | 2026.0.1 | Inherit |
| Spring Data JPA / Redis | 4.1.1 / 4.1.1 | Inherit when used |
| Hibernate ORM | 7.4.5.Final | Inherit |
| Hibernate Validator | 9.1.3.Final | Inherit |
| Jackson BOM | 3.1.5 | Inherit |
| Flyway | 12.4.0 | Inherit; add PostgreSQL-specific module |
| PostgreSQL JDBC | 42.7.13 | Inherit |
| Testcontainers | 2.0.5 | Inherit |
| JUnit Jupiter | 6.0.3 | Inherit through Boot test support |
| Mockito | 5.23.0 | Inherit if used |
| Lettuce | 7.5.2.RELEASE | Inherit if Redis is used |
| Tomcat | 11.0.24 | Inherit |
| Angus Mail / Jakarta Mail | 2.0.5 / 2.1.5 | Inherit through mail starter |

Source: the published Spring Boot 4.1.1 dependency POM [B4], with Spring Data detail from its managed BOM [SD1]. Security, persistence, test, servlet, JSON, and mail components must not be individually overridden merely to reproduce this table.

## 5. Frontend exact baseline

The initial frontend set is:

- React 19.3.0 and React DOM 19.3.0;
- `@types/react` 19.3.0 and `@types/react-dom` 19.3.0;
- TypeScript 7.0.2, stable only;
- Vite 8.2.2 and `@vitejs/plugin-react` 6.1.1;
- TanStack Query 5.102.8 for remote/server state;
- React Router 8.3.1 for client-side routing;
- React Hook Form 7.87.0 for structured forms;
- Vitest 5.0.0, Testing Library React 16.3.3, DOM 10.4.1, user-event 14.6.7, jest-dom 7.0.1, and jsdom 30.0.1 for future unit/component testing;
- Playwright 1.63.0 for future browser-level testing;
- `react-markdown` 10.1.0 and `remark-gfm` 4.0.1 for rendering; `rehype-sanitize` 6.0.0 when an explicitly enabled HTML/plugin pipeline makes sanitization necessary.

React 19.3 was released on 2026-09-09, immediately before this verification date [RE1]. TypeScript 7.0 and several frontend point releases are similarly current. Their registry metadata and peer ranges form authoritative package evidence [NPM], but their freshness makes a clean install/build/type-check test mandatory before substantial UI work. On the 2026-09-10 verification date, npm identifies Vite 8.2.2 with the `latest` stable tag and Vite 8.3.0-beta.1 with the `beta` prerelease tag [V2]. The project therefore deliberately selects Vite 8.2.2 and does not select the 8.3 beta. No experimental feature is needed.

React Router 8 requires React 19.2.7 or newer and Node 22.22.0 or newer; Node 24.21.0 and React 19.3.0 satisfy those constraints [RR1]. Version 8 removes `react-router-dom`; browser integrations come from `react-router` and `react-router/dom`. The application should use Declarative or Data mode and should not infer Framework mode, SSR, or a hosting topology from the routing library [RR2].

Node 24 is an active LTS line and 24.21.0 includes npm 11.19.0 [N1], [N2]. This also exceeds Vite’s documented modern Node minimum [V1]. The future repository should have one root frontend package-management policy and one committed `package-lock.json`; CI should use `npm ci`.

## 6. Compatibility matrix

| Relationship | Result | Evidence and constraint |
|---|---|---|
| Java 25 ↔ Spring Boot 4.1.1 | VERIFIED | Boot requires Java 17 and supports through Java 26 [B2] |
| Java 25 ↔ Maven 3.9.16 | VERIFIED | Maven current stable on supported Java; compilation toolchain targets Java 25 [M1], [M2] |
| Spring Boot 4.1.1 ↔ Spring Framework 7.0.9 | VERIFIED | Boot system requirements and dependency management [B2], [B4] |
| Spring Boot 4.1.1 ↔ Spring Security 7.1.1 | VERIFIED | Boot-managed; servlet OAuth2 login and CSRF documented [B4], [SEC1], [SEC2] |
| Spring Boot 4.1.1 ↔ Spring Session JDBC 4.1.1 | VERIFIED | Boot-managed starter and JDBC repository documented [B4], [SES1], [SES2] |
| Spring Boot 4.1.1 ↔ Spring Data JPA 4.1.1 / Hibernate 7.4.5 | VERIFIED | Boot/Spring Data managed versions [B4], [SD1] |
| Spring Boot 4.1.1 ↔ Spring Modulith 2.1.1 | VERIFIED WITH CONSTRAINT | 2.1.1 tag POM aligns to Boot 4.1.1, but reference appendix omits the 2.1 line [SM2], [SM3] |
| Spring Boot 4.1.1 ↔ Spring AI 2.0.1 | VERIFIED | Spring AI 2.0.x explicitly supports Boot 4.0.x and 4.1.x [AI1], [AI2] |
| Spring Boot 4.1.1 ↔ Testcontainers 2.0.5 | VERIFIED | Boot-managed version and official database module [B4], [TC1] |
| Spring Boot 4.1.1 ↔ springdoc 3.1.1 | VERIFIED WITH CONSTRAINT | springdoc 3.x is the Boot 4 generation; current documentation’s exact table lags at 4.0.x, so Boot 4.1.1/Jackson 3 smoke test remains [OA1], [OA2] |
| PostgreSQL 18.6 ↔ pgJDBC 42.7.13 | VERIFIED | Current PostgreSQL 18 patch and driver release with PostgreSQL 18 support work [PG1], [PGJ1] |
| PostgreSQL 18.6 ↔ pgvector 0.8.6 | VERIFIED | pgvector supports PostgreSQL 13+ and publishes PG18 image/package variants [PV1], [PV2] |
| PostgreSQL 18.6 ↔ Flyway 12.4.0 | VERIFIED | Flyway’s PostgreSQL support matrix verifies PostgreSQL 18 and requires its database module [F1] |
| PostgreSQL/pgvector ↔ Testcontainers 2.0.5 | VERIFIED | PostgreSQL module documents `pgvector/pgvector` as compatible [TC1] |
| Spring AI 2.0.1 ↔ PgVectorStore | VERIFIED WITH CONSTRAINT | Official module exists; project schema and specialized retrieval remain project-owned [AI6] |
| Spring AI 2.0.1 ↔ Google GenAI chat | VERIFIED WITH MODEL CONSTRAINTS | Google GenAI chat supports text, PDF, image, audio, and video subject to the configured model [AI3], [AI4] |
| Spring AI 2.0.1 ↔ Google GenAI embeddings | VERIFIED FOR TEXT ONLY | Spring AI’s integration page explicitly documents text embeddings only and multimodal support as pending [AI7] |
| Gemini API ↔ Gemini Embedding 2 multimodal embeddings | VERIFIED | Dedicated model and embedding docs support text, image, audio, video, and PDF in one space [G1], [G2] |
| Spring AI embedding abstraction ↔ required Gemini multimodal behavior | CUSTOM ADAPTER REQUIRED | Framework integration does not currently expose the committed provider behavior [AI7], [G1] |
| React 19.3 ↔ TanStack Query 5.102.8 | VERIFIED | Package peer metadata permits React 18/19 [TQ1], [NPM] |
| React 19.3 ↔ React Router 8.3.1 | VERIFIED | Router requires React 19.2.7+ [RR1], [NPM] |
| React 19.3 ↔ React Hook Form 7.87.0 | VERIFIED | Peer range includes React 19 [RHF1], [NPM] |
| TypeScript 7.0.2 ↔ selected frontend packages | VERIFIED WITH SMOKE TEST | Stable compiler and current type packages; fresh baseline requires clean type-check [TS1], [NPM] |
| Vite 8.2 ↔ Node 24.21 LTS | VERIFIED | Node 24 exceeds Vite’s supported minimum [V1], [N1] |
| Playwright 1.63 ↔ Node 24.21 LTS | VERIFIED | Playwright supports current Node LTS generations [PW1] |

## 7. Spring Boot 4 implications

This is a new application, so it should begin directly with Jakarta EE 11-era APIs, Servlet 6.1, Spring Framework 7, and Jackson 3 conventions. No compatibility layer or Boot 3 migration work is justified. Source examples copied from older tutorials must be checked for `javax.*` imports, Jackson 2 package names, removed Boot properties, and outdated springdoc/Security configuration [B5], [B6].

The embedded-container baseline is Boot-managed Tomcat 11. This does not decide whether browser assets are served by the Spring Boot deployable or hosted separately; that remains the downstream deployment decision already preserved by ADR-001.

## 8. Spring Modulith compatibility finding

Spring Modulith 2.1.1 is the selected stable release. The strongest version-specific evidence is the official `2.1.1` tag POM, which declares Spring Boot 4.1.1 and Spring Framework 7.0.9 [SM2]. The official 2.1.1 reference site is current, and the release announcement confirms the release [SM1], [SM3].

However, the reference appendix’s compatibility table has not been updated consistently: it lists earlier generations and snapshots but omits a 2.1 row. This is an official-documentation discrepancy, not evidence that the tag POM is incompatible. Confidence is high enough to select 2.1.1, with a mandatory implementation-time smoke test.

Initial uses are limited to:

- module-boundary verification;
- module-focused tests;
- architecture documentation/diagram generation where useful.

Do not add event externalization, a broker integration, Kafka, RabbitMQ, runtime insights, or every starter by default. Add only the Modulith modules needed for these stated capabilities.

## 9. Database, migrations, vectors, and integration tests

PostgreSQL 18.6 is the current 18.x patch release on the verification date [PG1]. Production must track supported security and corrective patches within 18.x; `18.6` is the reproducible starting point, not an instruction to remain indefinitely on that patch. PostgreSQL 19 beta/development releases are not an acceptable production baseline.

pgvector 0.8.6 is a PostgreSQL extension, not a standalone vector database. It supports PostgreSQL 18 and provides controlled PG18 container variants [PV1], [PV2]. A future local/test image may begin with `pgvector/pgvector:0.8.6-pg18-trixie`, then be digest-pinned when build infrastructure is created. No `latest` tag should be used.

The Java integration approach is deliberately mixed rather than forced through one abstraction:

- use Spring AI PgVectorStore where its general text-vector store operations and metadata filters fit;
- use JPA and owned native SQL for relational ownership, authorization-aware queries, hybrid retrieval, and PostgreSQL-specific operations;
- add a small project-owned vector adapter only where Spring AI/JPA do not express a required operation cleanly.

This does not select HNSW versus IVFFlat, similarity metrics, dimensions, chunking, ranking, or retrieval algorithms. Those require Search/AI/Retrieval Design and measurement.

Flyway 12.4.0, managed by Boot, supports PostgreSQL 18, with the current Flyway generation requiring `org.flywaydb:flyway-database-postgresql` in addition to core [F1]. Flyway will own all application schema changes. Hibernate schema mutation and Spring AI PgVectorStore automatic initialization must be disabled for shared application schema.

Testcontainers 2.0.5 can start the controlled pgvector/PostgreSQL image through `PostgreSQLContainer` [TC1]. Redis may use a generic container only when Redis behavior is exercised. S3-compatible test infrastructure should be selected only when object-storage contract testing is designed; this document does not choose a local storage product.

## 10. Spring AI capability matrix

Support below means the framework/provider documentation exposes the integration; actual modality availability remains model-, account-, region-, and provider-dependent.

| Capability | Spring AI 2.0.1 path | Result | Constraint |
|---|---|---|---|
| Provider-neutral chat orchestration | `ChatModel` / `ChatClient` | VERIFIED | Provider-specific options must stay behind Knowledge-owned policy and configuration |
| Google GenAI text chat | Google GenAI Chat | VERIFIED | Stable model ID chosen later |
| Google GenAI multimodal chat | Google GenAI Chat media messages | VERIFIED | Docs list text, PDF, image, audio, and video; chosen model limits still apply |
| Google GenAI text embedding | Google GenAI Embedding | VERIFIED FOR TEXT | Spring AI integration currently documents text only |
| Google GenAI multimodal embedding | No complete Spring AI 2.0.1 integration documented | CUSTOM ADAPTER REQUIRED | Use Gemini API/SDK behind a narrow project port |
| Ollama chat | Ollama Chat | VERIFIED | Local model and hardware determine text/image capability |
| Ollama embedding | Ollama Embedding | VERIFIED WITH MODEL CONSTRAINT | Useful for local/synthetic development, not cross-provider vector compatibility |
| Groq chat | OpenAI-compatible integration | VERIFIED WITH PROVIDER/MODEL CONSTRAINT | Current Spring AI comparison indicates text/image, not the full committed modality set |
| PostgreSQL vector store | PgVectorStore | VERIFIED WITH CONSTRAINT | Not a substitute for owned authorization and exhaustive/hybrid retrieval logic |

The key distinction is explicit: multimodal **chat** support does not imply multimodal **embedding** support. Spring AI’s Google GenAI chat documentation supports media input [AI4], while its Google GenAI embedding page says the integration supports text embeddings only and that multimodal embedding support is pending [AI7]. The latter wording attributes the limitation to the SDK, but Google’s Gemini API documentation independently exposes multimodal Embedding 2. The implementation conclusion is based on the observable integration surface: Spring AI 2.0.1 cannot be assumed to provide the required multimodal embedding behavior.

## 11. Gemini Embedding 2 documentation reconciliation

### 11.1 Official-source comparison

| Official source | Visible release/update evidence | Identifier/status conveyed | Relevant capability |
|---|---|---|---|
| Dedicated Gemini Embedding 2 model page [G1] | Page last updated 2026-04-28 UTC | `gemini-embedding-2`, stable | Text, image, audio, video, PDF; 128–3072 output dimensions |
| General embeddings guide [G2] | Current page verified 2026-09-10 | `gemini-embedding-2`, stable | Cross-modal inputs in a unified embedding space; recommended dimensions 768, 1536, 3072 |
| Gemini model catalog [G3] | Page last updated 2026-08-26 UTC | Still presents `gemini-embedding-2-preview` | Conflicts with the dedicated and release documentation |
| Gemini API release notes [G4] | Page last updated 2026-09-04 UTC | Preview released 2026-03-10; stable `gemini-embedding-2` GA released 2026-04-22 | Establishes stable release chronology |
| Gemini deprecations page [G5] | Verified 2026-09-10 | No deprecation/shutdown entry for stable Embedding 2 | Model is not documented as deprecated |

### 11.2 Conclusion

The production identifier supported by the strongest and most specific current evidence is **`gemini-embedding-2`**. Confidence is **high** because the dedicated model page, current embeddings guide, and dated GA release notes agree. The model catalog’s preview entry is treated as stale because it conflicts with those three sources despite its later page update date.

A runtime smoke test remains mandatory before the integration string is frozen in configuration. It must verify model discovery/access and a stable `embedContent` request in the project’s account/region/tier. This protects the implementation from documentation/catalog propagation lag and from the Spring AI-managed Google client’s older surface. It is not permission to fall back silently to a preview identifier.

### 11.3 Current documented limits

Google currently documents:

- a shared maximum input context of 8,192 tokens for Embedding 2;
- text input up to 8,192 tokens;
- up to 6 PNG/JPEG images per request;
- audio up to 180 seconds in MP3 or WAV;
- video up to 120 seconds in MP4 or MOV, with documented H.264/H.265/AV1/VP9 constraints, up to 32 sampled frames, and without using the video audio track;
- one PDF per request, up to 6 pages, with one page recommended and silent truncation beyond provider limits;
- configurable output dimensions from 128 to 3,072, with 768, 1,536, and 3,072 recommended.

These are provider request limits, not the application’s final upload or processing policy. The eventual media pipeline must validate and segment inputs deliberately rather than rely on silent provider truncation. No vector dimension is frozen here. Search/AI/Retrieval Design must select and measure it; every query and corpus vector within one index must use a compatible model/version/dimension.

## 12. Narrow multimodal embedding adapter

The initial boundary is conceptually:

```text
Knowledge module
  -> project-owned multimodal embedding port
  -> Google GenAI SDK or REST implementation
  -> validated embedding vector result
  -> PostgreSQL / pgvector persistence through owned boundaries
```

This adapter is required because forcing image/audio/video/PDF requests through a framework integration documented as text-only would conceal an important capability mismatch. The adapter should expose only project needs: supported input type, bytes/reference and metadata, task intent where supported, requested dimension, provider result, and typed failures. Exact interfaces, class names, retries, batching, and media preprocessing remain downstream.

The adapter:

- stays inside the Knowledge module and obeys authorization-before-retrieval and per-note AI participation decisions;
- is not a microservice, separate worker, generic AI gateway, or cross-module bypass;
- keeps the provider implementation replaceable;
- must not log note content, media, credentials, or raw sensitive provider payloads;
- must validate returned dimensions and model identity before persistence.

Embedding vectors are model-, version-, task-, and dimension-specific. Changing the embedding model or incompatible configuration can require versioned indexes and full authorized-corpus re-embedding; vectors from unrelated spaces must not be mixed or compared as though compatible.

## 13. Provider feasibility and privacy classification

| Provider path | Technical use | Classification for data | Decision constraints |
|---|---|---|---|
| Ollama running locally | Local chat/embedding experiments; offline-capable development depending on model | **Local-only/private execution candidate**; suitable for synthetic and appropriately protected local development | Local execution means prompts are not sent to Ollama’s hosted service [P1]; model quality, modality, hardware, and operator security vary; it is not the product’s permanent provider identity |
| Google GenAI / Gemini unpaid services | Multimodal technical evaluation | **Synthetic development only** unless terms and approval say otherwise | Google states unpaid-service content may be used to improve products and may receive human review; do not submit confidential/private note data [P2] |
| Google GenAI / Gemini paid services | Multimodal chat and embedding production candidate | **Technically feasible; production candidate subject to tier, contract, region, retention/ZDR, DPA, and data-handling review** | Paid-service terms state prompts/responses are not used to improve products; abuse monitoring/retention and ZDR eligibility still matter [P2], [P3] |
| Groq-hosted inference | Low-latency hosted text/image-capable models where appropriate | **Technically feasible; production candidate subject to contract/tier/ZDR/region review** | Groq documents default inference retention exceptions, abuse/reliability handling up to 30 days, ZDR controls, and retained usage metadata [P4] |

No cloud provider is approved for real private note content merely because a framework can call it. Until a production privacy review approves the exact service, tier, region, account configuration, retention behavior, subcontractors, DPA, and incident/logging posture, use synthetic content only. Required provider disclosure and the approved per-note AI model still apply before actual AI processing.

Provider choice must remain configuration behind Knowledge-owned boundaries. Stable model identifiers may be pinned for a deployment after validation, but neither a Gemini, Ollama, Groq, nor any individual model name becomes permanent product identity. Provider aliases that can silently change behavior should not be used in production without controlled resolution and regression tests.

## 14. Markdown safety and editor decision

`react-markdown` renders a syntax tree rather than injecting arbitrary HTML and is safe by default when raw HTML is not enabled [MD1]. The initial rendering policy is:

- raw HTML off by default;
- GFM through `remark-gfm` where required;
- URL/protocol validation and safe link behavior;
- if a downstream requirement enables raw HTML or an unsafe transformation, apply a strict `rehype-sanitize` schema **after** unsafe plugins [MD2];
- test XSS payloads, malformed Markdown, links, images, and plugin interactions before enabling any richer extension.

The exact Markdown editor is intentionally open for Frontend LLD. Candidate categories include a maintained CodeMirror 6-based Markdown editor and maintained Markdown-focused React editors. Selection needs direct evaluation of keyboard/screen-reader accessibility, mobile behavior, composition/IME, paste, large-document performance, controlled/uncontrolled integration, React 19 support, bundle cost, extensibility, and long-term maintenance. The rendering choice does not predetermine editor UX.

## 15. Replaceable external integrations

### 15.1 Object storage

Use the AWS SDK for Java 2.x S3 client behind an application-owned attachment/object-storage port, importing AWS SDK BOM 2.54.15 and only the needed modules [S31], [S33]. The SDK supports configurable endpoints [S32], so the technical baseline is the S3 API, not AWS as the sole production vendor. Provider, region, bucket names, object keys, encryption settings, path style, multipart thresholds, presigned requests, and upload flow remain downstream choices.

### 15.2 Email

Use Spring Boot’s mail integration and an application-owned email-delivery boundary [MAIL1]. Boot manages the underlying Jakarta Mail implementation. The production managed email provider remains replaceable; transport credentials are external secrets. Provider selection, templates, verification-link shape, bounce/complaint handling, and deliverability operations remain later work.

### 15.3 Redis

If measurements justify it, use Redis 8.10.x through Boot-managed Spring Data Redis/Lettuce for only transient rate limiting, short-lived deduplication, and measured caches. The current verified patched release on 2026-09-10 is Redis Open Source 8.10.1, which Redis classifies as an August 2026 security update [R2]. Because Redis is optional and may be introduced later, implementation must reverify and use the current supported security patch within the compatible 8.10.x line at the time it is introduced rather than permanently freezing 8.10.1. Redis is never the source of truth, session store, durable job store, or Streams-based job architecture. PostgreSQL remains authoritative and Spring Session remains JDBC-backed.

## 16. Dependency-management strategy

### Backend

1. Use the Spring Boot 4.1.1 parent or dependency-management BOM as the owner of Spring Framework, Security, Session, Data, Hibernate, Jackson, Flyway, pgJDBC, Testcontainers, JUnit, Mockito, Lettuce, Tomcat, mail, and other listed managed dependencies.
2. Import Spring Modulith BOM 2.1.1 and declare only the modules needed for verification, tests, and documentation.
3. Import Spring AI BOM 2.0.1 and declare only the provider/vector modules actually used.
4. Import AWS SDK BOM 2.54.15 only if/when object storage is implemented; depend on the S3 modules actually needed.
5. Give explicit versions only to direct dependencies outside those platforms, such as springdoc 3.1.1, or to a documented compatibility override. Record the reason for every override.
6. Do not add duplicate JSON, HTTP, logging, validation, persistence, test, or resilience libraries when the selected platform already supplies the required capability.

### Frontend

1. Use npm 11.19.0 through the selected Node 24.21.0 toolchain.
2. Commit exactly one `package-lock.json` when implementation begins; use reproducible clean installs in CI.
3. Declare intentional direct dependencies and allow the lockfile to record exact transitive resolutions.
4. Do not mix npm with pnpm/yarn lockfiles or rely on floating unreviewed versions.

No build file, manifest, wrapper, or lockfile is created by this document.

## 17. Version and supply-chain policy

- Freeze the selected major/minor/patch versions for the first compatibility branch and smoke-test them together.
- Apply security and corrective patch updates through a controlled change, dependency review, release-note review, and CI. PostgreSQL stays current within supported 18.x.
- Major/minor framework, database, Node, TypeScript, provider SDK, or persistent-format changes require explicit compatibility review and regression testing.
- Use official repositories and release artifacts. Do not use snapshots, milestones, release candidates, abandoned libraries, or unofficial container images in the production baseline unless an approved exception records why.
- Pin toolchains and controlled container tags; use digests for critical future CI/deployment images where maintainable. Never use `latest` as a reproducible environment contract.
- Keep provider model IDs and requested embedding dimensions explicit in deployment configuration. Do not allow an alias to silently change production behavior.
- Track model deprecations and shutdown dates; migrate through explicit corpus/query compatibility testing and re-embedding where needed.
- Commit no secrets. API keys, database passwords, OAuth credentials, object-storage credentials, and mail credentials belong in approved external secret/configuration mechanisms.
- Add dependency vulnerability and license review, secret scanning, artifact provenance/checksum practices, and container scanning when CI/security design is authorized. This document does not select a bot or workflow.

## 18. Implementation-time compatibility smoke-test plan

These tests are mandatory gates after implementation/scaffolding is separately authorized; they are not authorization to create code now.

1. Build and start the minimal Spring Boot 4.1.1 application with Java 25 and Maven 3.9.16; verify no preview features and expected Framework/Jackson/Tomcat generations.
2. Load the selected Spring Modulith 2.1.1 verification/test/documentation modules; run module-structure verification and one module test.
3. Import Spring AI BOM 2.0.1 alongside Boot 4.1.1 and Modulith 2.1.1; inspect the dependency graph for version overrides/conflicts.
4. Start `pgvector/pgvector:0.8.6-pg18-trixie` through Testcontainers 2.0.5; assert PostgreSQL 18.x and vector extension 0.8.6.
5. Run a minimal Flyway 12.4.0 migration through the PostgreSQL-specific module against that container; confirm Hibernate and PgVectorStore do not auto-create shared schema.
6. Perform a synthetic vector insert/query round trip using the selected Spring AI PgVectorStore path and, where needed, project-owned SQL; validate vector dimension errors fail visibly.
7. Generate OpenAPI using springdoc 3.1.1 on Boot 4.1.1/Jackson 3 and load the JSON/UI, specifically catching generation or namespace incompatibility.
8. Establish a JDBC Spring Session, reload it, and exercise CSRF plus a synthetic/mock OAuth2 client configuration without real personal data.
9. Call a selected Google GenAI chat model with synthetic text and one supported synthetic media item; confirm Spring AI serialization and model capability.
10. Through the narrow embedding adapter, resolve/call stable `gemini-embedding-2` using synthetic text and at least one synthetic media input; verify both land in the expected common dimension and that configured task/dimension behavior is honored.
11. If Spring AI-managed Google client 1.65.0 cannot call the stable multimodal API surface, test the smallest supported client/REST alternative, document the incompatibility, and only then approve an explicit override.
12. Create a clean Node 24.21.0/npm 11.19.0 install of the frozen React/Vite 8.2.2/TypeScript dependencies; build, strict type-check, unit-test, and verify Router/TanStack Query/Hook Form imports and peer dependencies.
13. Launch Playwright 1.63.0 against the minimal frontend on a supported browser and CI-equivalent Linux container/runner.
14. If Redis/object storage/email are introduced, run focused contract tests against controlled test dependencies; do not add them speculatively.

## 19. Known constraints and intentionally open selections

| Item | State | Required resolution point |
|---|---|---|
| Spring Modulith appendix omits the 2.1 compatibility row | Selected with high-confidence tag-POM evidence; smoke test required | Initial backend compatibility spike |
| springdoc table does not explicitly enumerate Boot 4.1.x | Selected current Boot 4 generation; smoke test required | Initial backend compatibility spike |
| Google model catalog still shows preview while dedicated/release docs show stable | Stable ID selected with high confidence; live API test required | Provider integration spike before production configuration freeze |
| Spring AI Google embedding integration is text-only | Narrow multimodal adapter required | Search/AI/Retrieval Design and provider spike |
| Google Java client version | Begin with Spring AI-managed 1.65.0; override only if stable multimodal endpoint proves unavailable | Provider integration spike |
| Embedding output dimension and vector index type | Not selected | Search/AI/Retrieval Design after measurement |
| Generative provider/model routing | Not selected | Search/AI/Retrieval Design and privacy review |
| Markdown editor library | Shortlist only | Frontend LLD and accessibility/UX evaluation |
| S3-compatible storage and email vendors | Not selected | Deployment/integration design and commercial/privacy review |
| Frontend asset hosting, proxy/CDN/domains/SSR/BFF | Not selected | Downstream deployment decision; SSR/BFF require separate evidence |

None of these constraints requires changing the approved one-deployable modular-monolith architecture.

## 20. Technologies deliberately not selected

- No Gradle alongside Maven.
- No Spring Authorization Server: the application is an OAuth2/OIDC client, not an authorization server.
- No custom JWT browser authentication or localStorage token scheme.
- No Redis browser sessions, Redis Streams durable jobs, or authoritative Redis persistence.
- No Elasticsearch/OpenSearch or separate vector database.
- No RabbitMQ, Kafka, API gateway, Kubernetes, initial separate worker, or separate AI service.
- No Next.js or other full-stack frontend framework; no SSR baseline.
- No Redux by default.
- No React Compiler, Java preview features, TypeScript nightly, Spring snapshots/milestones, PostgreSQL 19 beta, or Gemini preview ID as the planned production baseline.
- No second Markdown parser, JSON stack, HTTP stack, or test framework without a demonstrated gap.

## 21. Authoritative sources

All sources were accessed and verified on 2026-09-10. Dates above are source-reported release or page-update dates where available.

### Java, Spring Boot, and Maven

- **[J1]** Oracle Java SE support roadmap: https://www.oracle.com/java/technologies/java-se-support-roadmap.html
- **[J2]** Java SE 25 update release notes: https://www.oracle.com/java/technologies/javase/25u-relnotes.html
- **[B1]** Spring Boot reference: https://docs.spring.io/spring-boot/reference/
- **[B2]** Spring Boot 4.1.1 system requirements: https://docs.spring.io/spring-boot/system-requirements.html
- **[B3]** Spring Boot 4.1.1 release announcement: https://spring.io/blog/2026/08/20/spring-boot-4-1-1-available-now/
- **[B4]** Spring Boot 4.1.1 dependency-management POM: https://repo1.maven.org/maven2/org/springframework/boot/spring-boot-dependencies/4.1.1/spring-boot-dependencies-4.1.1.pom
- **[B5]** Spring Boot 4 migration guide: https://github.com/spring-projects/spring-boot/wiki/Spring-Boot-4.0-Migration-Guide
- **[B6]** Spring Boot JSON/Jackson reference: https://docs.spring.io/spring-boot/reference/features/json.html
- **[M1]** Apache Maven download/current stable: https://maven.apache.org/download.cgi
- **[M2]** Apache Maven release history: https://maven.apache.org/docs/history.html
- **[M3]** Apache Maven Wrapper releases: https://github.com/apache/maven-wrapper/releases
- **[SD1]** Spring Data BOM 2026.0.1: https://repo1.maven.org/maven2/org/springframework/data/spring-data-bom/2026.0.1/spring-data-bom-2026.0.1.pom

### Modulith, security, session, data, and OpenAPI

- **[SM1]** Spring Modulith 2.1.1 reference: https://docs.spring.io/spring-modulith/reference/
- **[SM2]** Spring Modulith 2.1.1 tagged POM: https://github.com/spring-projects/spring-modulith/blob/2.1.1/pom.xml
- **[SM3]** Spring Modulith release announcement: https://spring.io/blog/2026/08/26/spring-modulith-2-2-m1-2-1-1-2-0-8-and-1-4-13-released/
- **[SM4]** Spring Modulith compatibility appendix: https://docs.spring.io/spring-modulith/reference/appendix.html
- **[SEC1]** Spring Security servlet OAuth2 login: https://docs.spring.io/spring-security/reference/servlet/oauth2/login/
- **[SEC2]** Spring Security servlet CSRF: https://docs.spring.io/spring-security/reference/servlet/exploits/csrf.html
- **[SES1]** Spring Boot Spring Session reference: https://docs.spring.io/spring-boot/reference/web/spring-session.html
- **[SES2]** Spring Session JDBC reference: https://docs.spring.io/spring-session/reference/configuration/jdbc.html
- **[OA1]** springdoc-openapi official documentation and compatibility table: https://springdoc.org/
- **[OA2]** Maven Central springdoc WebMVC UI metadata: https://repo1.maven.org/maven2/org/springdoc/springdoc-openapi-starter-webmvc-ui/maven-metadata.xml

### PostgreSQL, pgvector, Flyway, and tests

- **[PG1]** PostgreSQL 18 release notes: https://www.postgresql.org/docs/18/release.html
- **[PG2]** PostgreSQL release index: https://www.postgresql.org/docs/release/
- **[PGJ1]** pgJDBC 42.7.13 release notes: https://jdbc.postgresql.org/changelogs/2026-07-06-42.7.13-release/
- **[PV1]** pgvector official repository/readme: https://github.com/pgvector/pgvector
- **[PV2]** pgvector changelog: https://github.com/pgvector/pgvector/blob/master/CHANGELOG.md
- **[F1]** Flyway PostgreSQL support page: https://documentation.red-gate.com/fd/postgresql-database-277579325.html
- **[TC1]** Testcontainers PostgreSQL module: https://java.testcontainers.org/modules/databases/postgres/

### Spring AI, Gemini, and provider data handling

- **[AI1]** Spring AI getting started and compatibility: https://docs.spring.io/spring-ai/reference/getting-started.html
- **[AI2]** Spring AI 2.0.1 release: https://spring.io/blog/2026/08/21/spring-ai-2-0-1-available-now/
- **[AI3]** Spring AI chat-model comparison: https://docs.spring.io/spring-ai/reference/api/chat/comparison.html
- **[AI4]** Spring AI Google GenAI chat: https://docs.spring.io/spring-ai/reference/api/chat/google-genai-chat.html
- **[AI5]** Spring AI Ollama chat: https://docs.spring.io/spring-ai/reference/api/chat/ollama-chat.html
- **[AI6]** Spring AI PgVectorStore: https://docs.spring.io/spring-ai/reference/api/vectordbs/pgvector.html
- **[AI7]** Spring AI Google GenAI text embeddings: https://docs.spring.io/spring-ai/reference/api/embeddings/google-genai-embeddings-text.html
- **[AI8]** Spring AI Google GenAI 2.0.1 POM: https://repo1.maven.org/maven2/org/springframework/ai/spring-ai-google-genai/2.0.1/spring-ai-google-genai-2.0.1.pom
- **[G1]** Dedicated Gemini Embedding 2 model page: https://ai.google.dev/gemini-api/docs/models/gemini-embedding-2
- **[G2]** Gemini embeddings guide: https://ai.google.dev/gemini-api/docs/embeddings
- **[G3]** Gemini models catalog: https://ai.google.dev/gemini-api/docs/models
- **[G4]** Gemini API release notes: https://ai.google.dev/gemini-api/docs/changelog
- **[G5]** Gemini deprecations: https://ai.google.dev/gemini-api/docs/deprecations
- **[P1]** Ollama privacy policy: https://ollama.com/privacy
- **[P2]** Gemini API additional terms: https://ai.google.dev/gemini-api/terms
- **[P3]** Gemini API zero data retention: https://ai.google.dev/gemini-api/docs/zdr
- **[P4]** Groq data controls: https://console.groq.com/docs/your-data

### Frontend and tools

- **[RE1]** React 19.3 release: https://react.dev/blog/2026/09/09/react-19-3
- **[TS1]** TypeScript official site/releases: https://www.typescriptlang.org/
- **[V1]** Vite guide and Node requirements: https://vite.dev/guide/
- **[V2]** Official npm Vite version tags (`latest` 8.2.2; `beta` 8.3.0-beta.1 on the verification date): https://www.npmjs.com/package/vite?activeTab=versions
- **[TQ1]** TanStack Query React documentation: https://tanstack.com/query/latest/docs/framework/react
- **[RR1]** React Router changelog: https://reactrouter.com/changelog
- **[RR2]** React Router modes: https://reactrouter.com/start/modes
- **[RHF1]** React Hook Form official documentation: https://react-hook-form.com/
- **[PW1]** Playwright installation/system requirements: https://playwright.dev/docs/intro
- **[N1]** Node.js releases and LTS status: https://nodejs.org/en/about/previous-releases
- **[N2]** Node.js 24 archive: https://nodejs.org/en/download/archive/v24
- **[NPM]** Official npm registry current package metadata used for the frozen package versions: https://registry.npmjs.org/react/latest, https://registry.npmjs.org/react-dom/latest, https://registry.npmjs.org/@types%2freact/latest, https://registry.npmjs.org/@types%2freact-dom/latest, https://registry.npmjs.org/typescript/latest, https://registry.npmjs.org/vite/latest, https://registry.npmjs.org/@vitejs%2fplugin-react/latest, https://registry.npmjs.org/@tanstack%2freact-query/latest, https://registry.npmjs.org/react-router/latest, https://registry.npmjs.org/react-hook-form/latest, https://registry.npmjs.org/vitest/latest, https://registry.npmjs.org/@testing-library%2freact/latest, https://registry.npmjs.org/@testing-library%2fdom/latest, https://registry.npmjs.org/@testing-library%2fuser-event/latest, https://registry.npmjs.org/@testing-library%2fjest-dom/latest, https://registry.npmjs.org/jsdom/latest, https://registry.npmjs.org/playwright/latest, https://registry.npmjs.org/react-markdown/latest, https://registry.npmjs.org/remark-gfm/latest, https://registry.npmjs.org/rehype-sanitize/latest
- **[MD1]** react-markdown official repository/security notes: https://github.com/remarkjs/react-markdown
- **[MD2]** rehype-sanitize official repository: https://github.com/rehypejs/rehype-sanitize
- **[MD3]** remark-gfm official releases: https://github.com/remarkjs/remark-gfm/releases

### External integrations, containers, and CI

- **[S31]** AWS SDK for Java 2 Maven/BOM setup: https://docs.aws.amazon.com/sdk-for-java/latest/developer-guide/setup-project-maven.html
- **[S32]** AWS SDK endpoint configuration: https://docs.aws.amazon.com/sdk-for-java/latest/developer-guide/endpoint-config.html
- **[S33]** AWS SDK Java BOM metadata: https://repo1.maven.org/maven2/software/amazon/awssdk/bom/maven-metadata.xml
- **[MAIL1]** Spring Boot email reference: https://docs.spring.io/spring-boot/reference/io/email.html
- **[R1]** Redis Open Source release notes: https://redis.io/docs/latest/operate/oss_and_stack/stack-with-enterprise/release-notes/redisce/
- **[R2]** Redis Open Source 8.10 release notes (8.10.1 security update): https://redis.io/docs/latest/operate/oss_and_stack/stack-with-enterprise/release-notes/redisce/redisos-8.10-release-notes/
- **[D1]** Docker Engine 29 release notes: https://docs.docker.com/engine/release-notes/29/
- **[D2]** Docker Compose releases: https://github.com/docker/compose/releases
- **[CI1]** GitHub Actions Java with Maven: https://docs.github.com/en/actions/tutorials/build-and-test-code/java-with-maven
- **[CI2]** GitHub Actions service containers: https://docs.github.com/en/actions/tutorials/use-containerized-services

## 22. Review gate

Human review is complete, and this document is the Approved Baseline for the initial implementation technology stack and compatibility decisions. Its explicit implementation-time smoke-test gates remain binding. Approval does not change the one-deployable architecture or authorize any deferred design document or implementation artifact.
