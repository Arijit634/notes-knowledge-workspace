# Notes & Knowledge Workspace

## Product Vision and Target Flagship Requirements

### 1. Document Status, Purpose and Authority

Status: Approved Baseline  
Baseline approval date: 2026-09-08  
Document type: Authoritative product requirements  
Product: **Notes & Knowledge Workspace**  
Repository slug: `notes-knowledge-workspace`

This document defines **what the finished product must do** and the user-visible guarantees it must provide. Once approved, it is the authoritative product-level input to architecture, security, domain, API, testing, deployment, and implementation planning.

This document does not define final schemas, APIs, packages, algorithms, library versions, model selections, cloud vendors, infrastructure key formats, or deployment names. Those decisions belong to downstream documents.

Authority for this draft is, in order:

1. Approved repository design documents.
2. `PROJECT_CONTEXT_HANDOFF.md`.
3. The explicit task that commissioned this document.

The scope classifications used throughout are:

- **Target Flagship:** committed behavior of the finished product.
- **Optional / Future:** genuinely undecided or deliberately deferred capability.
- **Out of Scope:** deliberately rejected for this product.

Implementation order does not alter these classifications. The finished product is one coherent target, delivered through a later phased roadmap.

### 2. Product Vision

**Notes & Knowledge Workspace** is a private-first notes application that helps people capture, organize, rediscover, and understand their own accumulated knowledge across Markdown and bounded supported media, then optionally publish deliberately approved note snapshots.

Its progression is:

`CAPTURE -> ORGANIZE -> REDISCOVER -> UNDERSTAND -> OPTIONALLY PUBLISH`

The product should feel excellent before a user invokes generative AI. Writing, editing, organizing, ordinary search, and note recovery are the foundation. AI augments rediscovery and synthesis; it does not redefine the application as a chatbot. Public discovery adds a small outward-facing surface without turning private notes into a social network.

### 3. Problem Statement

Personal notes become less useful as they accumulate. People remember fragments rather than exact wording, use shorthand, make spelling errors, scatter related items across many notes, and lose deterministic data such as saved links beneath newer content.

Existing note-taking and AI-assisted knowledge products already address parts of this category. This product does not claim to invent AI-powered notes, semantic search, or retrieval-augmented generation. Its intended distinction is trustworthy execution:

- A polished note-taking experience that remains useful without AI.
- Retrieval that tolerates imperfect memory.
- Different retrieval behavior for relevance, facts, aggregation, and exhaustive extraction.
- Authorization applied before candidate retrieval.
- Transparent per-note AI access boundaries.
- Grounded answers with navigable source provenance.
- Publication snapshots that cannot change through ordinary private editing or saving.
- Consumer security and tests that prove the promises.

### 4. Product Thesis and Positioning

Current positioning:

> A private-first notes workspace for capturing and rediscovering personal knowledge across text and supported media, with grounded AI assistance and deliberate public sharing.

The product is not a password manager, a generic chatbot, a team collaboration suite, an enterprise search platform, or a social network. Users may store arbitrary lawful text, including sensitive material, but the product must explain the implications of AI access and protect ownership boundaries consistently.

The product succeeds when a user trusts it to preserve notes, find imperfectly remembered information, explain what AI can access, cite the evidence behind answers, and publish only what the user explicitly approves.

### 5. Target Users

Primary target users are individuals who:

- Maintain a growing collection of personal Markdown notes.
- Need fast capture and editing across desktop and responsive web layouts.
- Often remember meaning, fragments, misspellings, or shorthand rather than exact text.
- Want ordinary search and optional AI-assisted rediscovery in one product.
- Capture useful knowledge in images, voice recordings, bounded video clips, and PDF attachments as well as text.
- Value explicit control over whether a note participates in AI processing.
- Want occasional public sharing without operating a social profile or publication platform.

Secondary target users include technical learners, researchers, hobbyists, and knowledge workers whose notes contain lists, references, snippets, decisions, and links spread across time.

The Target Flagship does not serve multi-user teams, collaborative documents, regulated credential storage, or enterprise content administration.

### 6. Product Principles

**P-01 — Notes first.** The private writing and retrieval experience must stand on its own.

**P-02 — User trust over novelty.** Privacy, authorization, recoverability, and honest system states take priority over impressive demos.

**P-03 — AI is explicit and bounded.** Users can see and control whether their notes participate in AI-dependent features.

**P-04 — Retrieval matches intent.** Ranked relevance, focused facts, semantic aggregation, and exhaustive deterministic extraction are different product behaviors.

**P-05 — Evidence over fluency.** Answers about a user's knowledge are grounded in authorized notes and expose meaningful provenance.

**P-06 — Authorization before retrieval.** Unauthorized content must not enter candidate sets, context, citations, caches, or derived results.

**P-07 — Private by default; publication by deliberate action.** Private editing never silently updates public content.

**P-08 — Honest completeness.** The product distinguishes what it ranked, what it found with high recall, and what it exhaustively inspected.

**P-09 — Graceful degradation.** AI failures do not disable healthy non-AI note functionality.

**P-10 — Focused scope.** Supporting security and public features strengthen the core experience without becoming separate products.

**P-11 — Deliberate persistence.** Editor-owned title and body changes become durable only when the user explicitly saves them; other explicit commands may persist immediately.

**P-12 — Knowledge across supported media.** Retrieval should help users rediscover knowledge regardless of whether they captured it as text or a supported attachment.

### 7. Product Priority Hierarchy

1. **Core product — private notes:** excellent capture, editing, organization, lifecycle management, and recovery.
2. **Core differentiator — rediscovery and grounded AI:** ordinary, fuzzy, semantic, hybrid, aggregated, and exhaustive retrieval over authorized knowledge.
3. **Supporting capabilities — identity and trust:** secure authentication, account recovery, sessions, MFA, and profiles.
4. **Secondary feature — public notes:** deliberate snapshot publication and a small discovery surface.

The application is not complete if its editor and ordinary search are weak, even if Ask My Knowledge works well. Public engagement metrics and profile polish must not displace the private note experience.

### 8. Core User Journeys

**J-01 — Capture and deliberately save.** A user creates a Markdown note, sees Unsaved Changes after editing, invokes Save or the supported keyboard shortcut, sees Saving and then Saved only after persistence succeeds, and recovers from a transient failure. Traces primarily to FR-NOTE-01 through FR-NOTE-12 and FR-NOTE-24 through FR-NOTE-26.

**J-02 — Organize and recover.** A user tags, pins, archives, trashes, restores, reviews versions, and restores earlier content without losing current intent. Traces primarily to FR-NOTE-13 through FR-NOTE-21.

**J-03 — Rediscover imperfectly remembered content.** A user searches by title, content, tag, corrected spelling, or fuller wording and navigates ranked results. Traces primarily to FR-SEARCH-01 through FR-SEARCH-10.

**J-04 — Control AI participation.** A new account defaults future notes to AI OFF. The user receives a brief AI/privacy introduction, may change the future-note default, sees each Create Note AI control initialized from that default, may override it for that note, and may later change individual or bulk existing-note states without a default change mutating existing notes. Traces primarily to FR-AI-01 through FR-AI-09 and FR-AI-36 through FR-AI-45.

**J-05 — Query personal knowledge.** A user asks a focused or broad question through the knowledge-query experience; the product routes it to an authorized deterministic or AI-dependent strategy, returns evidence-backed results, opens sources, or reports insufficient evidence honestly. Traces primarily to FR-AI-10 through FR-AI-20 and FR-RETR-01 through FR-RETR-29.

**J-06 — Extract every deterministic item.** A user asks for every occurrence of a supported deterministic pattern, receives complete authorized matches with source occurrences, and no discovered link is visited automatically. Traces primarily to FR-RETR-10 through FR-RETR-16.

**J-07 — Secure the account.** A user registers or uses Google login, verifies identity, enables MFA, manages recovery codes, reviews sessions, and revokes an unwanted session. Traces primarily to FR-ID-01 through FR-SESSION-08.

**J-08 — Publish deliberately.** A user previews a private note snapshot, publishes it, continues private editing without changing the public copy, explicitly updates it, and can unpublish it. Traces primarily to FR-PUB-01 through FR-PUB-12.

**J-09 — Discover public notes.** A visitor browses Latest or Trending, searches active public snapshots, opens public author profiles, and interacts through deliberately limited likes and approximate views. Traces primarily to FR-EXPLORE-01 through FR-ENGAGE-07.

**J-10 — Capture and rediscover supported media.** A user attaches an authorized image, voice recording, bounded video clip, or PDF, can access it even if AI processing fails or the note is AI OFF, and can semantically rediscover media from AI-enabled notes through the unified knowledge workflow. Traces primarily to FR-ATTACH-01 through FR-ATTACH-15, FR-MM-01 through FR-MM-10, and FR-AI-39.

### 9. Target Flagship Functional Requirements

#### 9.1 Identity & Authentication

- **FR-ID-01:** The product shall allow a person to register an application-managed account using an email address and password.
- **FR-ID-02:** The product shall require ownership verification of a registered email before enabling all account capabilities governed by verified status.
- **FR-ID-03:** Registration, verification, and login responses shall avoid revealing whether an unrelated email address is already registered beyond what is necessary for a usable signed-in flow.
- **FR-ID-04:** The product shall allow a registered user to authenticate with their application-managed credentials.
- **FR-ID-05:** The product shall support Google OAuth/OIDC login through a deliberate user-initiated authorization flow.
- **FR-ID-06:** Every successful login method shall resolve to one stable internal application identity used for ownership and authorization.
- **FR-ID-07:** Linking Google identity to an existing account shall require safeguards stronger than an unverified email-address match.
- **FR-ID-08:** The product shall prevent an external identity from being silently linked to the wrong internal account.
- **FR-ID-09:** Authentication failure messages and recovery entry points shall be understandable without exposing sensitive account state.
- **FR-ID-10:** The product shall provide logout from the current session.

#### 9.2 MFA & Recovery

- **FR-MFA-01:** A user shall be able to enable optional TOTP multi-factor authentication after recent authentication.
- **FR-MFA-02:** MFA enrollment shall present an authenticator-compatible QR code and an accessible alternative to scanning it.
- **FR-MFA-03:** MFA shall not become active until the user proves successful enrollment with a valid TOTP code.
- **FR-MFA-04:** A user with active MFA shall complete an MFA challenge before a pending primary authentication becomes a fully authorized session.
- **FR-MFA-05:** Successful enrollment or regeneration shall produce high-entropy one-time recovery codes and display their plaintext only during that event.
- **FR-MFA-06:** A valid unused recovery code shall satisfy an MFA recovery challenge once and shall not be reusable.
- **FR-MFA-07:** Recovery-code regeneration shall invalidate earlier unused codes and require recent authentication.
- **FR-MFA-08:** Disabling or resetting MFA shall require a deliberately defined proof-of-control flow and shall apply the approved session-security consequences.
- **FR-MFA-09:** MFA enrollment, challenge, recovery, disablement, and reset states shall have clear success, failure, expiry, and retry behavior.
- **FR-MFA-10:** Password recovery shall use a generic initiation response and an expiring, single-use recovery process.
- **FR-MFA-11:** A successful password reset shall apply the approved revocation or rotation policy to active sessions.
- **FR-MFA-12:** Password recovery for an account with active MFA shall follow an explicit recovery policy and shall not silently bypass MFA.

#### 9.3 Sessions & Account Security

- **FR-SESSION-01:** An authenticated browser shall use a revocable server-side session rather than exposing reusable authentication tokens to browser storage.
- **FR-SESSION-02:** A user shall be able to view active or recent sessions with enough privacy-conscious device/browser and activity information to recognize them.
- **FR-SESSION-03:** The session list shall identify the current session.
- **FR-SESSION-04:** A user shall be able to revoke one other session.
- **FR-SESSION-05:** A user shall be able to revoke all other sessions while retaining the current one when policy permits.
- **FR-SESSION-06:** A revoked or expired session shall no longer authorize access to private resources.
- **FR-SESSION-07:** Sensitive account changes shall require recent authentication where the approved security policy identifies elevated risk.
- **FR-SESSION-08:** State-changing browser actions shall be protected against cross-site request forgery without making normal workflows unusable.

#### 9.4 Profiles

- **FR-PROFILE-01:** A user shall have an immutable internal identity that is not replaced by a public handle or email address for ownership checks.
- **FR-PROFILE-02:** A private-only user shall not be required to choose a public handle during account creation; before first publication or public-profile activation, the user shall establish a valid unique public handle.
- **FR-PROFILE-10:** A public handle shall be public identity and routing metadata only; ownership and authorization shall always use immutable internal identity.
- **FR-PROFILE-03:** A user shall be able to edit their display name and short biography within documented content limits.
- **FR-PROFILE-04:** A user shall be able to upload, replace, and remove a profile avatar.
- **FR-PROFILE-05:** Public avatar output shall use a validated and sanitized display asset rather than exposing an untrusted original upload.
- **FR-PROFILE-06:** A public profile shall expose only approved public fields and the user's active published notes.
- **FR-PROFILE-07:** Private account settings shall be distinct from public profile editing.
- **FR-PROFILE-08:** Security and session settings shall be distinct from public profile output.
- **FR-PROFILE-09:** Public pages and responses shall not expose private email, authentication identities, MFA state, recovery data, session data, or moderation internals.

#### 9.5 Notes & Editor

- **FR-NOTE-01:** An authenticated user shall be able to create a private Markdown note.
- **FR-NOTE-02:** A user shall be able to edit the title and Markdown body of a note they own.
- **FR-NOTE-03:** The editor shall provide a genuine Markdown authoring experience with readable structure and rendered-content confidence; a plain title field, undifferentiated textarea, and manual Save button alone shall not satisfy this requirement.
- **FR-NOTE-04:** Editing the title, Markdown body, or comparable editor-owned content shall create a visible Unsaved Changes state until the user deliberately invokes Save.
- **FR-NOTE-05:** The editor shall provide an obvious Save action and shall visibly distinguish Unsaved Changes, Saving, Saved, and Save Failed states.
- **FR-NOTE-06:** After a transient save failure, the active editor shall retain the user's current edited content and provide a recoverable Retry Save path.
- **FR-NOTE-07:** The product shall not represent a failed save as saved.
- **FR-NOTE-08:** Concurrent edits shall use optimistic conflict detection rather than silently overwriting a newer persisted revision.
- **FR-NOTE-09:** When a conflict occurs, the product shall preserve enough information for the user to understand and deliberately resolve it.
- **FR-NOTE-10:** Note lists and editor transitions shall provide designed loading, empty, and error states.
- **FR-NOTE-11:** Core note creation, editing, navigation, and formatting workflows shall be keyboard accessible.
- **FR-NOTE-12:** The notes experience shall adapt to supported desktop and smaller responsive layouts without hiding essential persistence or recovery state.
- **FR-NOTE-13:** A user shall be able to apply and remove tags from a note they own.
- **FR-NOTE-14:** A user shall be able to pin and unpin a note.
- **FR-NOTE-24:** The editor shall support Ctrl+S, Cmd+S, or the platform-appropriate equivalent for deliberate Save.
- **FR-NOTE-25:** When technically possible, navigating away from dirty editor-owned content shall warn the user and offer an appropriate choice before abandoning unsaved work.
- **FR-NOTE-26:** Explicit commands such as pin, archive, restore, trash, tag changes, publish, update-public-copy, and unpublish may persist as part of the command and shall not require a separate editor Save unless a later approved UX decision justifies it.

#### 9.6 Note Lifecycle & Versioning

- **FR-NOTE-15:** A user shall be able to archive and later restore an owned note from the archive.
- **FR-NOTE-16:** A user shall be able to move an owned note to trash without immediately destroying recoverable content.
- **FR-NOTE-17:** A user shall be able to restore an owned trashed note while it remains recoverable.
- **FR-NOTE-18:** Permanent deletion shall require explicit confirmation that clearly distinguishes it from archive and trash.
- **FR-NOTE-19:** The product shall retain a bounded history of meaningful immutable note versions under a policy visible enough for users to understand its limits.
- **FR-NOTE-20:** A user shall be able to inspect an earlier retained version and restore it through a deliberate action.
- **FR-NOTE-21:** Restoring an earlier version shall not silently erase the ability to understand or recover the pre-restore current content while retention policy permits.
- **FR-NOTE-22:** Notes shall load efficiently in bounded pages or cursors, preserving stable navigation as the collection grows.
- **FR-NOTE-23:** Lifecycle actions affecting a published source note shall explain and enforce their public-copy consequences before completion.

#### 9.6A Attachments & Multimodal Capture

- **FR-ATTACH-01:** A user shall be able to attach zero or more supported images, audio or voice recordings, bounded video clips, and PDF files to an owned private note.
- **FR-ATTACH-02:** Supported attachment types, sizes, durations, page limits, per-note limits, and storage quotas shall be bounded and communicated; Target Flagship shall not promise unlimited media or arbitrary file storage.
- **FR-ATTACH-03:** Every private attachment shall inherit access control from its owning note and shall require authorization before viewing, playing, opening, or downloading.
- **FR-ATTACH-04:** A user shall be able to view, play, open, or download an authorized supported attachment in an appropriate safe experience.
- **FR-ATTACH-05:** Upload and AI-processing state shall be distinguishable so a successfully stored attachment is not represented as failed merely because later AI indexing fails.
- **FR-ATTACH-06:** A failed upload shall expose a clear retry or removal path without damaging the note.
- **FR-ATTACH-07:** Failure of semantic or multimodal processing shall not destroy or revoke access to a successfully stored attachment.
- **FR-ATTACH-08:** A user shall be able to remove an attachment from a note they own.
- **FR-ATTACH-09:** Attachment retention and cleanup shall follow the approved note trash, restore, permanent-deletion, and version/publication policies without orphaning private data indefinitely.
- **FR-ATTACH-10:** Attachment acceptance shall validate size, declared type, and actual content characteristics appropriate to the modality rather than trusting client metadata alone.
- **FR-ATTACH-11:** Stored attachment identity shall not expose an untrusted client filename as an authorization mechanism or make private media reachable through a guessable public location.
- **FR-ATTACH-12:** Display, playback, and document handling shall prevent an attachment from executing unsafe active content in the application origin.
- **FR-ATTACH-13:** Private attachment content and metadata shall not enter routine logs or public responses.
- **FR-ATTACH-14:** Uploading and storing an attachment shall not by itself authorize sending its content to an AI provider.
- **FR-ATTACH-15:** Private attachment existence shall never imply public exposure; publication requires an explicit decision about each supported attachment included in the public snapshot.

#### 9.7 Ordinary Search

- **FR-SEARCH-01:** An authenticated user shall be able to search the titles of notes they are authorized to access.
- **FR-SEARCH-02:** An authenticated user shall be able to search the bodies of notes they are authorized to access.
- **FR-SEARCH-03:** Ordinary search shall support filtering by relevant note state and tags.
- **FR-SEARCH-04:** Search results shall be ranked in a way that gives understandable priority to likely useful matches.
- **FR-SEARCH-05:** Ordinary search shall intentionally tolerate common spelling mistakes where similarity is sufficient, without guaranteeing correction of every typo.
- **FR-SEARCH-06:** Retrieval shall have a reasonable opportunity to connect common shorthand or abbreviations with fuller later wording where available signals support that association.
- **FR-SEARCH-07:** Ordinary lexical and fuzzy search shall remain available for AI-excluded notes.
- **FR-SEARCH-08:** Semantic signals may contribute only for notes currently eligible for AI-dependent retrieval.
- **FR-SEARCH-09:** Hybrid search shall combine complementary eligible signals without weakening authorization boundaries.
- **FR-SEARCH-10:** Search results shall support bounded paging/loading and navigation to the matching note and relevant location where practical.
- **FR-SEARCH-11:** Search shall not claim exhaustive coverage when serving a relevance-ranked query.
- **FR-SEARCH-12:** Every private search path shall constrain candidates to the authenticated user's authorized scope before ranking.

#### 9.8 AI Access Controls

- **FR-AI-01:** A newly created account shall set Default AI access for new notes to OFF, so no note content enters AI-dependent processing merely because the account exists.
- **FR-AI-02:** Each private note shall own a persistent, understandable AI ON or AI OFF state after creation, independent of later changes to the account default for new notes.
- **FR-AI-03:** AI exclusion shall be described as a processing-control choice, not as encryption or end-to-end encryption.
- **FR-AI-04:** An AI-excluded note shall remain usable for editing, explicit Save, ordinary lexical/fuzzy search, tags, filters, lifecycle actions, version history, authorized attachment access, and non-AI deterministic extraction.
- **FR-AI-05:** An AI-excluded note and its attachments shall contribute no content to embedding generation, vector/semantic retrieval, AI-dependent reranking or classification, semantic category extraction, generative model context, semantic related notes, AI tag/topic suggestions, media reasoning, or any other AI-dependent processing, regardless of which visible query surface initiated the operation.
- **FR-AI-06:** Explicitly excluding a note shall initiate removal of its existing AI-derived representations and expose a meaningful pending, completed, or failed state.
- **FR-AI-07:** Stale indexing or retrieval work shall not recreate or use representations after a note becomes ineligible.
- **FR-AI-08:** Changing an owned note from AI OFF to AI ON may initiate asynchronous indexing after the required AI/provider disclosure has been acknowledged, regardless of the current default for future notes, and shall not be described as decrypting the note.
- **FR-AI-09:** Users shall be able to understand the relevant configured processing mode or provider policy without being burdened by internal job details.
- **FR-AI-36:** Before any note or attachment is processed by AI for the first time, the product shall provide and require acknowledgement of a brief understandable AI/privacy introduction explaining the meaningful configured provider/processing policy, what AI ON permits, and what normal functionality AI OFF preserves; this information shall remain accessible later through AI & Privacy settings without being repeated on every login.
- **FR-AI-37:** Create Note shall expose an understandable control equivalent to Use this note with AI, initialize it automatically from Default AI access for new notes, allow the user to override that initial value for the note, and persist the resulting truthful note state.
- **FR-AI-38:** A user shall be able to change Default AI access for new notes between ON and OFF; the change shall affect only the initial AI-control value of future Create Note experiences and shall not modify any existing note.
- **FR-AI-39:** Attachment AI eligibility shall follow the parent note in Target Flagship; no separate per-attachment AI-permission system is required.
- **FR-AI-43:** AI & Privacy settings shall provide a deliberate confirmed bulk action that enables AI for applicable existing notes and their attachments, requires acknowledgement of the provider disclosure before first AI use, and queues required processing asynchronously without changing the default for future notes.
- **FR-AI-44:** AI & Privacy settings shall provide a deliberate confirmed bulk action that disables AI for applicable existing notes and their attachments, makes affected representations ineligible, and initiates deindex/removal without changing the default for future notes.
- **FR-AI-45:** Changing the account default shall never silently act as a bulk mutation; an individual note may remain or become AI ON while the future-note default is OFF, and may remain or become AI OFF while the default is ON.

#### 9.9 Ask My Knowledge

- **FR-AI-10:** Ask My Knowledge shall be presented as a dedicated capability for questions about the user's authorized notes, not as a general-purpose chatbot.
- **FR-AI-11:** Every Ask My Knowledge request shall require an authenticated user.
- **FR-AI-12:** The knowledge-query capability shall operate only on authorized content; AI-dependent stages may use only AI-enabled notes and their attachments, while a routed deterministic non-AI operation may return authorized data from AI-disabled notes without sending that content to an embedding or model call.
- **FR-AI-13:** Ask My Knowledge shall understand the requested outcome and internally select among ranked relevance, focused fact retrieval, corpus-wide semantic extraction/aggregation, exhaustive deterministic extraction, and modality-appropriate evidence handling without requiring the user to choose a technical search mode.
- **FR-AI-14:** When generation is appropriate, model context shall be bounded to selected authorized evidence.
- **FR-AI-15:** Answers shall be presented as grounded in the user's notes and shall not silently substitute unrelated general model knowledge.
- **FR-AI-16:** Material answer claims shall expose meaningful, navigable source provenance where a source exists.
- **FR-AI-17:** If available notes do not sufficiently support an answer, the product shall say so rather than fabricate confidence.
- **FR-AI-18:** An insufficient-evidence result may show closest authorized notes or invite query refinement, while clearly distinguishing them from an answer.
- **FR-AI-19:** The product shall not arbitrarily refuse to retrieve an authenticated owner's lawful AI-enabled note solely because its text appears sensitive, while still making clear that the application is not a password manager.
- **FR-AI-20:** Failure or unavailability of model generation shall produce a clear degraded state without disabling healthy non-AI note capabilities.

#### 9.10 Related Notes & AI Organization

- **FR-AI-21:** While viewing an AI-enabled note, a user may request or view semantically related notes from their authorized AI-enabled corpus.
- **FR-AI-22:** Related-note results shall never include another user's private content or the user's AI-excluded notes.
- **FR-AI-23:** Related-note discovery shall not require generative text when similarity retrieval alone satisfies the user need.
- **FR-AI-24:** AI may suggest tags, topics, or organizational metadata for an eligible note.
- **FR-AI-25:** AI suggestions shall require explicit user confirmation before changing stored metadata.
- **FR-AI-26:** AI shall not silently rewrite note content, move notes, change tags, or publish notes.

#### 9.10A Multimodal Rediscovery & Reasoning

- **FR-MM-01:** Target Flagship shall support semantic rediscovery across AI-enabled text, images, audio/voice recordings, bounded video clips, and PDFs. Downstream technical validation selects how each committed modality is fulfilled; removing a modality requires an explicit approved product-scope change.
- **FR-MM-02:** A textual query shall be able to return a semantically relevant authorized image, audio/voice recording, bounded video clip, or PDF through the unified knowledge workflow.
- **FR-MM-03:** Users shall not be required to choose separate technical Image Search, Audio Search, Video Search, Vector Search, or Full Corpus Scan modes for the core knowledge workflow.
- **FR-MM-04:** A query that explicitly names a modality may use that intent to constrain or prioritize results, while a modality-neutral query may search eligible modalities together.
- **FR-MM-05:** The product shall distinguish locating a relevant attachment from answering a detailed question about the attachment's contents.
- **FR-MM-06:** When the user's requested outcome is satisfied by locating a source, the product shall not require generative multimodal reasoning solely to return that source.
- **FR-MM-07:** When an answer requires understanding retrieved media, the product shall use an eligible modality-capable processing path or suitable derived content and shall ground the answer in the retrieved source.
- **FR-MM-08:** An embedding or similarity result shall be treated as candidate evidence rather than a guarantee of factual or semantic classification correctness.
- **FR-MM-09:** Difficult semantic classification may return an understandable uncertain result instead of forcing a confident match or rejection.
- **FR-MM-10:** Multimodal provenance shall identify the source note, attachment, and relevant segment or location where practical without exposing unauthorized media.

#### 9.11 Publication

- **FR-PUB-01:** A user shall be able to publish an owned private note only through an explicit action.
- **FR-PUB-02:** Before publishing or updating public content, the user shall be able to preview the exact public-facing content and metadata.
- **FR-PUB-03:** Publication shall create one current public snapshot with stable public identity and provenance to an immutable source checkpoint where required.
- **FR-PUB-04:** Ordinary private editing or saving shall not silently change an existing public snapshot.
- **FR-PUB-05:** When private content differs from its public snapshot, the product shall make that drift understandable to the owner.
- **FR-PUB-06:** Updating the public snapshot shall require a deliberate owner action distinct from ordinary private editing.
- **FR-PUB-07:** A user shall be able to unpublish without deleting the private source note.
- **FR-PUB-08:** Unpublishing shall remove the snapshot from public reads, discovery, and public retrieval eligibility.
- **FR-PUB-09:** Public output shall include only explicitly approved content and public author/profile fields.
- **FR-PUB-10:** A published source entering trash or permanent deletion shall require a clear decision about unpublishing and shall not leave unintended public content.
- **FR-PUB-11:** Account deletion shall remove active public availability before slower private-data cleanup completes.
- **FR-PUB-12:** The Target Flagship shall maintain one current public snapshot and shall not promise a separate public revision-history product.
- **FR-PUB-19:** Publishing or updating a public snapshot shall require an explicit choice about which supported attachments, if any, are included.
- **FR-PUB-20:** Attachments added, replaced, removed, or edited privately after publication shall not silently alter public media.

#### 9.12 Explore / Public Discovery

- **FR-EXPLORE-01:** Visitors shall be able to browse active public note snapshots through an Explore surface.
- **FR-EXPLORE-02:** Explore shall provide a Latest view ordered by understandable publication recency.
- **FR-EXPLORE-03:** Explore shall provide a Trending view based on simple, transparent, testable engagement and recency signals.
- **FR-EXPLORE-04:** Trending shall not personalize ranking into a recommendation feed.
- **FR-EXPLORE-05:** Public search shall search only active public snapshot content and approved public metadata.
- **FR-EXPLORE-06:** Public discovery shall support useful navigation by public author and selected public tags where available.
- **FR-EXPLORE-07:** Public results shall never expose private note text, private indexes, private embeddings, private identifiers, or unpublished drift.
- **FR-EXPLORE-08:** Removing or hiding a publication shall make it ineligible for public browsing, search, and Trending.

#### 9.13 Likes & Views

- **FR-ENGAGE-01:** An authenticated user shall be able to like and unlike an active public note.
- **FR-ENGAGE-02:** A user shall have at most one active like on a publication.
- **FR-ENGAGE-03:** Repeating an already satisfied like or unlike intent shall not create duplicate engagement.
- **FR-ENGAGE-04:** Public notes may display an approximate view count.
- **FR-ENGAGE-05:** View counting shall avoid treating every rapid refresh by the same apparent viewer as an independent meaningful view.
- **FR-ENGAGE-06:** View deduplication shall be privacy-conscious and shall not require permanent raw IP-address history solely for counting views.
- **FR-ENGAGE-07:** Failure of view counting shall not block access to an otherwise available public note.

#### 9.14 Moderation

- **FR-MOD-01:** A publication owner shall be able to unpublish their own content.
- **FR-MOD-02:** A user shall be able to report active public content using a bounded reason category and relevant context.
- **FR-MOD-03:** An authorized moderator shall be able to review open reports.
- **FR-MOD-04:** An authorized moderator shall be able to hide or remove a public publication when justified.
- **FR-MOD-05:** A narrowly authorized administrator or moderator shall be able to suspend an abusive account when justified by policy.
- **FR-MOD-06:** Material moderation actions shall be attributable and auditable.
- **FR-MOD-07:** Moderation shall remain a narrow safety capability and shall not require editorial approval, automated content scoring, or a large administration product.

### 10. Retrieval Behavior Requirements

#### 10.1 Focused Fact Lookup

- **FR-RETR-01:** A focused fact request shall seek a small, high-quality authorized evidence set using complementary eligible retrieval signals and stronger inspection or verification where the requested outcome requires it.
- **FR-RETR-02:** Focused lookup shall have a reasonable opportunity to associate common shorthand, spelling variation, or partial wording with a fuller query when evidence supports it.
- **FR-RETR-03:** A focused answer shall identify its source note and relevant location where practical.
- **FR-RETR-04:** Sensitive-looking lawful content shall remain subject to the same owner authorization and AI-eligibility rules as other note content, not a separate cross-user or content-category bypass.

#### 10.2 Relevance Retrieval

- **FR-RETR-05:** A relevance query shall return the best-ranked authorized results rather than claiming to inspect or return every possible result.
- **FR-RETR-06:** Relevance ranking shall be able to use lexical, fuzzy, semantic, and hybrid signals according to current eligibility.
- **FR-RETR-07:** The user experience shall distinguish relevance-ranked results from exhaustive results when the distinction affects interpretation.

#### 10.3 Multi-Note Aggregation

- **FR-RETR-08:** A corpus-wide semantic extraction or aggregation request shall use a high-recall strategy across the authorized AI-enabled corpus rather than relying on a small fixed top-ranked set or a hard-coded category feature.
- **FR-RETR-09:** Corpus-wide semantic extraction shall support arbitrary semantic categories expressed by the user, extract and verify candidate items where appropriate, normalize and deduplicate results, and retain source provenance.
- **FR-RETR-10:** Semantic aggregation shall distinguish corpus coverage from semantic classification correctness, report the evidence scope inspected, and avoid claiming that every item was classified perfectly.
- **FR-RETR-11:** Items gathered from multiple notes shall remain traceable to their contributing authorized sources.

#### 10.4 Exhaustive Structured Extraction

- **FR-RETR-12:** When a user asks for every occurrence of a supported deterministically detectable pattern, the product shall inspect the complete authorized scope or a complete derived index for that scope, including AI-excluded notes when the operation does not use AI.
- **FR-RETR-13:** Exhaustive deterministic extraction shall collect every detected authorized occurrence and preserve source-note provenance; URLs are a canonical example rather than a separate product subsystem.
- **FR-RETR-14:** Exhaustive results may deduplicate values for presentation only if the user can still account for all occurrences and sources.
- **FR-RETR-15:** Generative AI may interpret, group, or format an exhaustive result only for content currently eligible for AI processing, but shall not determine completeness from top-ranked chunks; excluded content shall remain in non-AI presentation or be omitted from AI post-processing without being omitted from the authorized deterministic result.
- **FR-RETR-16:** Discovering a saved URL shall not automatically fetch, crawl, open, preview, download, or inspect the remote resource.
- **FR-RETR-26:** Query handling shall conceptually follow requested-outcome understanding, authorized strategy selection, evidence gathering, verification or extraction where required, deduplication, provenance, and answer/result presentation.
- **FR-RETR-27:** Semantic or hybrid candidate retrieval shall not by itself be treated as final factual or category truth.
- **FR-RETR-28:** Where meaning-based classification is required, the product may distinguish match, non-match, and uncertainty rather than forcing unsupported binary certainty.
- **FR-RETR-29:** AI-excluded content shall not participate in semantic category extraction or other AI-dependent verification, even when it remains eligible for ordinary and deterministic non-AI retrieval.

#### 10.5 Completeness Semantics

- **FR-RETR-17:** Relevance queries shall optimize useful ranking and shall not promise complete corpus coverage.
- **FR-RETR-18:** Semantic aggregation shall aim for high recall and communicate uncertainty when absolute completeness is not provable.
- **FR-RETR-19:** Exhaustive deterministic extraction shall state complete coverage only when the complete authorized corpus or a complete derived index was actually evaluated.
- **FR-RETR-20:** Result language shall make the applicable completeness class understandable to the user where misunderstanding would be material.

#### 10.6 Provenance

- **FR-RETR-21:** Ask My Knowledge and multi-note synthesis shall associate material claims or items with authorized source-note identity.
- **FR-RETR-22:** Provenance shall identify the relevant section or excerpt location where practical.
- **FR-RETR-23:** A user shall be able to navigate from provenance to the source they are authorized to open.
- **FR-RETR-24:** The system shall not invent citations or present an unverified source association as established.
- **FR-RETR-25:** Citations and provenance shall not leak another user's note title, URL, snippet, identifier, or existence.

### 11. Security & Privacy Product Requirements

- **FR-SEC-01:** Every private note and derivative shall remain bound to immutable internal ownership identity.
- **FR-SEC-02:** A user shall be denied direct access to a private resource they do not own or otherwise have explicit authorization to access.
- **FR-SEC-03:** Authorization shall constrain candidates before private lexical, fuzzy, vector, hybrid, related-note, aggregation, exhaustive, citation, cache, and background-result retrieval.
- **FR-SEC-04:** Public visibility shall arise only from an active, explicit publication snapshot and never from the existence of a private note or private derived representation.
- **FR-SEC-05:** AI-access state shall be enforced both when derived data is created and when it is retrieved.
- **FR-SEC-06:** Public and private output shall be sanitized so stored Markdown cannot execute unsafe active content.
- **FR-SEC-07:** Secrets, recovery codes, TOTP secrets, session identifiers, private note bodies, and model context shall not appear in user-visible diagnostics or routine operational logs.
- **FR-SEC-08:** Application secrets and real credentials shall not be stored in source control.
- **FR-SEC-09:** Sensitive account changes shall produce appropriate session revocation or rotation behavior.
- **FR-SEC-10:** Public profile and publication endpoints shall expose only intentionally public fields.
- **FR-SEC-11:** Security controls shall provide safe, understandable errors without revealing another user's resource existence.
- **FR-SEC-12:** AI Excluded shall never be represented to users as E2EE, zero knowledge, or a server-unreadable storage mode.
- **FR-SEC-13:** Private attachment access and every derived media representation shall follow the same ownership, AI-eligibility, and public/private boundaries as the parent note.
- **FR-SEC-14:** User B shall not discover, retrieve, infer, or access User A's private attachment, metadata, transcript, embedding, extracted content, thumbnail, citation, or processing result without authorization.

Cross-user isolation is a non-negotiable product guarantee: if User A owns a private note and User B lacks authorization, User B must not retrieve any part or derivative through direct access, lists, filters, fuzzy or semantic search, hybrid ranking, related notes, Ask My Knowledge, aggregation, exhaustive extraction, citations, caches, indexes, public search, or background-job output.

### 12. AI Product Requirements

- **FR-AI-27:** AI-dependent features shall disclose their dependency state as available, pending, degraded, excluded, or failed where that state affects the user.
- **FR-AI-28:** Users shall be able to understand the Default AI access for new notes preference and the independent AI ON/OFF state of an individual note without mistaking the account preference for a master processing switch.
- **FR-AI-29:** Users shall be able to understand whether an AI-enabled note is indexed, awaiting processing, being removed, or failed without exposing unnecessary internal mechanics.
- **FR-AI-30:** The product shall communicate the configured processing mode or provider policy sufficiently for informed use.
- **FR-AI-31:** The product shall not send any note or attachment to a local or external AI provider before the required disclosure is acknowledged, and shall never send AI-disabled content to AI-dependent processing.
- **FR-AI-32:** Ask My Knowledge shall prioritize supported answers from user-owned evidence and shall not present generic model knowledge as note-derived fact.
- **FR-AI-33:** AI-generated organizational suggestions shall remain proposals until the user confirms them.
- **FR-AI-34:** Provider failure, quota exhaustion, or indexing delay shall not falsely appear as an empty or complete knowledge result.
- **FR-AI-35:** A true client-side/E2EE Vault shall not be promised as part of Target Flagship behavior.
- **FR-AI-40:** The product shall remain provider-agnostic and shall allow downstream provider selection by capability, modality, privacy, availability, measured quality, and cost rather than presenting any model vendor as the product identity.
- **FR-AI-41:** Retrieval and reasoning routing may consider the evidence actually retrieved, so locating media need not trigger a multimodal reasoning call unless the requested outcome requires content understanding.
- **FR-AI-42:** Provider quota exhaustion or rate limiting shall produce truthful pending, retryable, or degraded behavior and shall not be presented as a valid empty or complete result.

### 13. Public Notes Product Requirements

The public surface exists to share deliberately selected knowledge, not to expose private-note state or create a social network.

- **FR-PUB-13:** A public note page shall show its active snapshot content, approved public author identity, selected public tags, and relevant publication timestamps.
- **FR-PUB-14:** Public pages may show authenticated-like and approximate-view aggregates without exposing viewer identities.
- **FR-PUB-15:** Public search, Latest, Trending, profiles, likes, views, and moderation shall operate only on active public publications.
- **FR-PUB-16:** Private edits, drafts, versions, AI settings, and unpublished metadata shall not be inferable through public output.
- **FR-PUB-17:** Public-note removal shall take precedence over engagement-count availability or discovery freshness.
- **FR-PUB-18:** Public features shall exclude comments, follows, direct messages, reposts, social graphs, and personalized recommendation feeds.
- **FR-PUB-21:** Object storage containing a private attachment shall not make that attachment public; only an explicitly approved publication snapshot and its supported public media may be publicly accessible.

### 14. Non-Functional Requirements

Exact performance baselines and numerical thresholds are **TO BE ESTABLISHED** in downstream design and testing work using realistic datasets and deployment constraints.

#### Security

- **NFR-SEC-01:** Security-critical behavior shall default to denial when ownership, session, publication, or AI-eligibility state cannot be established.
- **NFR-SEC-02:** Authentication, authorization, session, recovery, MFA, upload, rendering, and retrieval controls shall be covered by repeatable security tests.
- **NFR-SEC-03:** Sensitive values shall be redacted or excluded from logs, telemetry, errors, and public artifacts.
- **NFR-SEC-04:** Supported isolation tests shall tolerate zero cross-user content leaks, zero AI-excluded-content violations on AI paths, and zero unauthorized citation/provenance leaks; a single observed violation is a release-blocking failure.

#### Privacy

- **NFR-PRIV-01:** Data processing shall be limited to the scope necessary for the user-visible feature being performed.
- **NFR-PRIV-02:** AI-provider and processing-mode disclosures shall be understandable and consistent with actual behavior.
- **NFR-PRIV-03:** View measurement shall minimize identifying data and shall not require permanent raw IP-address retention solely for analytics.
- **NFR-PRIV-04:** Removing AI eligibility or public visibility shall propagate to derived retrieval surfaces within a measurable window to be established downstream.

#### Reliability and Data Integrity

- **NFR-REL-01:** Acknowledged note saves shall survive ordinary application restart and dependency recovery scenarios defined downstream.
- **NFR-REL-02:** Transient failures shall expose retry or recovery behavior without silently losing acknowledged or still-present local edits.
- **NFR-REL-03:** Conflicting writes shall not silently overwrite newer content.
- **NFR-REL-04:** Background indexing, deindexing, email, avatar-cleanup, and public-projection work shall be recoverable, observable, and safe to retry.
- **NFR-REL-05:** Publication and unpublication behavior shall preserve private/public integrity even when dependent background processing is delayed.
- **NFR-REL-06:** A successfully stored attachment shall remain available to its authorized owner when embedding, transcription, extraction, or multimodal reasoning is pending or fails.

#### Search Usability and AI Grounding

- **NFR-SEARCH-01:** Search quality shall be evaluated on representative exact, typo, shorthand, semantic, authorization, aggregation, and exhaustive datasets.
- **NFR-SEARCH-02:** Search latency and result-loading targets shall be measured and baselined downstream using realistic corpus sizes rather than invented enterprise claims.
- **NFR-AI-01:** Grounded-answer quality shall be evaluated for evidence support, citation correctness, insufficient-evidence behavior, and cross-user isolation.
- **NFR-AI-02:** High-recall aggregation and exhaustive deterministic extraction shall have separate evaluation criteria.
- **NFR-AI-03:** The retrieval and AI subsystem shall have a reproducible offline evaluation suite using representative synthetic multi-user corpora with distractors, spelling variation, shorthand, duplicates, scattered semantic categories, AI-excluded content, public/private states, and supported media.
- **NFR-AI-04:** Evaluation shall report task-appropriate metrics separately for ranked retrieval, focused answers, semantic extraction, aggregation, citations, insufficient-evidence behavior, and each supported cross-modal retrieval path.
- **NFR-AI-05:** Before a non-security retrieval or semantic capability is described as mature, the engineering target is at least 90% on the approved task-appropriate quality metric where that target is realistically meaningful; exact metrics, datasets, and thresholds shall be defined downstream and results shall be reported rather than assumed.
- **NFR-AI-06:** No blanket claim that AI is more than 90% accurate shall be made from the task-specific engineering ambition.
- **NFR-AI-07:** Evaluation shall permit controlled comparison of pipeline stages such as lexical/fuzzy/vector candidate retrieval, query expansion, reranking, verification, and synthesis so quality improvements can be attributed to evidence.
- **NFR-AI-08:** Provider/model comparisons shall use the same appropriate evaluation corpus and report measured quality, cost, latency, and failure behavior without assuming that a newer or larger model is superior.
- **NFR-AI-09:** Multimodal evaluation shall separately measure text-to-text, text-to-image, text-to-audio, text-to-video, and text-to-PDF retrieval, plus media-grounded answer and provenance correctness for every committed modality.

#### Performance and Cost

- **NFR-PERF-01:** Core note read, write, list, and ordinary search interactions shall remain responsive under an expected personal-workspace workload; numerical budgets are TO BE ESTABLISHED.
- **NFR-PERF-02:** Large collections shall use bounded transfer and rendering so growth does not require loading the complete note corpus into the interface.
- **NFR-COST-01:** AI, storage, email, search, and public-engagement features shall expose enough usage information for cost controls and quotas to be designed and validated.
- **NFR-COST-02:** Expensive AI work shall fail or queue gracefully at approved limits rather than compromising core note availability.

#### Accessibility and Responsive UX

- **NFR-ACC-01:** Core workflows shall be operable by keyboard and expose meaningful focus, labels, validation, and status announcements.
- **NFR-ACC-02:** Content and controls shall remain understandable with common assistive technologies; conformance targets are TO BE ESTABLISHED in frontend design.
- **NFR-ACC-03:** Supported responsive layouts shall preserve essential reading, editing, save-state, search, security, and publication controls.

#### Maintainability and Testability

- **NFR-MAINT-01:** Product behavior shall be traceable through stable requirement identifiers into architecture decisions and tests.
- **NFR-MAINT-02:** The system shall preserve approved modular boundaries so one feature area cannot bypass another area's ownership rules.
- **NFR-TEST-01:** Critical workflows shall have deterministic automated tests that do not depend on nondeterministic live-model output.
- **NFR-TEST-02:** Integration tests shall exercise the real relational and vector capabilities selected downstream.
- **NFR-TEST-03:** Test fixtures shall use synthetic public-safe identities, notes, credentials, and URLs.

#### Observability and Auditability

- **NFR-OBS-01:** Health, failure, retry, latency, and queue/index-state signals shall be observable without recording private note content or secrets.
- **NFR-OBS-02:** Requests and background work shall be correlatable for diagnosis without exposing reusable authentication material.
- **NFR-AUDIT-01:** Security-sensitive account changes, session revocations, publication state changes, and moderation actions shall have appropriately protected audit evidence.

#### Reproducibility and Repository Hygiene

- **NFR-REPO-01:** A new contributor shall be able to reproduce the supported local environment from public-safe documented steps.
- **NFR-REPO-02:** Builds, tests, migrations, and quality checks shall be automatable in continuous integration.
- **NFR-REPO-03:** The public repository shall contain no real credentials, private user content, confidential project information, or environment-specific secrets.
- **NFR-REPO-04:** Dependencies and generated artifacts shall be managed so a clean checkout does not rely on undocumented local state.

#### Degraded-Mode Behavior

- **NFR-DEGRADE-01:** Loss of chat, embedding, transcription, or multimodal processing capability shall not make healthy note creation, explicit saving, reading, attachment access, lifecycle, lexical/fuzzy search, profile, or publication functionality unavailable.
- **NFR-DEGRADE-02:** Loss of nonessential engagement counting shall not block public-note reads.
- **NFR-DEGRADE-03:** Degraded states shall be distinguishable from valid empty, complete, saved, or unpublished states.

### 15. Canonical Acceptance Scenarios

#### AS-01 — Create and explicitly save a note

Given an authenticated user creates or opens a Markdown note, when they edit title or body, then the interface shows Unsaved Changes; when they invoke Save or the supported keyboard shortcut, it shows Saving and then Saved only after persistence succeeds, and reopening the note shows the saved content. Traces to FR-NOTE-01 through FR-NOTE-05 and FR-NOTE-24.

#### AS-02 — Recover from a transient save failure

Given a Save cannot be persisted temporarily, when the failure occurs, then the interface shows Save Failed, retains the user's current editor state, offers Retry Save, and never labels the failed change Saved. Traces to FR-NOTE-05 through FR-NOTE-07 and NFR-REL-02.

#### AS-03 — Detect concurrent edits

Given the same note is edited from two sessions, when the older session attempts to save over a newer revision, then the product detects the conflict, preserves both relevant states, and requires deliberate resolution rather than silently overwriting. Traces to FR-NOTE-08 and FR-NOTE-09.

#### AS-04 — Inspect and restore a version

Given a note has retained history, when its owner restores an earlier version, then that content becomes current through an explicit action and the immediately pre-restore state remains understandable or recoverable while policy permits. Traces to FR-NOTE-19 through FR-NOTE-21.

#### AS-05 — Retrieve a corrected spelling

Given an owned note contains the synthetic misspelling `goohle`, when the owner searches for `Google`, then typo-tolerant retrieval gives the note a reasonable opportunity to rank without promising that every typo always resolves. Traces to FR-SEARCH-05.

#### AS-06 — Retrieve fuller wording from shorthand

Given an authorized eligible note contains `ps login`, when the owner searches or asks using `PlayStation`, then complementary retrieval signals can associate the concepts when supported and return the source without weakening authorization. Traces to FR-SEARCH-06 and FR-RETR-02.

#### AS-07 — Answer a focused knowledge question

Given one authorized AI-enabled note supports a requested fact, when the owner asks for that fact, then Ask My Knowledge returns an evidence-grounded answer with navigable provenance. Any credential-like value in fixtures is a fictional placeholder. Traces to FR-AI-10 through FR-AI-16 and FR-RETR-01 through FR-RETR-04.

#### AS-08 — Report insufficient evidence

Given the user's AI-enabled notes do not sufficiently support an AI-dependent answer, when the user asks Ask My Knowledge, then the product reports insufficient evidence, may show closest authorized sources, and does not present generic model knowledge as note-derived. Traces to FR-AI-15 through FR-AI-18.

#### AS-09 — Extract an arbitrary semantic category across many notes

Given synthetic items from several unrelated categories are scattered across many authorized AI-enabled notes, when the owner asks for items belonging to one user-defined semantic category, then the product uses high-recall corpus-wide retrieval, filters obvious distractors, extracts and deduplicates likely matches, preserves sources, distinguishes corpus coverage from classification correctness, and communicates uncertainty honestly without relying on a hard-coded category feature. Traces to FR-RETR-08 through FR-RETR-11 and FR-RETR-27 through FR-RETR-29.

#### AS-10 — Extract every saved URL

Given the complete authorized scope contains synthetic URLs such as `https://example.com/...` in old and semantically unrelated notes, when the owner asks for every saved URL, then the product evaluates the complete authorized corpus or complete derived index, reports every occurrence with provenance, and may state complete coverage only when that evaluation succeeds. Traces to FR-RETR-12 through FR-RETR-15 and FR-RETR-19.

#### AS-11 — Do not visit extracted URLs

Given exhaustive URL extraction finds saved links, when results are produced, then the system does not automatically fetch, crawl, open, preview, download, or inspect any remote destination. Traces to FR-RETR-16.

#### AS-12 — Exclude a note from AI

Given an AI-enabled note has derived representations, when its owner changes it to AI Excluded, then ordinary editing and lexical/fuzzy search continue, deindexing state becomes visible, AI-dependent features cease using it, and stale work cannot recreate eligibility. Traces to FR-AI-02 through FR-AI-08.

#### AS-13 — Prevent cross-user retrieval

Given User A owns a private note and User B has no authorization, when User B attempts direct access or any list, search, vector, hybrid, related-note, Ask My Knowledge, aggregation, extraction, citation, cache, index, public-search, or background-result path, then no part, derivative, metadata, or existence of the note is disclosed. Traces to FR-SEARCH-12, FR-SEC-01 through FR-SEC-05, and FR-RETR-25.

#### AS-14 — Preserve a publication snapshot

Given an owner publishes a previewed note snapshot, when the private source is later edited and explicitly saved, then the public copy remains unchanged and the owner sees that private and public content have drifted; only Update Public Note can replace the active snapshot. Traces to FR-PUB-01 through FR-PUB-06.

#### AS-15 — Unpublish without deleting the note

Given an active public note, when its owner unpublishes it, then public reads and discovery stop while the private source remains available to the owner. Traces to FR-PUB-07 and FR-PUB-08.

#### AS-16 — Complete MFA enrollment and challenge

Given a recently authenticated user begins MFA enrollment, when they scan or enter the authenticator secret and verify a valid TOTP, then MFA becomes active, recovery codes are shown once, and a later login requires a successful second factor before full authorization. Traces to FR-MFA-01 through FR-MFA-06.

#### AS-17 — Revoke another session

Given a user recognizes an unwanted active session, when they revoke it, then subsequent requests from that session cannot access private resources while the current authorized session follows the selected revoke policy. Traces to FR-SESSION-02 through FR-SESSION-06.

#### AS-18 — Continue during AI failure

Given chat or embedding processing is unavailable, when the user works with notes, then creation, editing, reading, lifecycle operations, version history, lexical/fuzzy search, profiles, and publication continue where their own dependencies are healthy, while AI-dependent interfaces show a truthful degraded state. Traces to FR-AI-20, FR-AI-27 through FR-AI-34, and NFR-DEGRADE-01.

#### AS-19 — Limit public engagement behavior

Given an active public note, when users like, unlike, refresh, search, or browse it, then likes remain one per user, rapid refreshes are not necessarily separate meaningful views, view-count failure does not block reading, and the surface provides no comments, follows, or personalized feed. Traces to FR-ENGAGE-01 through FR-ENGAGE-07 and FR-PUB-18.

#### AS-20 — Moderate public content

Given a reason-coded report exists for an active publication, when an authorized moderator determines removal is justified, then the publication becomes unavailable to public reads and discovery and the action has protected audit evidence. Traces to FR-MOD-02 through FR-MOD-06.

#### AS-21 — Warn before abandoning unsaved editor content

Given a note has dirty editor-owned title or body changes, when the user attempts to navigate away in a context where interception is technically possible, then the product warns that work is unsaved and offers an appropriate choice before abandonment. Traces to FR-NOTE-25.

#### AS-22 — Persist an explicit command without editor Save

Given an owned note has no unrelated dirty editor content, when the user invokes an explicit command such as Pin, Archive, Restore, Trash, or a tag change, then that command may persist directly and does not require a second editor Save. Traces to FR-NOTE-26.

#### AS-23 — Include AI-excluded notes in routed deterministic extraction

Given AI-enabled and AI-excluded owned notes both contain supported deterministic patterns, when the owner requests exhaustive extraction through the unified knowledge-query surface, then the router selects a deterministic non-AI operation, matches from both authorized sets are returned, and no excluded content is sent to embeddings, AI reranking/classification, or a reasoning model. Traces to FR-AI-04, FR-AI-05, FR-AI-12, FR-RETR-12, and FR-RETR-29.

#### AS-24 — Exclude an AI-excluded note from semantic extraction

Given an AI-excluded note contains a possible member of a requested semantic category, when classification requires embeddings, AI reranking, or model reasoning, then that note is not processed through the semantic path, even though direct lexical search and routed deterministic non-AI extraction remain available. Traces to FR-AI-04, FR-AI-05, FR-AI-12, and FR-RETR-29.

#### AS-25 — Retrieve an image from a text query

Given an AI-enabled note contains a semantically relevant image, when the owner issues a text query, then Target Flagship can return the image with note and attachment provenance. Traces to FR-MM-01 through FR-MM-04 and FR-MM-10.

#### AS-26 — Retrieve audio from a text query

Given an AI-enabled note contains a relevant voice or audio attachment, when the owner issues a text query, then Target Flagship can return the audio with note and attachment provenance without requiring a separate Audio Search product. Traces to FR-MM-01 through FR-MM-04 and FR-MM-10.

#### AS-27 — Answer from retrieved media

Given authorized media from an AI-enabled note has been retrieved, when the user asks a question that requires understanding its contents rather than merely locating it, then a modality-capable path processes the necessary evidence and returns a grounded answer with provenance or honest uncertainty. Traces to FR-MM-05 through FR-MM-10 and FR-AI-41.

#### AS-28 — Keep AI-excluded media accessible but unprocessed

Given an owner attaches an image, audio/voice recording, bounded video clip, or PDF to an AI-disabled note, when the owner opens the note, then every attachment remains viewable, playable, openable, or downloadable as authorized, while none creates embeddings or enters any AI-dependent or multimodal reasoning context. Traces to FR-ATTACH-03 through FR-ATTACH-05, FR-AI-05, and FR-AI-39.

#### AS-29 — Prevent cross-user media access

Given User A owns a private attachment and User B lacks authorization, when User B attempts direct access, semantic discovery, derived-representation access, citation navigation, or metadata inference, then no attachment content, derivative, metadata, or existence is disclosed. Traces to FR-ATTACH-03, FR-SEC-13, and FR-SEC-14.

#### AS-30 — Preserve attachment access after AI-processing failure

Given an attachment upload succeeded but semantic or multimodal processing fails, when its owner opens the note, then the attachment remains available, processing state truthfully shows failure, and Retry or later reprocessing can be offered without damaging the note or attachment. Traces to FR-ATTACH-05 through FR-ATTACH-07 and NFR-REL-06.

#### AS-31 — Publish attachments deliberately

Given a private note contains attachments, when its owner publishes or updates its public snapshot, then only explicitly selected supported attachments become public; later private attachment changes do not alter the active public media automatically. Traces to FR-ATTACH-15, FR-PUB-19 through FR-PUB-21.

#### AS-32 — Start a new account with a conservative default

Given a newly created account, when its settings and first Create Note experience are opened, then Default AI access for new notes is OFF and the note's AI control is initially unchecked unless the user deliberately changes it. Traces to FR-AI-01 and FR-AI-37.

#### AS-33 — Create a note when the default is OFF

Given Default AI access for new notes is OFF, when Create Note opens, then Use this note with AI is initially unchecked; the user may check it for that note, and if informed AI disclosure has not yet been acknowledged it is required before any AI processing. Traces to FR-AI-36 and FR-AI-37.

#### AS-34 — Retrieve committed video and PDF modalities

Given AI-enabled notes contain a relevant bounded video clip and PDF, when the owner issues appropriate text queries, then Target Flagship can return each authorized source with attachment provenance; failure to find a technically acceptable implementation would require an explicit product-scope decision rather than silently removing either modality. Traces to FR-MM-01, FR-MM-02, and FR-MM-10.

#### AS-35 — Create a note when the default is ON

Given Default AI access for new notes is ON, when Create Note opens, then Use this note with AI is initially checked and the user may uncheck it so that specific note starts AI OFF. Traces to FR-AI-37.

#### AS-36 — Change the default without changing existing notes

Given existing notes have mixed AI ON/OFF states, when the user changes Default AI access for new notes, then every existing note retains its state and only future Create Note controls use the new initial value. Traces to FR-AI-02, FR-AI-38, and FR-AI-45.

#### AS-37 — Enable one note while the default is OFF

Given Default AI access for new notes is OFF and an owned note is AI OFF, when the owner explicitly enables AI for that note after acknowledging any required first-use disclosure, then the note and its supported attachments become eligible for queued AI processing while the future-note default remains OFF. Traces to FR-AI-08, FR-AI-36, FR-AI-39, and FR-AI-45.

#### AS-38 — Disable one note while the default is ON

Given Default AI access for new notes is ON and an owned note is AI ON, when the owner disables AI for that note, then it remains AI OFF despite the account default, its derived representations become ineligible and enter removal, and normal non-AI functionality continues. Traces to FR-AI-04 through FR-AI-07 and FR-AI-45.

#### AS-39 — Bulk-enable existing notes deliberately

Given many existing notes are AI OFF, when the user chooses Enable AI for existing notes, reviews the provider disclosure if required, and confirms, then applicable notes and their attachments become AI ON and indexing is queued without changing Default AI access for new notes. Traces to FR-AI-36, FR-AI-39, FR-AI-43, and FR-AI-45.

#### AS-40 — Bulk-disable existing notes deliberately

Given many existing notes are AI ON, when the user chooses Disable AI for existing notes and confirms, then affected notes and attachments become AI OFF, their derived representations become ineligible and enter removal, and Default AI access for new notes is unchanged. Traces to FR-AI-06, FR-AI-07, FR-AI-39, FR-AI-44, and FR-AI-45.

### 16. Target Flagship Scope Summary

Target Flagship commits to:

- High-quality private Markdown notes with deliberate explicit Save, dirty-state protection, conflict handling, lifecycle controls, bounded version history, responsive behavior, and accessible workflows.
- Bounded images, audio/voice recordings, video clips, and PDF attachments with authorized access, safe handling, lifecycle behavior, and explicit publication choices.
- Independent ordinary search with title/body retrieval, filters, typo tolerance, shorthand support, ranking, and bounded result navigation.
- A conservative future-note AI default, independent per-note AI controls, deliberate bulk actions for existing notes, and visible indexing/deindexing state.
- Ask My Knowledge with grounded answers, insufficient-evidence behavior, provenance, internal query routing, generalized arbitrary semantic extraction, deterministic extraction, and modality-aware retrieval.
- Related notes and confirm-before-apply AI organization suggestions.
- Application-managed login, verified email, Google OIDC, revocable server-side sessions, TOTP MFA, recovery codes, and deliberate password recovery.
- Public/private profile separation and sanitized avatars.
- Explicit snapshot publication, public Latest/Trending/Search, authenticated likes, approximate views, and narrow moderation.
- Cross-user isolation across text and media direct, derived, search, AI, cache, job, and public paths.
- Graceful non-AI operation during model or embedding failure.

Approved architectural constraints referenced by, but not redesigned in, this document are:

- Exactly one initial Spring Boot backend deployable; its artifact/deployable name is **TO BE DECIDED** downstream.
- A modular monolith, with Spring Modulith recommended.
- PostgreSQL as durable source of truth, pgvector for semantic retrieval, and PostgreSQL-backed durable jobs.
- Redis restricted to transient responsibilities; RabbitMQ is not needed in the approved topology.
- React and TypeScript, with TanStack Query for server-state strategy.
- Revocable server-side browser sessions and Google OIDC.
- Object storage for sanitized avatars, private note attachments, and explicitly approved public attachment derivatives.
- Spring AI abstractions and practical local Ollama support.
- Docker, Testcontainers, and CI/CD.

Exact framework versions, schema, aggregates, routes, DTOs, packages, cache keys, TTLs, job tables, ranking/fusion algorithms, vector indexes, chunk sizes, models, providers, vendors, cost ceilings, Trending formula, view-deduplication window, and backend artifact name remain downstream decisions.

### 17. Optional / Future

The following are not promised Target Flagship capabilities:

- GitHub OAuth.
- Import and export.
- Unlisted share links.
- AI-generated cheat-sheet transformations.
- Interview-review transformations.
- Weekly digest.
- User notifications.
- Flashcards and spaced repetition.
- Personalized recommendations.
- Multiple user-selectable AI providers.
- A separately deployed worker.
- A dedicated AI service.
- A true client-side/E2EE Vault.
- Local-only search or AI over client-decrypted Vault content.
- Automatic URL previews, fetching, or remote-content inspection.
- Deterministic sensitive-content warnings.
- Unlimited/general file storage, arbitrary file types, rich media editing, video streaming, and an elaborate transcription archive.
- Office documents, ebooks, and other non-PDF document formats unless explicitly promoted through a later approved scope change.

### 18. Explicit Non-Goals / Out of Scope

- Comments, followers, direct messages, reposts, and social graphs.
- Team workspaces and real-time collaborative editing.
- A generic chatbot unrelated to the user's notes.
- Autonomous AI modification, movement, tagging, or publication without confirmation.
- A large administration dashboard or enterprise moderation platform.
- A creator analytics platform.
- A personalized recommendation-engine feed.
- Password-manager positioning or a credential-management subsystem.
- A committed E2EE Vault.
- Arbitrary saved-URL crawling or automatic remote navigation.
- Unnecessary microservices or infrastructure introduced for demonstration.
- An API gateway in the approved topology.
- RabbitMQ in the approved topology.
- Kubernetes without a separately justified deployment requirement.
- Elasticsearch/OpenSearch at the current expected scale.
- Browser-storage JWT authentication.
- A separate public publication-revision history product.

### 19. Product Completion Definition

The Target Flagship is product-complete only when:

1. Every Target Flagship functional requirement is implemented or has an explicitly approved replacement preserving its user outcome.
2. The canonical acceptance scenarios pass with production-representative integrations or justified deterministic substitutes.
3. Cross-user isolation is proven across every direct and derived retrieval path.
4. New accounts default future notes to AI OFF; Create Note initializes its control from that default; per-note overrides persist independently; default changes are non-retroactive; and deliberate bulk enable/disable actions remain distinct from that default.
5. Relevance, focused lookup, aggregation, and exhaustive extraction are separately testable and honestly presented.
6. Note creation, deliberate Save, unsaved-state protection, conflict recovery, lifecycle management, version restore, and ordinary search form a polished product without AI.
7. Public snapshots cannot be changed by ordinary private editing or saving, and unpublish reliably removes public eligibility.
8. Authentication, OAuth identity linking, MFA, recovery, sessions, CSRF defenses, avatar safety, and Markdown rendering meet the approved security requirements.
9. AI and engagement dependencies degrade without taking healthy core note capabilities down.
10. Accessibility, responsive UX, observability, reproducibility, repository hygiene, and measurable performance baselines satisfy approved downstream criteria.
11. Optional / Future and Out-of-Scope features have not been smuggled into the completion definition.
12. Public documentation accurately describes proven behavior without novelty, security, privacy, or completeness overclaims.
13. Informed provider disclosure occurs before the first actual AI processing regardless of whether enablement came from Create Note, a per-note change, or a bulk action; disabling a note reliably deindexes it, and attachments follow the note's AI state.
14. Deterministic non-AI retrieval remains available for authorized AI-disabled notes, while AI-dependent paths exclude them.
15. Supported multimodal attachments remain authorized and usable independently of AI-processing success, and AI-enabled images, audio/voice recordings, bounded video clips, and PDFs can be rediscovered and reasoned over with provenance.
16. Reproducible offline evaluation reports task-specific retrieval and AI metrics, pursues the approved at-least-90% engineering ambition where appropriate, and treats every tested isolation leak as failure.

### 20. Open Product Decisions

No blocking product-level decisions remain for baseline approval.

Public handles, the future-note AI default, first-processing disclosure, independent per-note AI state, explicit bulk actions, bounded version history, multimodal attachment scope, and sensitive-content-warning classification are resolved at the product level. Their exact UX, policy parameters, supported-media limits, and technical mechanisms remain downstream design decisions and do not reopen the approved product behavior.

### 21. Glossary

**AI-enabled note:** A note whose persistent independent AI state is ON, permitting it and its supported attachments to participate in configured AI-dependent processing and retrieval, subject to authorization, informed disclosure, and provider policy.

**AI-disabled / AI-excluded note:** A note whose persistent independent AI state is OFF. The note and its attachments are forbidden from AI-dependent processing while preserving authorized ordinary and deterministic non-AI behavior, including results initiated from a unified knowledge-query surface. This state is not encryption.

**Default AI access for new notes:** An account setting whose ON/OFF value initializes the **Use this note with AI** control in future Create Note flows. It defaults to OFF for new accounts, does not govern existing notes, and is not an AI pause or enablement gate.

**Attachment:** A bounded supported image, audio/voice recording, video clip, or PDF owned through a note and governed by that note's authorization, independent AI state, lifecycle, and publication decisions.

**Ask My Knowledge / knowledge-query capability:** The authenticated personal-knowledge query experience that routes authorized requests to deterministic non-AI or AI-dependent strategies. Only AI-dependent stages require AI-enabled content; routed deterministic non-AI operations may use authorized AI-disabled notes.

**Authorized corpus:** The notes or public snapshots the current principal is permitted to access for the specific operation.

**Complete derived index:** A maintained representation proven to cover the full authorized source scope required for a deterministic extraction.

**Corpus-wide semantic extraction:** High-recall inspection and meaning-based classification across the authorized AI-eligible corpus for an arbitrary category requested by the user; complete corpus coverage does not guarantee perfect semantic classification.

**Exhaustive deterministic extraction:** Complete authorized inspection for supported explicitly detectable patterns or entities, such as stored URLs, without relying on top-ranked semantic chunks or generative classification.

**Focused fact lookup:** Retrieval optimized for a small, high-quality evidence set supporting a specific fact request.

**Grounded answer:** An answer whose material claims are supported by identified authorized note evidence.

**High-recall aggregation:** Retrieval and extraction intended to find relevant items scattered across many notes while honestly acknowledging that semantic completeness may be uncertain.

**Hybrid retrieval:** Product behavior combining eligible complementary lexical, fuzzy, and semantic evidence; the exact implementation is defined downstream.

**Multimodal rediscovery:** Finding authorized knowledge across supported text and attachment modalities through a unified user query.

**Media-grounded reasoning:** Answering a question by processing the content of retrieved eligible media and tying the answer to that source; it is distinct from merely locating the attachment.

**Internal application identity:** The stable non-public identifier used for ownership and authorization regardless of login method.

**NoteVersion:** An immutable retained checkpoint of private note content used for history, restoration, and publication provenance; exact representation is defined downstream.

**Private note:** User-owned note content that is not public merely because it is stored, indexed, or AI enabled.

**Provenance:** Information connecting an answer, claim, or extracted item to an authorized source note and relevant location.

**Publication:** The one current, explicitly approved public snapshot derived from a private note checkpoint.

**Publication drift:** The state in which the private source note has changed since its current public snapshot was created.

**Relevance retrieval:** Ranked retrieval intended to return the most useful matches without promising complete corpus coverage.

**Target Flagship:** The committed behavior of the finished product, independent of implementation sequence.

**Optional / Future:** A capability not promised as part of Target Flagship.

**Out of Scope:** A capability deliberately rejected for the current product direction.
