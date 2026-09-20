# Notes & Knowledge Workspace
# Phase 0 — Implementation Entry and Revalidation

Status: Phase 0 evidence — Accepted  
Date: 2026-09-17  
Roadmap phase: 0
Human completion approval date: 2026-09-19

## 1. Purpose and evidence boundary

This document records the dated, read-only evidence gathered under the explicit authorization for Roadmap Phase 0 — Implementation Entry, Revalidation, and Public Safety. It is implementation-entry evidence only. It is not an Approved Baseline, a baseline amendment, implementation source, a declaration that Phase 0 is complete, or authorization for Phase 1.

Except for this document and its containing directory, the workspace was treated as read-only. No installation, dependency resolution, account login, provider call, cloud mutation, Git initialization, build, test, scaffold, migration, container pull, or container start was performed.

All time-sensitive external conclusions below are checkpoints as of **2026-09-18 (Asia/Calcutta)**. They are not claims of permanence.

## A. Authority / workspace-entry checklist

| Checkpoint | Evidence | Result |
|---|---|---|
| Workspace root | `G:\Notes Knowledge Workspace` was inspected only within that root. | PASS |
| Implementation operating contract | `AGENTS.md` says `Status: Accepted`, date and acceptance date 2026-09-17, and document role `Implementation Agent Operating Contract`. | PASS |
| Approved authority count | Exactly 17 documents under `docs/` identify themselves as `Approved Baseline`; the handoff independently registers the same 17. | PASS |
| Implementation Roadmap | Document 17 is `Approved Baseline`, approval date 2026-09-17. | PASS |
| Phase 0 pre-execution state | `AGENTS.md` and the handoff record every Roadmap phase, including Phase 0, as `NOT STARTED` before this explicit authorization. This evidence document does not change that status. | PASS |
| Phase 1 state | Phase 1 has not begun and is not authorized by this document. | PASS |
| Git | Git executable is present, but no `.git` directory exists in the workspace. | PASS |
| Implementation/scaffolding | Before this document was created, all 19 workspace files were Markdown authority/design documents; no application source, build manifest, wrapper, lockfile, migration, test, container, or CI workflow existed. | PASS |
| Existing amendment blockers | The approved Security and Schema amendments are already incorporated in their approved baselines. No unresolved `REQUIRES ... BASELINE AMENDMENT BEFORE IMPLEMENTATION` marker was found. | PASS |
| Authorized output | The only authorized output is this file under the newly created `docs/implementation/` directory. | PASS |

The authority-entry checkpoint is **PASS**.

## B. Local development tool inventory

The commands were read-only version queries. No daemon or container was started.

| Tool | Approved baseline | Discovered locally | Local classification | Required disposition |
|---|---|---|---|---|
| Java runtime | Java 25, no preview features | Eclipse Temurin OpenJDK `25.0.4+7-LTS` | READY | Retain the Java 25 line; use repository configuration to prohibit preview features in Phase 1. |
| `javac` | Java 25 | `javac 25.0.4` | READY | None before Phase 1. |
| Maven | 3.9.16 | Apache Maven `3.9.16` | READY | Phase 1 creates and then uses the approved Maven Wrapper; no wrapper exists now. |
| Node.js | 24.21.0 LTS | `v24.16.0` | DIFFERENT BUT NON-BLOCKING BEFORE PHASE 1 | Switch/install the approved 24.21.0 LTS toolchain before executing Phase 1. |
| npm | 11.19.0 bundled with Node 24.21.0 | `11.13.0` | DIFFERENT BUT NON-BLOCKING BEFORE PHASE 1 | Use npm 11.19.0 from the approved Node distribution before Phase 1 creates the lockfile. |
| Git executable | Supported current Git | `2.51.0.windows.2` | READY | Presence is not repository initialization; initialize only if separately authorized in Phase 1. |
| Docker Engine CLI | Verified 29.8.0; supported current release | `28.5.1` | DIFFERENT BUT NON-BLOCKING BEFORE PHASE 1 | Align to a supported current release satisfying the baseline before Phase 1 container smoke tests. |
| Docker Compose CLI | Verified 5.5.1; supported current release | `v2.40.0-desktop.1` | DIFFERENT BUT NON-BLOCKING BEFORE PHASE 1 | Align before Phase 1 container smoke tests. |

Local tool checkpoint: **PASS — RECHECK AT PHASE 1**.

## C. Approved technology-version revalidation

“Exists” means the approved release/line remains obtainable from an official project source or registry. It does not mean that a newer release must replace it.

| Technology | Approved version/line | Current official existence | Compatibility finding | Local installed state | Official source | Result |
|---|---|---|---|---|---|---|
| Java / Eclipse Temurin | Java 25 | Temurin 25 binaries and current local 25.0.4 runtime exist. | Boot 4.1.1 supports Java through 26. | 25.0.4 installed | [Adoptium FAQ](https://adoptium.net/docs/faq), [Boot requirements](https://docs.spring.io/spring-boot/system-requirements.html) | PASS |
| Spring Boot | 4.1.1 | Release and dependency POM remain published. | Java 25 and Maven 3.9.16 satisfy documented requirements. | Not resolved; correctly absent before Phase 1 | [Boot requirements](https://docs.spring.io/spring-boot/system-requirements.html), [4.1.1 POM](https://repo1.maven.org/maven2/org/springframework/boot/spring-boot-dependencies/4.1.1/spring-boot-dependencies-4.1.1.pom) | PASS |
| Spring Security / Session JDBC / Actuator / Mail | Boot-managed 7.1.1 / 4.1.1 / 4.1.1 / managed Jakarta Mail line | Present in the Boot 4.1.1 managed dependency and starter generation. | No evidence contradicts the approved servlet/session/operability/mail design. | Not resolved | [Boot 4.1.1 POM](https://repo1.maven.org/maven2/org/springframework/boot/spring-boot-dependencies/4.1.1/spring-boot-dependencies-4.1.1.pom) | PASS |
| Spring Modulith | 2.1.1 | Official tag and reference remain available. | Version-specific POM alignment supports Boot 4.1.1; executable verification remains mandatory. | Not resolved | [2.1.1 POM](https://github.com/spring-projects/spring-modulith/blob/2.1.1/pom.xml), [reference](https://docs.spring.io/spring-modulith/reference/) | PASS — RECHECK AT PHASE 1 |
| Spring AI | 2.0.1 | Official 2.0.1 reference/release remains available. | 2.0 generation supports Boot 4.0/4.1; provider surfaces still require smoke tests. | Not resolved | [2.0.1 release](https://spring.io/blog/2026/08/21/spring-ai-2-0-1-available-now/), [reference](https://docs.spring.io/spring-ai/reference/) | PASS — RECHECK AT PHASE 1 |
| Maven | 3.9.16 | Current official release/download remains published. | Satisfies Boot minimum. | 3.9.16 installed | [Maven 3.9.16 notes](https://maven.apache.org/docs/3.9.16/release-notes.html) | PASS |
| PostgreSQL | 18.6 | Official 18.6 release exists and the 18 line is supported through 2030-11-14. | Native UUIDv7 functions and `pg_trgm` are documented. | Not installed/started as a project service | [18.6 notes](https://www.postgresql.org/docs/release/18.6/), [versioning policy](https://www.postgresql.org/support/versioning/), [UUID functions](https://www.postgresql.org/docs/18/functions-uuid.html), [`pg_trgm`](https://www.postgresql.org/docs/18/pgtrgm.html) | PASS |
| pgvector | 0.8.6 | Tag/package/source and PostgreSQL 18 image tags exist. | Supports PostgreSQL 13+, including 18; source, APT, and controlled container paths remain plausible. | Not installed | [pgvector README](https://github.com/pgvector/pgvector), [metadata](https://github.com/pgvector/pgvector/blob/master/META.json) | PASS — RECHECK AT PHASE 1 |
| Flyway PostgreSQL module | Boot-managed 12.4.0 | PostgreSQL database module remains documented. | PostgreSQL 18 is listed as verified; separate PostgreSQL module remains required. | Not resolved | [Flyway PostgreSQL](https://documentation.red-gate.com/fd/postgresql-database-277579325.html) | PASS |
| Required database extensions | `vector`, `pg_trgm` only | Both remain available through pgvector and PostgreSQL contrib. | No third required extension was discovered. | Not installed | [pgvector README](https://github.com/pgvector/pgvector), [`pg_trgm`](https://www.postgresql.org/docs/18/pgtrgm.html) | PASS |
| Testcontainers | 2.0.5 | Official documentation/release exists. | Docker-backed PostgreSQL testing remains plausible; execute on Java 25 and controlled pgvector image in Phase 1. | Not resolved | [Testcontainers Java](https://java.testcontainers.org/), [repository](https://github.com/testcontainers/testcontainers-java) | PASS — RECHECK AT PHASE 1 |
| springdoc-openapi | 3.1.1 | Official site identifies 3.1.1 for the Boot 4 generation. | Exact Boot 4.1.1/Jackson 3 generation remains a mandatory smoke. | Not resolved | [springdoc](https://springdoc.org/), [Maven metadata](https://repo1.maven.org/maven2/org/springdoc/springdoc-openapi-starter-webmvc-ui/maven-metadata.xml) | PASS — RECHECK AT PHASE 1 |
| Node.js / npm | 24.21.0 LTS / 11.19.0 | Node archive identifies 24.21.0 as LTS and bundled npm 11.19.0. | Approved LTS line remains available for Windows and Linux ARM64/x64. | 24.16.0 / 11.13.0 installed | [Node 24.21.0 archive](https://nodejs.org/en/download/archive/v24.21.0) | PASS — RECHECK AT PHASE 1 |
| React / React DOM | 19.3.0 / 19.3.0 | Both approved package versions remain published. | No current official incompatibility with the approved Node/TypeScript/Vite line was found. | Not resolved | [React npm](https://www.npmjs.com/package/react), [React DOM npm](https://www.npmjs.com/package/react-dom) | PASS — RECHECK AT PHASE 1 |
| TypeScript | 7.0.2 | Approved version remains published. | Clean strict type-check is required because this is a fresh major line. | Not resolved | [TypeScript npm](https://www.npmjs.com/package/typescript) | PASS — RECHECK AT PHASE 1 |
| Vite / React plugin | 8.2.2 / 6.1.1 | Both remain published. Vite 8.3.0 has since become stable, but that does not invalidate 8.2.2. | No upgrade is selected; clean build smoke remains required. | Not resolved | [Vite npm](https://www.npmjs.com/package/vite), [plugin npm](https://www.npmjs.com/package/@vitejs/plugin-react) | PASS — RECHECK AT PHASE 1 |
| TanStack Query | 5.102.8 | Approved version remains published. | No contradictory peer/runtime constraint found. | Not resolved | [npm](https://www.npmjs.com/package/@tanstack/react-query) | PASS — RECHECK AT PHASE 1 |
| React Router | 8.3.1 | Approved version remains published. | Current package remains MIT and compatible in principle with React 19; import smoke required. | Not resolved | [npm](https://www.npmjs.com/package/react-router) | PASS — RECHECK AT PHASE 1 |
| React Hook Form | 7.87.0 | Approved version remains published. | No contradictory official constraint found. | Not resolved | [npm](https://www.npmjs.com/package/react-hook-form) | PASS — RECHECK AT PHASE 1 |
| Vitest / jsdom | 5.0.0 / 30.0.1 | Both approved versions remain published; later patches exist. | Node 24 is within Vitest 5's supported engine line; actual suite smoke remains required. | Not resolved | [Vitest npm](https://www.npmjs.com/package/vitest), [jsdom npm](https://www.npmjs.com/package/jsdom) | PASS — RECHECK AT PHASE 1 |
| Testing Library | React 16.3.3; DOM 10.4.1; user-event 14.6.7; jest-dom 7.0.1 | All approved versions remain published. | React 16.3.3 package requires React 18+ and the approved React 19 satisfies that floor. | Not resolved | [React](https://www.npmjs.com/package/@testing-library/react), [DOM](https://www.npmjs.com/package/@testing-library/dom), [user-event](https://www.npmjs.com/package/@testing-library/user-event), [jest-dom](https://www.npmjs.com/package/@testing-library/jest-dom) | PASS — RECHECK AT PHASE 1 |
| Playwright | 1.63.0 | Approved version remains published. | Current Node LTS posture remains plausible; install/browser/Linux runner smoke required. | Not resolved | [Playwright npm](https://www.npmjs.com/package/playwright), [requirements](https://playwright.dev/docs/intro) | PASS — RECHECK AT PHASE 1 |
| Redis | Compatible 8.10.x; verified patch 8.10.1 | 8.10.1 and ARM64 official image tags remain available. | Still optional/transient/non-authoritative. Licensing requires deliberate compliance review before use. | Not installed/run | [Redis releases](https://redis.io/docs/latest/operate/oss_and_stack/stack-with-enterprise/release-notes/), [ARM64 image](https://hub.docker.com/r/arm64v8/redis) | PASS — RECHECK BEFORE PRODUCTION |

Approved-version checkpoint: **PASS — RECHECK AT PHASE 1**.

## D. Critical compatibility matrix

| Combination | Dated finding | Required executable evidence | Result |
|---|---|---|---|
| Java 25 + Spring Boot 4.1.1 | Boot 4.1.1 supports Java 17 through 26; Java 25 is within range. | Minimal Boot build/start with no preview features. | PASS — RECHECK AT PHASE 1 |
| Boot 4.1.1 + Spring Modulith 2.1.1 | Official tag POM aligns to Boot 4.1.1, while documentation can lag the 2.1 line. Nothing positively contradicts compatibility. | Load verification/test/documentation modules; run structure verification and a module test. | PASS — RECHECK AT PHASE 1 |
| Boot 4.1.1 + Spring AI 2.0.1 | Spring AI 2.0.x is designed for Boot 4.0/4.1. | Resolve the BOM and exercise fake Google chat/text-embedding paths plus narrow multimodal adapter boundary. | PASS — RECHECK AT PHASE 1 |
| Boot 4.1.1 + springdoc 3.1.1 | springdoc 3.x is the Boot 4 generation; the exact 4.1.1/Jackson 3 combination still lacks sufficient documentation-only proof. | Generate/load OpenAPI JSON and UI. | PASS — RECHECK AT PHASE 1 |
| Testcontainers 2.0.5 + Java 25 + Docker | Official current Testcontainers supports Docker-backed JUnit testing; no Java 25 contradiction found. | Start controlled pgvector/PostgreSQL and verify lifecycle on aligned Docker. | PASS — RECHECK AT PHASE 1 |
| React 19.3 + TypeScript 7.0.2 + Vite 8.2.2 | All exact releases remain available; no official contradictory peer/engine condition found. | `npm ci`, strict type-check, build, Vitest, imports, peer-dependency check. | PASS — RECHECK AT PHASE 1 |
| PostgreSQL 18.6 + pgvector 0.8.6 | pgvector 0.8.6 supports PostgreSQL 18 install/image paths. | Testcontainers extension/version assertions; later Linux ARM64 image/source-build proof. | PASS — RECHECK AT PHASE 1 |
| Node 24.21.0 + Playwright 1.63.0 | Approved LTS and package remain available. | Install managed browsers and execute a CI-equivalent browser smoke. | PASS — RECHECK AT PHASE 1 |

Critical compatibility checkpoint: **PASS — RECHECK AT PHASE 1**.

## 2. Prometheus/Micrometer conditional checkpoint

Spring Boot 4.1.1 Actuator manages Micrometer registry integrations and documents the Prometheus-format Actuator endpoint when `micrometer-registry-prometheus` is on the classpath. The adapter adds no service, authority, deployable, or new architecture boundary. Exact exposure remains constrained by the Security, Deployment, and Observability baselines.

Conclusion: the standard adapter fits as an implementation-level Boot-managed observability adapter; no Technology Stack amendment is required. **PASS**.

## E. License / public-repository review

This is a bounded engineering review, not legal advice. License texts and notices must still be retained and an SBOM/license scan must run under the approved CI policy.

| Component | Official/license evidence | Bounded finding | Result |
|---|---|---|---|
| Eclipse Temurin JDK | Adoptium identifies GPLv2 with Classpath Exception and no-cost use. | NO OBVIOUS PUBLIC-PORTFOLIO BLOCKER | PASS |
| Spring projects | Spring projects state Apache License 2.0. | NO OBVIOUS PUBLIC-PORTFOLIO BLOCKER | PASS |
| PostgreSQL | PostgreSQL License permits use/copy/modify/distribute with notices. | NO OBVIOUS PUBLIC-PORTFOLIO BLOCKER | PASS |
| pgvector | PostgreSQL License is declared in project metadata/LICENSE. | NO OBVIOUS PUBLIC-PORTFOLIO BLOCKER | PASS |
| Node.js/npm and React ecosystem | Major selected packages identify permissive MIT/ISC/Apache-style licenses in their official repositories/registry metadata. | NO OBVIOUS PUBLIC-PORTFOLIO BLOCKER; dependency scan remains required. | PASS — RECHECK AT PHASE 1 |
| Testcontainers | Official repository identifies MIT. | NO OBVIOUS PUBLIC-PORTFOLIO BLOCKER | PASS |
| Playwright | Official repository identifies Apache-2.0. | NO OBVIOUS PUBLIC-PORTFOLIO BLOCKER | PASS |
| Redis 8 | Redis 8 is tri-licensed under RSALv2, SSPLv1, or AGPLv3; AGPLv3 is OSI-approved but copyleft. | No obvious blocker for an unmodified, separately operated, optional same-VM transient server in a public-source portfolio. Record the chosen license path, preserve notices/source obligations, and re-review before introduction/distribution. | PASS — RECHECK BEFORE PRODUCTION |

License checkpoint: **PASS — RECHECK AT PHASE 1**.

## F. GitHub zero-cost/current-capability table

| Capability | Current official checkpoint | Account/repository action deferred | Result |
|---|---|---|---|
| Standard public x64 Actions | Standard GitHub-hosted runners are free for public repositories; `ubuntu-24.04` is available. | Verify repository visibility and prohibit larger runners. | PASS — RECHECK AT PHASE 1 |
| Standard public ARM64 Actions | `ubuntu-24.04-arm` is documented as a standard ARM64 label; standard public-repository runners are free. | Prove the actual repository can schedule it. | PASS — RECHECK AT PHASE 1 |
| Artifacts | GitHub Free allowance is currently 500 MB, shared with Packages storage. | Set bounded retention and artifact size controls. | PASS — RECHECK AT PHASE 1 |
| Cache | 10 GB per repository is included; charges require a higher configured limit/payment posture. | Keep the limit at/below included usage. | PASS — RECHECK AT PHASE 1 |
| GHCR | Public packages are free; Container Registry image storage/bandwidth is currently free with advance notice promised for policy change. | Keep image public only when approved; recheck policy before release. | PASS — RECHECK BEFORE PRODUCTION |
| CodeQL / secret scanning / push protection | Public-repository security capabilities remain documented; exact enablement varies by repository/settings. | Verify each required feature after repository creation. | PASS — RECHECK AT PHASE 1 |
| Dependency review / Dependabot | Dependency review is available to public repositories and Dependabot runs are free. | Enable approved checks and update policy. | PASS — RECHECK AT PHASE 1 |
| Artifact attestations | Available for public repositories on eligible GitHub plans. | Prove permissions and verify release attestations. | PASS — RECHECK AT PHASE 1 |
| Spending controls | No payment method causes quota exhaustion to block; accounts with payment methods require appropriate budgets. Larger runners are always chargeable. | Account-specific hard-stop/budget verification when repository exists; no larger runners. | PASS — RECHECK AT PHASE 1 |

Public-documentation result: the ₹0 public-repository model remains plausible. Account-specific controls cannot be proven before the repository exists and remain mandatory. **PASS — RECHECK AT PHASE 1**.

## G. OCI zero-cost/current-capability table

| Capability | Current official checkpoint | Qualification still required | Result |
|---|---|---|---|
| Ampere A1 | 1,500 OCPU-hours and 9,000 GB-hours monthly, equivalent for Always Free tenancy to 2 OCPUs / 12 GB RAM. | Home-region entitlement, current account eligibility, image and capacity. | PASS — RECHECK BEFORE PRODUCTION |
| Planned shape | The approved ~2 OCPU / 12 GB plan consumes the documented total A1 Always Free allowance, not a smaller fraction. | Confirm no other A1 allocation consumes it and size internal reserves conservatively. | PASS — RECHECK BEFORE PRODUCTION |
| Capacity/reclamation | Oracle documents region capacity errors and idle-instance reclamation. | **ACCOUNT-SPECIFIC CHECK REQUIRED AT PHASE 13**; no paid fallback. | PASS — RECHECK BEFORE PRODUCTION |
| Block storage / backups | 200 GB combined boot+block storage and five volume backups in home region. | Prove allocation totals and restore design within limits. | PASS — RECHECK BEFORE PRODUCTION |
| Object Storage | 20 GB combined Always Free storage for free-only state and 50,000 requests/month. | Confirm actual tenancy state, request metering, retention and guardrails. | PASS — RECHECK BEFORE PRODUCTION |
| S3 compatibility | OCI documents an Amazon S3 Compatibility API, endpoint configuration, Customer Secret Keys, and S3 tool reuse. | Prove the application port against a private bucket and SigV4-compatible client. | PASS — RECHECK BEFORE PRODUCTION |
| Outbound transfer | 10 TB/month Always Free checkpoint. | Confirm tenancy/region scope and configure soft denial before any paid boundary. | PASS — RECHECK BEFORE PRODUCTION |
| Bastion | OCI states Bastion is free for free and paid accounts. | Verify tenancy availability and restricted operator policy. | PASS — RECHECK BEFORE PRODUCTION |
| Monitoring / Logging | 500 million ingestion and 1 billion retrieval data points; Logging includes 10 GB/month for Free Tier tenancy. | Validate metric/log cardinality and actual tenancy limits. | PASS — RECHECK BEFORE PRODUCTION |
| Dashboards / Notifications / synthetics | 100 dashboards; 1 million HTTPS and 1,000 email notifications/month; 10 synthetic runs/hour. | Validate exact service/region/account availability and conservative quotas. | PASS — RECHECK BEFORE PRODUCTION |
| Budgets/cost controls | OCI budgets are explicitly soft limits/alerts; compartment quotas can constrain resource consumption. | Do not treat budgets as a hard stop. Verify free-only/account upgrade state, quotas and every resource's Always Free label. | PASS — RECHECK BEFORE PRODUCTION |

The official policy does not invalidate the approved topology, but capacity is account-specific and budgets alone are not hard stops. **PASS — RECHECK BEFORE PRODUCTION**.

## H. Brevo qualification table

| Check | Current official finding | Deferred live qualification | Result |
|---|---|---|---|
| Free tier | Free plan remains listed at $0 with 300 emails/day. | Confirm account eligibility and no automatic overage. | PASS — RECHECK BEFORE LIVE QUALIFICATION |
| Transactional SMTP | Brevo documents authenticated transactional SMTP relay. | Create/verify sender and credentials only in the separately authorized live phase. | PASS — RECHECK BEFORE LIVE QUALIFICATION |
| TLS | Documented relay ports include TLS-capable 587/465/2525. | Prove authenticated TLS from the actual deployment network; OCI blocks outbound port 25 by default, so do not rely on 25. | PASS — RECHECK BEFORE LIVE QUALIFICATION |
| Java integration | SMTP is a standard protocol; approved `JavaMailSender`/Jakarta Mail can use it without a Brevo HTTP SDK. | Integration smoke with fake/local adapter first, then a synthetic live delivery. | PASS — RECHECK BEFORE LIVE QUALIFICATION |
| Sender/domain constraints | Sender/domain authentication and provider account configuration are required for trustworthy live mail. | Complete in Phase 3 qualification; never expose enumeration or capability material. | PASS — RECHECK BEFORE LIVE QUALIFICATION |

The approved zero-cost demonstration path remains plausible. **PASS — RECHECK BEFORE LIVE QUALIFICATION**.

## I. Google OIDC/domain-readiness table

| Check | Official finding | Deferred live qualification | Result |
|---|---|---|---|
| Authorization Code flow | Google's web-server OAuth guidance supports server-side authorization-code exchange. | Exercise exact application flow against an authorized test client. | PASS — RECHECK BEFORE LIVE QUALIFICATION |
| Redirect URI | Google requires exact match, including scheme, case and trailing slash characteristics. | Register and test the final HTTPS callback only. | PASS — RECHECK BEFORE LIVE QUALIFICATION |
| PKCE | Google discovery/guidance exposes PKCE support; the Security/API baselines require S256. | Prove S256 verifier/challenge end to end. | PASS — RECHECK BEFORE LIVE QUALIFICATION |
| `state` / `nonce` / ID token | Official OIDC guidance requires state protection as applicable and validation of signed token, issuer, audience, expiry; nonce protects replay. | Execute negative and positive tests without changing issuer+subject identity semantics. | PASS — RECHECK BEFORE LIVE QUALIFICATION |
| Consent / authorized domains | Production apps require current consent/branding/public URL configuration and authorized domains. | Verify the actual Google Auth Platform project and minimal `openid email profile` scopes. | PASS — RECHECK BEFORE LIVE QUALIFICATION |
| Domain ownership | Google may require Search Console/domain verification and matching ownership roles. | Prove the assigned hostname/domain with the real project and DNS evidence. | PASS — RECHECK BEFORE LIVE QUALIFICATION |

Free-hostname compatibility cannot be certified without a live hostname and Google project, but the documented path remains possible. **PASS — RECHECK BEFORE LIVE QUALIFICATION**.

## J. Free-hostname/TLS table

| Check | Current official finding | Result |
|---|---|---|
| DuckDNS candidate | DuckDNS still documents a free dynamic DNS service and TXT-record update API, including a Let's Encrypt ownership-proof use case. | PASS — RECHECK BEFORE LIVE QUALIFICATION |
| Equivalent provider boundary | Deployment explicitly permits DuckDNS **or equivalent** after actual verification, so discovering another verified zero-cost hostname does not itself change architecture. | PASS — RECHECK BEFORE LIVE QUALIFICATION |
| Let's Encrypt | Let's Encrypt remains a free, automated CA; ACME HTTP-01 and DNS-01 remain documented. | PASS — RECHECK BEFORE LIVE QUALIFICATION |
| Google readiness | TXT capability is necessary evidence but does not guarantee Google will accept the assigned hostname/authorized domain. | PASS — RECHECK BEFORE LIVE QUALIFICATION |
| ARM64 Linux | ACME is protocol-based; no ARM64 architectural incompatibility was found. The concrete ACME client/image still requires supply-chain and executable proof. | PASS — RECHECK BEFORE PRODUCTION |

Conclusion: **PASS — RECHECK BEFORE LIVE QUALIFICATION**. The approved “or equivalent” path exists; no provider replacement is silently selected.

## K. Gemini capability/model table

| Check | Current official finding | Implementation constraint | Result |
|---|---|---|---|
| Unpaid tier | Gemini Developer API pricing continues to expose a free tier with limited model access and free input/output for listed eligible models. | Billing remains disabled; limits and exact model eligibility must be read from the actual project. | PASS — RECHECK BEFORE LIVE QUALIFICATION |
| Stable chat/generation candidate | The current model catalog lists stable Flash candidates, including `gemini-3.8-flash`; the approved design intentionally does not freeze a stale chat alias. | At implementation/live qualification select a current stable free-tier model only after capability, quota, terms and API smoke. This evidence does not select it permanently. | PASS — RECHECK BEFORE LIVE QUALIFICATION |
| Text embedding | `gemini-embedding-001` remains documented for text-only use. | Preserve lineage/dimension configuration and query/document task behavior. | PASS — RECHECK BEFORE LIVE QUALIFICATION |
| Multimodal embedding | Gemini Embedding 2 is documented for text, image, audio, video and PDF/document inputs in a unified space. | Exact model ID/availability and dimensions must be retrieved and smoke-tested in the actual project. | PASS — RECHECK BEFORE LIVE QUALIFICATION |
| Candidate status | The dedicated model card documents stable `gemini-embedding-2`, while catalog surfaces and lifecycle can change. Capability exists, but exact endpoint status must not be assumed from stale prose. | Query the provider model list and recheck deprecations immediately before implementation/live use. | PASS — RECHECK AT PHASE 1 |
| Bounds | Current docs describe up to 8,192 text tokens, six images, 180 seconds audio, 120 seconds/32 sampled frames video, and one PDF up to six pages for Embedding 2. | Treat these as dated provider caps; enforce stricter project bounds where baselines require. | PASS — RECHECK BEFORE LIVE QUALIFICATION |
| Quotas/rate limits | Limits vary by model/project/tier and include RPM/TPM/possibly daily dimensions; preview models are more restricted. | Discover actual quotas, bound retry/backoff and surface truthful degradation. | PASS — RECHECK BEFORE LIVE QUALIFICATION |
| Lifecycle risk | Google maintains model/deprecation documentation and model-list API; model availability is not permanent. | Record exact IDs/lineage and execute the approved retirement/reindex flow. | PASS — RECHECK BEFORE PRODUCTION |

Required generation, embedding and multimodal capability still exists. **PASS — RECHECK AT PHASE 1**.

## L. Gemini terms/privacy table

| Check | Current official finding | Approved posture | Result |
|---|---|---|---|
| Unpaid-service data use | Current Additional Terms permit Google to use submitted content and outputs to provide/improve/develop products and ML technologies. | Do not submit arbitrary private content. | PASS — RECHECK BEFORE LIVE QUALIFICATION |
| Human review | Current terms say human reviewers may read/annotate/process API inputs and outputs, with stated disassociation measures. | Use only synthetic, public-safe, non-sensitive content on the unpaid tier. | PASS — RECHECK BEFORE LIVE QUALIFICATION |
| Sensitive/confidential content | Current terms direct users not to submit sensitive, confidential or personal information to unpaid services, subject to regional terms differences. | Approved restriction remains binding and is not relaxed here. | PASS — RECHECK BEFORE LIVE QUALIFICATION |
| Paid contrast | Pricing currently distinguishes paid content as not used to improve products. | Paid service is not authorized and is not an automatic fallback. | PASS — RECHECK BEFORE LIVE QUALIFICATION |
| Regional/account variation | Terms note different data-use treatment for EEA/Switzerland/UK. | Actual account/region still requires explicit provider/privacy review; no broader private-content inference is allowed. | PASS — RECHECK BEFORE LIVE QUALIFICATION |

Privacy conclusion: the approved unpaid-tier restriction is **CORRECT**. It remains necessary and sufficient for the synthetic/public-safe/non-sensitive demonstration checkpoint, subject to live revalidation. **PASS — RECHECK BEFORE LIVE QUALIFICATION**.

## M. Spring AI/Gemini integration table

| Capability | Current integration evidence | Required project boundary | Result |
|---|---|---|---|
| Chat/generation | Spring AI 2.0.1 documents Google GenAI chat, model options and media inputs dependent on model capability. | Use the provider-agnostic Knowledge port, exact configured model and fake provider in mandatory CI. | PASS — RECHECK AT PHASE 1 |
| Text embedding | Spring AI 2.0.1 documents a Google GenAI text embedding starter/model. | Use only where text behavior and selected model are supported. | PASS — RECHECK AT PHASE 1 |
| Multimodal embedding | Spring AI's Google embedding page still states its SDK integration supports text embeddings only and multimodal support is pending. | Preserve the approved narrow Knowledge-owned Gemini multimodal embedding adapter; do not replace Spring AI globally. | PASS — RECHECK AT PHASE 1 |
| Provider/API key path | Spring AI documents Gemini Developer API API-key and Vertex AI credential modes. | Initial unpaid demonstration uses only the approved provider boundary and secret injection; no credentials now. | PASS — RECHECK BEFORE LIVE QUALIFICATION |

The approved capability mapping remains accurate. **PASS — RECHECK AT PHASE 1**.

## 3. Groq non-blocking check

Official Groq documentation still lists hosted production inference models and an OpenAI-compatible Models/Chat API. This proves only that hosted inference remains a plausible later experiment. No credential, model selection, comparison, benchmark, production choice or fallback is authorized.

Disposition: **NON-BLOCKING POST-DEPLOYMENT CANDIDATE**. Phase-0 checkpoint: **PASS**.

## N. Redis/licensing table

| Check | Finding | Result |
|---|---|---|
| Version/distribution | Official ARM64 image tags include Redis 8.10.1/8.10. | PASS — RECHECK BEFORE PRODUCTION |
| Architecture role | Nothing found requires Redis to become authoritative; same-VM transient rate limiting/dedupe/cache remains plausible and optional. | PASS |
| Licensing | Redis 8 offers RSALv2, SSPLv1 or AGPLv3. The public portfolio is not a managed Redis service, and unmodified separate operation presents no obvious blocker, but the selected option/obligations must be recorded before introduction. | PASS — RECHECK BEFORE PRODUCTION |
| Failure posture | PostgreSQL remains authoritative; Redis loss must activate approved safe fallback/fail-closed behavior. | PASS |

Redis checkpoint: **PASS — RECHECK BEFORE PRODUCTION**.

## 4. Object Storage / S3 portability

OCI officially supports an Amazon S3 Compatibility API for Object Storage, including S3 tools, configurable OCI endpoints/regions, and customer secret keys. Data is encrypted at rest by default. This supports the approved application S3-compatible port to a private OCI bucket. It does not authorize a public bucket, direct client authority, credentials, or provisioning.

Object-storage checkpoint: **PASS — RECHECK BEFORE PRODUCTION**.

## O. ARM64 feasibility table

| Component | Documentary feasibility | Executable obligation | Result |
|---|---|---|---|
| Java 25 runtime | Temurin supplies Linux AArch64 builds; Java bytecode/runtime posture has no discovered architecture conflict. | Build and start on Linux ARM64. | PASS — RECHECK AT PHASE 1 |
| PostgreSQL 18.6 | Official ARM64 PostgreSQL image tags include 18.6. | Start, migrate, query and restore on ARM64. | PASS — RECHECK AT PHASE 1 |
| pgvector 0.8.6 | Source build supports PostgreSQL 18; controlled image/source paths are documented, with portable compiler flags available. | Prove `linux/arm64` image/build, extension 0.8.6 and vector queries. | PASS — RECHECK AT PHASE 1 |
| Redis 8.10.x | Official ARM64 Redis image tags exist. | Only if Redis is introduced, run transient/failure smoke. | PASS — RECHECK BEFORE PRODUCTION |
| Application image/tooling | Docker/OCI image ecosystem supports multi-architecture images. | Phase 1 packaging spike and Phase 11 release-image proof; no image created now. | PASS — RECHECK AT PHASE 1 |
| Frontend runtime | Node is required to build the SPA, not in the approved production runtime after packaging into the Spring Boot artifact. Node 24.21.0 nevertheless has Linux ARM64 binaries. | Build reproducibly; assert no Node process/runtime in production artifact. | PASS — RECHECK AT PHASE 1 |
| GitHub runner | `ubuntu-24.04-arm` is currently documented as a standard ARM64 runner. | Schedule and prove native ARM64 release work once repository exists. | PASS — RECHECK AT PHASE 1 |

ARM64 conclusion: documentary feasibility is established. Executable ARM64 smoke remains required in Phase 1 and full release proof in Phase 11. **PASS — RECHECK AT PHASE 1**.

## P. Public-repository workspace safety audit

The audit was restricted to the project root. It enumerated the actual workspace before this evidence file existed, reviewed file types/names, searched for high-confidence credential/private-key patterns without printing values, and categorized the content. All 19 files were Markdown authority/design documents. No `.env`, key/certificate, database, dump, log, archive, binary, source, or infrastructure-export file was present.

FILES INSPECTED: **19**  
Potential secrets: **none found**  
Private personal data: **none found**  
Employer/client-confidential material: **none found**  
Legacy source: **none found**  
Production dumps/logs: **none found**  
Git initialized: **NO**  
Application source exists: **NO**

The documentation contains security terminology and deliberately synthetic examples; these are not operational credentials or real private Notes.

Public-safety checkpoint: **PASS**.

## Q. STOP AND REVIEW register

| Potential blocker | Finding | Disposition |
|---|---|---|
| Approved version unavailable or contradicted | None found. Newer releases do not invalidate the frozen obtainable versions. | PASS |
| Architecture/baseline/count conflict | None found. 17 Approved Baselines remain authoritative; no relation/API/product decision was changed. | PASS |
| License blocker | None found in this bounded review. Redis requires explicit license/compliance recheck before introduction. | PASS — RECHECK BEFORE PRODUCTION |
| Public-repository secret/private data | None found in the 19 pre-output workspace files. | PASS |
| Automatic-spend or zero-cost invalidation | No current public policy invalidates the design, but GitHub/OCI/provider account controls cannot be proven until their authorized phases. | PASS — RECHECK BEFORE PRODUCTION |
| Gemini required capability removed | No; current docs expose required multimodal embedding capability. Exact model access/lifecycle remains live-qualified. | PASS — RECHECK BEFORE LIVE QUALIFICATION |
| Gemini unpaid privacy posture | Current terms confirm the approved synthetic/public-safe/non-sensitive restriction remains required. | PASS — RECHECK BEFORE LIVE QUALIFICATION |
| Free hostname/OIDC impossibility | Not established; DuckDNS TXT plus Let's Encrypt is a plausible candidate, but the actual hostname must pass Google qualification. | PASS — RECHECK BEFORE LIVE QUALIFICATION |

Active `STOP AND REVIEW` blockers: **none**.  
Required baseline amendment before implementation: **none identified**.

## R. Phase-1 prerequisites

### READY NOW

- Java/Javac 25.0.4.
- Maven 3.9.16.
- Git executable 2.51.0 (workspace intentionally remains uninitialized).
- Accepted `AGENTS.md`, 17 Approved Baselines, approved Roadmap, and this draft evidence for human review.

### MUST ALIGN BEFORE PHASE 1 EXECUTION

- Switch/install Node.js 24.21.0 LTS with bundled npm 11.19.0 before creating `package-lock.json`.
- Align Docker Engine and Compose to supported current releases satisfying the approved tool baseline before any container/Testcontainers smoke.
- Obtain separate human authorization for Phase 1; that authorization must independently cover Git initialization and scaffolding if intended.

### PHASE-1 EXECUTABLE SMOKE REQUIRED

- Boot 4.1.1/Java 25/Maven 3.9.16 minimal build and start with no preview features.
- Boot-managed dependency generation and Jackson 3/Jakarta/Tomcat checks.
- Spring Modulith 2.1.1 verification/test/documentation modules.
- Spring AI 2.0.1 fake Google chat/text-embedding integration and narrow multimodal adapter contract.
- springdoc 3.1.1 OpenAPI JSON/UI on Boot 4.1.1/Jackson 3.
- Testcontainers 2.0.5 with `pgvector/pgvector:0.8.6-pg18-trixie`, PostgreSQL 18.x and extension assertions.
- PostgreSQL UUIDv7 and `pg_trgm` behavior.
- Clean Node/npm lock/install, strict TypeScript, Vite build, Vitest/Testing Library and approved frontend imports/peer ranges.
- Playwright 1.63.0 browser smoke on a CI-equivalent supported environment.
- Initial Linux ARM64 build/runtime feasibility, followed by full Phase 11 release proof.
- Prometheus-format Micrometer adapter only when added under the approved Observability boundary.

### ACCOUNT-SPECIFIC LATER CHECK

- GitHub repository visibility, standard runner availability, security features, storage retention and zero-spend controls when the repository exists.
- Brevo account/sender/domain/TLS/quota in Phase 3 live qualification.
- Google Auth Platform/consent/authorized domain/exact redirect/PKCE/ownership in the authorized live OIDC phase.
- OCI tenancy/home-region A1 capacity and every free entitlement at Phase 13.
- Exact public hostname, TXT ownership proof and Let's Encrypt issuance before production.
- Gemini project/model list, quotas, region, billing-off state, data-use terms and synthetic-only smoke before live use.

### PRODUCTION-TIME RECHECK

- Every provider free tier, model lifecycle, license/security patch, OCI allocation, GitHub/GHCR policy and hard zero-spend guardrail.
- Redis exact 8.10.x security patch, ARM64 artifact and license choice if measurements justify introducing Redis.
- Restore rehearsal, operational capacity, quotas, alerts, privacy and all approved release gates.

Phase-1 prerequisite checkpoint: **PASS — RECHECK AT PHASE 1**.

## 5. Official source register

All sources in this register were accessed/revalidated on **2026-09-18**. Each proves only the dated fact stated.

| Provider/project | Document/page | Official URL | What it proves |
|---|---|---|---|
| Spring Boot | System Requirements | https://docs.spring.io/spring-boot/system-requirements.html | Boot 4.1.1 Java/Maven/servlet requirements and supported Java range. |
| Spring Boot | 4.1.1 dependency POM | https://repo1.maven.org/maven2/org/springframework/boot/spring-boot-dependencies/4.1.1/spring-boot-dependencies-4.1.1.pom | Managed ecosystem versions. |
| Spring Modulith | 2.1.1 tagged POM/reference | https://github.com/spring-projects/spring-modulith/blob/2.1.1/pom.xml | Version existence and Boot alignment evidence. |
| Spring AI | 2.0.1 release/reference | https://spring.io/blog/2026/08/21/spring-ai-2-0-1-available-now/ | Release existence and Boot 4 generation posture. |
| Spring AI | Google GenAI Chat | https://docs.spring.io/spring-ai/reference/api/chat/google-genai-chat.html | Google chat/media/provider integration surface. |
| Spring AI | Google GenAI Text Embeddings | https://docs.spring.io/spring-ai/reference/api/embeddings/google-genai-embeddings-text.html | Text-only integration and pending multimodal support. |
| Spring Boot | Actuator Metrics / Prometheus | https://docs.spring.io/spring-boot/reference/actuator/metrics.html | Boot-managed Micrometer registries and Prometheus endpoint adapter. |
| Maven | 3.9.16 release notes | https://maven.apache.org/docs/3.9.16/release-notes.html | Approved Maven release existence. |
| PostgreSQL | 18.6 release / support | https://www.postgresql.org/docs/release/18.6/ | Patch existence and release information. |
| PostgreSQL | Versioning policy | https://www.postgresql.org/support/versioning/ | PostgreSQL 18 support horizon. |
| PostgreSQL | UUID functions | https://www.postgresql.org/docs/18/functions-uuid.html | Native UUIDv7 generation/extraction support. |
| PostgreSQL | `pg_trgm` | https://www.postgresql.org/docs/18/pgtrgm.html | Required trigram extension availability. |
| pgvector | README / metadata / license | https://github.com/pgvector/pgvector | Version 0.8.6, PostgreSQL 18, install/image paths and portability. |
| Flyway | PostgreSQL database | https://documentation.red-gate.com/fd/postgresql-database-277579325.html | PostgreSQL 18 verified support and separate database module. |
| Testcontainers | Java documentation | https://java.testcontainers.org/ | Current Docker-backed Java integration-testing posture. |
| springdoc | Official documentation | https://springdoc.org/ | 3.1.1 and Boot 4 generation claim. |
| Node.js | 24.21.0 archive | https://nodejs.org/en/download/archive/v24.21.0 | LTS release, bundled npm 11.19.0, x64/ARM64 artifacts. |
| npm | Approved frontend package pages | https://www.npmjs.com/ | Exact package-version existence and published metadata. |
| Playwright | Introduction/system requirements | https://playwright.dev/docs/intro | Supported installation/runtime posture. |
| Eclipse Adoptium | FAQ | https://adoptium.net/docs/faq | Temurin no-cost GPLv2+Classpath Exception license. |
| PostgreSQL | License | https://www.postgresql.org/about/licence/ | PostgreSQL's permissive license terms. |
| Redis | Licenses | https://redis.io/legal/licenses/ | Redis 8 tri-license and obligation categories. |
| Redis / Docker Official Images | ARM64 Redis tags | https://hub.docker.com/r/arm64v8/redis | Redis 8.10.1 Linux ARM64 distribution. |
| Docker Official Images | ARM64 PostgreSQL tags | https://hub.docker.com/r/arm64v8/postgres | PostgreSQL 18.6 Linux ARM64 distribution. |
| GitHub | Actions billing | https://docs.github.com/en/billing/concepts/product-billing/github-actions | Free public standard runners, artifact/cache allowances and spending behavior. |
| GitHub | Hosted runners | https://docs.github.com/en/actions/reference/runners/github-hosted-runners | `ubuntu-24.04` and `ubuntu-24.04-arm` labels. |
| GitHub | Packages billing | https://docs.github.com/en/billing/concepts/product-billing/github-packages | Public package policy and current GHCR container-image storage/bandwidth treatment. |
| GitHub | Dependency review | https://docs.github.com/en/code-security/concepts/supply-chain-security/dependency-review | Public repository availability. |
| GitHub | Artifact attestations | https://docs.github.com/en/actions/how-tos/secure-your-work/use-artifact-attestations/use-artifact-attestations | Public build-provenance capability. |
| Oracle OCI | Always Free Resources | https://docs.oracle.com/en-us/iaas/Content/FreeTier/freetier_topic-Always_Free_Resources.htm | A1, block/object storage, requests, monitoring/logging, notifications, dashboards, synthetics, egress and Bastion checkpoints. |
| Oracle OCI | S3 Compatibility API | https://docs.oracle.com/en-us/iaas/Content/Object/Tasks/s3compatibleapi.htm | S3-compatible private Object Storage application path. |
| Oracle OCI | Budgets | https://docs.oracle.com/en-us/iaas/Content/Billing/Concepts/budgetsoverview.htm | Budgets are soft limits/alerts, not hard spending stops. |
| Brevo | Free-plan limits | https://help.brevo.com/hc/en-us/articles/208580669-FAQs-What-are-the-limits-of-the-Free-plan | Current 300-email/day Free plan. |
| Brevo | Transactional SMTP | https://help.brevo.com/hc/en-us/articles/7924908994450-Send-transactional-emails-using-Brevo-SMTP | Authenticated SMTP relay/ports and setup. |
| Google Identity | OAuth 2.0 web-server apps | https://developers.google.com/identity/protocols/oauth2/web-server | Authorization Code, exact redirect URI and state guidance. |
| Google Identity | OpenID Connect | https://developers.google.com/identity/openid-connect/openid-connect | ID token, nonce, issuer/audience/expiry validation. |
| Google Cloud | OAuth app domain verification | https://support.google.com/cloud/answer/13804266 | Authorized-domain/Search Console ownership requirements. |
| DuckDNS | Specification | https://www.duckdns.org/spec.jsp | Free hostname update service and TXT-record API. |
| Let's Encrypt | Challenge types | https://letsencrypt.org/docs/challenge-types/ | ACME HTTP-01 and DNS-01 ownership proof. |
| Let's Encrypt | Documentation | https://letsencrypt.org/docs/ | Free automated CA posture. |
| Google Gemini | Models | https://ai.google.dev/gemini-api/docs/models | Current model candidates and lifecycle-sensitive IDs. |
| Google Gemini | Embeddings | https://ai.google.dev/gemini-api/docs/embeddings | Embedding 2 modalities and limits. |
| Google Gemini | Embedding 2 model card | https://ai.google.dev/gemini-api/docs/models/gemini-embedding-2 | Candidate status, dimensions and multimodal capability. |
| Google Gemini | Pricing | https://ai.google.dev/gemini-api/docs/pricing | Free-tier availability and free/paid data-use distinction. |
| Google Gemini | Rate limits | https://ai.google.dev/gemini-api/docs/rate-limits | Tier/model/project-dependent quota dimensions. |
| Google Gemini | Additional Terms | https://ai.google.dev/gemini-api/terms | Unpaid-service data use, human review and sensitive-content warning. |
| Groq | Supported Models | https://console.groq.com/docs/models | Hosted inference remains available as a later experiment. |

## S. Final implementation-entry decision

No dated official evidence found an architectural, product, security, licensing, provider-capability, public-safety, or zero-cost-policy contradiction that requires stopping before ordinary local implementation. The frozen architecture is therefore eligible to proceed to human review of Phase 0 evidence.

This decision does **not** mark Phase 0 complete, authorize Phase 1, authorize Git initialization, authorize scaffolding, or mutate any baseline/handoff/operating-contract status. Human review must decide completion and issue separate Phase 1 authority.

PHASE 0 ENTRY DECISION: PASS FOR HUMAN REVIEW

## Human completion finalization

Human review accepted the PASS evidence on 2026-09-19. Roadmap Phase 0 is now **COMPLETE**. This completion does not authorize Phase 1; Phase 1 remains **NOT STARTED** and requires separate explicit authorization.
