# ADR-001 — Architecture Style and Deployables

Status: Approved Baseline  
Date: 2026-09-10  
Baseline approval date: 2026-09-10  
Decision authority: Project owner following human review  
Product authority: Approved `docs/product/Product_Vision_and_Target_Flagship_Requirements.md`

## Context

Notes & Knowledge Workspace is a private-first notes and personal-knowledge product. Its Target Flagship combines authorization-sensitive Markdown notes, bounded image/audio/video/PDF attachments, ordinary and semantic retrieval, grounded Ask My Knowledge, durable indexing and deindexing, secure identity and session management, explicit publication snapshots, and a deliberately small public discovery surface.

Several workflows cross product capabilities while requiring consistent state. Examples include saving a note while scheduling its indexing work, creating a publication snapshot from a retained note checkpoint, unpublishing while scheduling removal from public retrieval, changing security settings while applying session consequences, and changing an attachment reference while scheduling cleanup. AI participation is owned by each note and must be rechecked before AI processing or retrieval. Authorization must constrain candidates before every private retrieval path.

The architecture must preserve those guarantees without creating operational machinery unsupported by current evidence. This is a public portfolio project expected to be built and operated primarily by one developer or a small project team. It should demonstrate explicit boundaries, reliable asynchronous work, testability, and sound tradeoffs—not technology count.

This ADR decides the initial backend architecture style and application deployable topology. It does not design the domain model, schema, APIs, packages, algorithms, providers, infrastructure hosting, or implementation roadmap.

## Decision Drivers

- Keep ownership and authorization rules close to private notes and every direct or derived retrieval path.
- Make cross-user isolation testable across notes, attachments, indexes, citations, caches, jobs, and public projections.
- Support local ACID consistency for product workflows that combine authoritative mutations with durable follow-up work.
- Accommodate Markdown plus bounded image, audio/voice, video, and PDF processing without assuming separate services.
- Keep ordinary search, semantic/hybrid retrieval, grounded answers, and publication behavior coherent under one authorization model.
- Enforce independent per-note AI participation, including durable indexing/deindexing and stale-work revalidation.
- Isolate provider failures so healthy core note capabilities remain available.
- Establish clear internal ownership, explicit interfaces, and independently testable modules.
- Remain reproducible and operationally understandable for a primary developer or small project team.
- Preserve credible future extraction seams without paying distributed-system costs before evidence justifies them.
- Demonstrate mature architecture in a public repository without artificial microservices or unsupported scale claims.

## Considered Options

### Option A — Traditional unstructured Spring Boot monolith

One application and database would minimize deployment complexity and retain local transactions. However, an unstructured codebase would provide weak ownership boundaries for security-sensitive retrieval, identity, notes, publishing, and knowledge processing. Tests would have difficulty proving which dependencies are allowed, repository access could spread across features, and later extraction seams would be accidental rather than designed.

This option is operationally simple but structurally insufficient for the product.

### Option B — Domain-oriented modular monolith with Spring Modulith

One Spring Boot application is divided into cohesive domain-oriented modules with explicit supported interfaces and conceptually owned durable state. Spring Modulith assists with structural verification, module-oriented tests, documentation, and selected event publication. Synchronous calls remain available for transactional business operations; asynchronous events are used only where decoupled reactions are useful.

This option retains local transactions and a single authorization boundary while making dependencies reviewable and testable. It creates deliberate seams for later extraction without pretending those seams are network services today.

### Option C — Multiple Spring Boot microservices from the beginning

Separate services could provide independent deployment, scaling, and process-level failure isolation. Those benefits matter when distinct workloads, ownership, or release cadences are measured.

At the current stage, this option would introduce network failures, service identity and authorization propagation, distributed transaction or eventual-consistency problems, more configuration and deployment surfaces, harder local reproduction, and broader integration testing. The project has no evidence of independent teams or scaling profiles that would repay those costs. Cross-service authorization drift would be a particular risk for private retrieval and AI participation.

### Option D — Modular monolith with a separately deployed AI/Knowledge service

Knowledge and media workloads may eventually have a distinct resource profile, and process isolation could later protect interactive requests from expensive work. A separate service now would nevertheless add a trust boundary, duplicate or remotely propagate identity and authorization context, and complicate consistency for note AI state, indexing, deindexing, and deletion. Remote AI-provider calls do not themselves require the application to expose an internal AI network service.

Provider abstraction and durable asynchronous work can meet current needs while Knowledge remains an internal module.

### Option E — Modular monolith with a separately deployed generic background worker

A separate worker could scale job execution independently and isolate CPU- or memory-heavy processing. Durability, however, comes from persisted work state rather than from placing an executor in another process. At the current scale, another deployable would add release, configuration, observability, and recovery complexity without measured contention or throughput need.

The executor can be separated later while retaining the same durable work concepts.

## Decision

Adopt a **domain-oriented modular monolith implemented as exactly one initial Spring Boot backend deployable**.

Use Spring Modulith to help verify, test, and document internal module boundaries. The Spring Boot deployable initially contains:

- HTTP/API application functionality.
- Domain and application modules.
- Security and server-side session integration.
- Knowledge/AI orchestration and provider abstractions.
- A bounded executor for durable background work.

Knowledge/AI and the background executor are internal parts of this deployable, not independently deployed services.

The application initially uses one PostgreSQL physical database. A shared physical database does not grant shared ownership of all durable state. Modules own their state conceptually and may not reach across boundaries through another module's repositories.

No API gateway, RabbitMQ broker, or Kubernetes platform is part of the initial architecture. None is prohibited forever; none is justified by the approved product and current operating reality.

This decision is materially different from an unstructured monolith. A modular monolith has explicit domain boundaries, permitted dependencies, supported interfaces, module-level tests, and enforceable ownership rules. Spring Modulith supports those properties; it does not turn modules into simulated microservices or require all collaboration to be asynchronous.

## What Counts as a Deployable

For this ADR, an application deployable is a software unit independently versioned, configured, released, and operated as an application process or service role.

The initial **backend application deployable count is one**. Running multiple identical instances of that same application for availability or capacity would not create a new deployable type or service boundary.

The following are supporting dependencies, not additional Notes & Knowledge Workspace backend deployables:

- PostgreSQL.
- Redis where later justified within its approved transient roles.
- S3-compatible object storage.
- An email delivery provider.
- Local or external AI/model providers.

The React and TypeScript frontend is compiled browser client software and static web assets. It is not a backend service boundary. Whether those assets are served by the Spring Boot application or hosted separately is a downstream deployment decision. Frontend/backend separation does not imply microservices. CDN, reverse proxy, domains, server-side rendering, backend-for-frontend, hosting provider, and exact production topology remain undecided.

## Internal Modular Structure

The initial domain-oriented module direction is:

- **Identity** — authentication, account security, sessions, verification, recovery, and MFA responsibilities.
- **Profile** — private account-facing profile management and approved public profile representation.
- **Notes** — private notes, versions, tags, attachments, and note lifecycle responsibilities.
- **Publishing** — deliberate publication snapshots and their lifecycle.
- **Discovery** — the bounded public discovery and engagement surface.
- **Knowledge** — search/AI orchestration, derived representations, provenance-aware retrieval, and indexing coordination.
- **Moderation** — bounded reporting and moderation responsibilities.

These names describe responsibility boundaries, not frozen Java packages, aggregates, repositories, tables, routes, or DTOs.

Each module exposes deliberate application interfaces. A module must not use another module's repository as an integration shortcut. Collaboration may use:

- Direct synchronous application interfaces where the business operation is synchronous or requires a local transaction.
- Purpose-built projections where a consuming module needs a stable read shape.
- Selected events where a reaction is genuinely decoupled and timing permits it.

Spring Modulith is intended to provide module-boundary verification, architecture tests, module-oriented integration testing, generated structural documentation/diagrams, and controlled event publication where useful. It does not mandate distributed messaging, event-driven choreography, or an event for every method call.

## Deployable Topology

The logical initial topology is:

`React browser client -> one Spring Boot backend deployable -> supporting infrastructure and configured providers`

The backend is the single application authorization boundary for private resources. PostgreSQL is the durable source of truth. Object storage holds authorized binary objects under application-enforced access rules. Redis may support approved transient concerns but is never authoritative. AI and email providers may be remote or local dependencies without becoming internal application services.

This logical topology does not select a cloud, host count, network layout, reverse proxy, domain arrangement, or deployment vendor.

### Redis boundary

Redis remains an optional external supporting dependency for transient deduplication, distributed rate controls, and measured cache use where later design justifies it. It is not the source of truth, note store, vector store, durable session authority, durable job queue, or permanent owner of engagement data. PostgreSQL remains authoritative for durable product state.

## Background Processing Model

The product requires durable asynchronous work for potential job families such as:

- Text and supported-media indexing, reindexing, and deindexing.
- Transcription or extraction where selected downstream.
- Retryable email delivery.
- Attachment and avatar cleanup.
- Public projection work.
- Bounded persistence of approximate view aggregates.

Durable work state and executor placement are separate decisions. Work requiring recovery is represented by PostgreSQL-backed durable job records scheduled consistently with the originating mutation where needed. A bounded executor or worker loop inside the same Spring Boot deployable initially claims and executes that work.

This preserves retry visibility and recovery across process restarts without requiring a second application deployable. Jobs must be able to revalidate current authorization, note AI state, publication state, and other relevant ownership conditions before producing or exposing derived results. Exact job tables, states, claims, leases, retries, and concurrency policies are downstream decisions.

Spring Modulith events may notify internal observers or help coordinate selected reactions. They do not replace persisted workload state when losing the work would violate a product guarantee.

RabbitMQ is not required merely because background work exists. PostgreSQL-backed durable work supports initial transactional scheduling, persistence, retry visibility, and state revalidation with less operational surface. A broker can be reconsidered only if later workload or integration evidence shows a material need that durable database jobs cannot satisfy cleanly.

## Data Ownership Implications

Use one PostgreSQL physical database initially because local transactions, operational simplicity, and reproducible tests are valuable for this product. Physical co-location does not erase module ownership.

Architectural rules are:

- Each module conceptually owns its durable state.
- No module accesses another module's repositories directly.
- Cross-module behavior uses approved application interfaces, projections, or selected events.
- Shared transactions are allowed where a synchronous business invariant genuinely spans approved module interfaces.
- Read models and derived representations must preserve source ownership and authorization constraints.
- The database is not a universal integration API between modules.

This ADR does not require a PostgreSQL schema per module and does not define tables, keys, relationships, migrations, or persistence technology details.

## Transactional Advantages

Local ACID transactions reduce the number of partially completed states that must be reconciled during early product development. Conceptual examples include:

- Persisting an explicit note Save and scheduling required indexing work.
- Creating or updating a publication snapshot with its source checkpoint and provenance.
- Unpublishing and scheduling removal from public retrieval.
- Applying a security change and its required session-revocation consequences.
- Activating MFA and creating recovery codes as one consistent outcome.
- Changing attachment or avatar references and scheduling cleanup safely.

Premature service boundaries would turn several of these into distributed workflows requiring message delivery guarantees, idempotent consumers, compensating actions, and prolonged intermediate states. Those patterns can be justified when independent services deliver measured value; they are avoidable complexity now.

## AI, Knowledge, and Multimodal Placement

Knowledge/AI remains internal to the backend deployable initially. It may call configured local or remote providers through internal abstractions, but provider location does not dictate application-service topology.

Keeping Knowledge internal initially provides:

- One place to enforce ownership and retrieval authorization.
- Direct access to approved application interfaces without copying identity context across a network boundary.
- Consistent scheduling and revalidation when a note's independent AI state changes.
- Fewer trust boundaries for private text and supported media.
- Easier local reproduction and end-to-end testing.
- Graceful isolation of provider failure from core note behavior without a separate service contract.

Images, audio/voice recordings, bounded video clips, and PDFs do not automatically require media, transcription, embedding, or AI services. Object storage plus durable asynchronous jobs are sufficient initial architectural building blocks. Materially different resource needs or failure modes may justify extraction later.

Provider-agnostic design remains required inside the Knowledge module. This ADR does not select a chat model, embedding model, provider, vector dimensions, routing policy, or processing location.

## Security Effects

One backend authorization boundary is easier to reason about initially than multiple internal service identities and network APIs. Retrieval authorization and note-level AI checks can remain close to authoritative ownership state, and fewer internal endpoints reduce attack surface.

These benefits do not make a monolith automatically secure. Module verification prevents forbidden structural coupling but does not prove correct authorization. Subsequent Security Architecture and Threat Model documents must define explicit controls, and tests must prove cross-user isolation across direct access, ordinary and semantic search, AI context, background work, caches, derived representations, provenance, and publication paths.

The chosen topology must preserve these product invariants:

- Authorization constrains candidates before retrieval.
- Each note's AI ON/OFF state governs AI processing independently of the future-note default.
- AI-disabled notes remain available to authorized deterministic non-AI operations.
- AI/provider failure does not disable healthy core note behavior.
- Publication exposes only deliberate snapshots and approved media.

## Consequences

### Positive Consequences

- Local transactions simplify consistency for security-, publication-, and indexing-sensitive workflows.
- One application authorization boundary reduces identity propagation and internal-network attack surface.
- One backend artifact is easier to build, test, deploy, observe, and reproduce from a clean checkout.
- Module verification and ownership rules provide structure beyond an unorganized monolith.
- End-to-end and integration testing require fewer distributed fixtures and failure modes.
- Provider abstraction and durable work remain possible without a dedicated AI service or worker.
- Explicit module interfaces create future extraction seams while keeping today's operations proportionate.

### Costs and Tradeoffs

- One process has a larger failure domain: a severe in-process fault can affect more capabilities.
- Background work can compete with interactive API requests for CPU, memory, database connections, and provider concurrency.
- Modules share deployment cadence and cannot be released independently.
- Individual modules cannot be independently scaled as distinct service roles.
- Strong discipline and automated checks are required to prevent boundary erosion.
- One physical database can invite accidental cross-module coupling if repository ownership is not enforced.
- Process-level isolation for expensive media or AI work is limited initially.
- Later extraction will require contract, data-ownership, deployment, and migration work.

These costs are acceptable because no current evidence demonstrates separate scaling, ownership, or availability needs. Bounded execution, failure isolation around external providers, module verification, observable workload impact, and explicit extraction criteria reduce the risk while preserving a simpler initial system.

## Rejected Alternatives

### Unstructured monolith

Rejected because low deployment complexity alone does not protect domain ownership or authorization-sensitive retrieval. The product requires enforceable module boundaries, supported interfaces, and module-level tests.

### Microservices from day one

Rejected because the project lacks measured independent-scaling or team-ownership requirements. Immediate services would add network failure, distributed authorization, eventual consistency, more deployment/configuration surfaces, and harder local reproduction without a compensating product benefit.

### Separate AI/Knowledge service from day one

Rejected because provider abstraction, bounded calls, and durable jobs meet the current need without another private-data trust boundary. It would complicate note-level AI-state enforcement and deindexing consistency before process isolation is shown to be necessary.

### Separate generic worker from day one

Rejected because durable work state does not require an independently deployed executor. A separate worker would add operational surface before queue throughput, resource contention, or deployment interruption demonstrates value.

## Service Extraction Criteria

Independent deployment of a module should be considered only when several meaningful signals exist. No single criterion automatically approves extraction, and no arbitrary numeric threshold is established here.

Relevant evidence includes:

- A materially independent scaling profile.
- CPU, GPU, memory, storage, or concurrency needs substantially different from the interactive application.
- Failure modes requiring process- or topology-level isolation.
- A meaningfully separate deployment cadence.
- A stable, narrow API or event boundary.
- Data ownership that can be separated without constant cross-service joins or distributed transactions.
- Independent operational ownership and on-call responsibility.
- Sustained workload throughput requiring a separately managed fleet.
- Measured contention that harms interactive requests.
- Provider processing that requires a distinct security or network topology.
- A demonstrated cost, availability, or resilience benefit from independent scaling.

Knowledge/AI is the most plausible future extraction candidate because media and model workloads may develop a distinct resource and failure profile. It is not pre-approved for extraction. A superseding approved ADR must evaluate the evidence and resulting authorization, data ownership, consistency, deployment, and observability costs.

## Worker Extraction Criteria

The internal executor may become a separately deployed worker role when evidence such as the following makes that change valuable:

- Background execution causes unacceptable measured latency or resource pressure on interactive requests.
- Worker concurrency or throughput needs independent scaling.
- Expensive media processing creates material CPU, memory, or accelerator contention.
- Application deployments interrupt long-running work beyond acceptable recovery behavior.
- Different resource classes or placement constraints have clear operational value.
- Persistent queue depth, processing time, or recovery measurements justify dedicated capacity.
- Process-level fault isolation materially improves availability.

Extraction must retain durable work state, idempotency, current-state revalidation, authorization, visibility, and recovery guarantees. Until such evidence exists, execution remains in the same backend deployable.

## Validation and Fitness Functions

The decision remains healthy when the following high-level checks hold:

- Spring Modulith verification passes for the approved module graph.
- Architecture tests prevent forbidden module dependencies.
- No cross-module repository access exists.
- Module-oriented tests can exercise supported interfaces without unrelated internals.
- Cross-user isolation tests pass independently of which module initiates retrieval or work.
- Note-level AI participation is revalidated before derived processing and retrieval.
- Background workload impact on interactive latency and resource use is measurable.
- Durable job depth, age, processing time, retry, and failure state are observable without logging private content.
- AI/provider failure cannot make healthy core note capabilities unavailable.
- A clean checkout can reproduce the documented application topology.
- The initial release produces exactly one backend application deployment artifact.
- No additional application service or worker is introduced without a superseding approved ADR.

These are architectural validation mechanisms, not claims of already measured performance.

## Downstream Decisions and Out of Scope

ADR-001 deliberately defers:

- Exact Spring Boot, Spring Modulith, Java, and dependency versions.
- Exact Java package structure and module API layout.
- Domain aggregates, entities, repositories, and detailed ownership mappings.
- Database schema, tables, per-module PostgreSQL schemas, and Flyway migrations.
- API routes, DTOs, contracts, and authentication implementation details.
- Redis keys, TTLs, cache policy, and rate-limit mechanisms.
- Durable-job table, state machine, leasing, retry, and executor implementation.
- Vector dimensions, index types, embeddings, chunking, fusion, reranking, and retrieval algorithms.
- Chat, embedding, transcription, and multimodal provider/model selection or routing.
- Media formats, codecs, size/duration/page limits, and quotas.
- Cloud vendor, hosting topology, domains, reverse proxy, CDN, or Kubernetes evaluation.
- Exact backend artifact or deployable name.
- CI/CD mechanisms, observability stack, and implementation roadmap.
- Exact frontend delivery topology, SSR, or backend-for-frontend decisions.

Subsequent documents may refine those choices but may not silently change the one-backend-deployable decision, modular-monolith style, internal Knowledge/AI placement, or same-deployable background executor. Changing any of those requires a superseding approved ADR.

## Relationship to Approved Product Requirements

This decision supports, but does not replace, the Approved Baseline requirements:

- Note Save and lifecycle requirements benefit from local consistency and durable follow-up scheduling.
- Publication snapshot requirements benefit from explicit module ownership and local transaction boundaries.
- `FR-SEC-01` through `FR-SEC-14` and the isolation NFRs require authorization-aware module interfaces and topology-independent security tests.
- `FR-AI-01` through `FR-AI-45` require note-level AI-state enforcement, provider isolation, durable indexing/deindexing, and graceful degradation.
- `FR-MM-01` through `FR-MM-10` and attachment requirements are supported without assuming separate media services.
- `FR-RETR-01` through `FR-RETR-29` require Knowledge to preserve authorization, provenance, and distinct retrieval completeness semantics.
- `NFR-MAINT-01` and `NFR-MAINT-02` motivate traceable, verifiable module boundaries.
- Reliability, testing, observability, and repository-hygiene NFRs motivate durable work state and a reproducible single-backend topology.

The ADR does not alter explicit Save, publication snapshots, the future-note AI default, independent per-note AI ON/OFF, deterministic access to AI-disabled notes, the committed multimodal scope, authorization-before-retrieval, provider-agnostic AI design, or graceful AI degradation.

## Decision Summary

- **Architecture style:** Domain-oriented modular monolith.
- **Initial backend deployables:** Exactly one Spring Boot application deployable.
- **Boundary support:** Spring Modulith for verification, module tests, documentation, and selected internal events—not simulated microservices.
- **Initial data topology:** One PostgreSQL physical database with conceptual module ownership and no cross-module repository access.
- **Background work:** PostgreSQL-backed durable work with a bounded same-deployable executor initially.
- **Knowledge/AI:** Internal module initially, with provider abstraction and no dedicated service.
- **Supporting dependencies:** PostgreSQL, justified transient Redis use, object storage, email, and AI providers do not increase the backend application deployable count.
- **Not introduced:** RabbitMQ, API gateway, Kubernetes, dedicated AI service, or separate worker.
- **Evolution:** Extraction requires measured evidence and a superseding approved ADR.
