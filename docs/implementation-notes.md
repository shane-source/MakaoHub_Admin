# Staff website implementation notes

## Confirmed scope and assumptions

The website implements the existing staff API exactly: password sign-in followed by email OTP, invite acceptance, password recovery, role-specific queues, audited evidence review, listing moderation, masked-message moderation, user reports, read-only payments/bookings, audit history and staff permissions. `RECOMMENDED`: keep payment operations absent until the backend has legally confirmed release/refund state transitions and two-person approval endpoints.

The Play application is a backend-for-frontend. Browser forms talk only to Play. Play calls Spring Boot and keeps the JWT pair in a server-side session. This prevents token theft through browser storage. The current session store is intentionally process-local for developer testing; removing it makes authenticated navigation impossible.

## File-by-file map

| File | What it does and connection | What breaks if removed |
|---|---|---|
| `build.sbt` | Selects Play Java, Java 17, WS, filters and JUnit. | The project cannot compile, call the API or run tests. |
| `project/plugins.sbt` | Loads the Play 3.0.12 sbt plugin. | Routes, Twirl and Play tasks are not generated. |
| `project/build.properties` | Pins the sbt version. | Builds become machine-dependent. |
| `conf/application.conf` | Defines secure cookies, CSRF, CSP, response headers, API endpoint, timeout and media allowlist. | Security controls and runtime wiring are absent. |
| `conf/routes` | Maps public auth, protected staff, evidence and moderation URLs to controllers. | Browser requests cannot reach application code. |
| `app/models/StaffModels.java` | Converts backend JSON into narrow immutable view records and owns staff navigation visibility. | Views would consume arbitrary JSON and role navigation would drift. |
| `app/services/StaffSessionStore.java` | Holds short opaque challenge/session IDs and server-only JWT state; enforces production TLS/cookie/secret settings. | MFA continuity and safe browser sessions fail. |
| `app/services/StaffApiClient.java` | Calls the fixed backend origin, rotates refresh tokens once, adds idempotency keys and proxies allowlisted media. | The UI cannot use the backend safely. |
| `app/controllers/StaffWebSupport.java` | Centralizes form parsing, validation, CSRF extraction and no-store headers. | Trust-boundary checks would be duplicated or omitted. |
| `app/controllers/StaffAuthController.java` | Implements sign-in, OTP, sign-out, recovery and invite acceptance. | Staff cannot authenticate. |
| `app/controllers/AdminController.java` | Implements each staff queue, detail, document/media proxy and supported mutation. | The staff workspace has no operational screens. |
| `app/views/main.scala.html` | Provides the HTML shell and local stylesheet. | Pages lose their document structure and CSP-compatible styling. |
| `app/views/staffLayout.scala.html` | Renders identity, role-limited navigation and CSRF-protected sign-out. | Protected screens lose consistent role navigation. |
| `app/views/login.scala.html` | Password sign-in matching the supplied design. | Initial staff authentication has no UI. |
| `app/views/otp.scala.html` | Six-digit email MFA form. | MFA challenge cannot be completed. |
| `app/views/recover.scala.html` | Generic recovery request and reset form. | Staff cannot recover credentials. |
| `app/views/acceptInvitation.scala.html` | Invite-only account activation. | Invited staff cannot create credentials. |
| `app/views/errorPage.scala.html` | Safe generic backend/session error state. | Failures become raw or inconsistent. |
| `app/views/admin/verificationQueue.scala.html` | Filters, searches, assigns and opens verification cases. | Verification reviewers cannot work their queue. |
| `app/views/admin/verification.scala.html` | Audited evidence access, reviewed state and approve/reject decision. | Role verification cannot be decided. |
| `app/views/admin/listing.scala.html` | Listing evidence and moderation decision without exact location. | Safety moderators cannot review a listing. |
| `app/views/admin/actionQueue.scala.html` | Shared masked-message and user-report cards/actions. | Safety queues have no working UI. |
| `app/views/admin/payments.scala.html` | Read-only payment and booking records. | Payment reviewers lose oversight; no money mutation is added. |
| `app/views/admin/team.scala.html` | Staff invitations and permission changes for staff leads. | Staff leads cannot administer the staff team. |
| `app/views/admin/table.scala.html` | Shared desktop tables for queues, audit and activity. | Several read-only queues stop rendering. |
| `public/stylesheets/staff.css` | Implements the warm Makao palette and fixed desktop workspace. | The reference layout and desktop-only behavior are lost. |
| `public/images/staff-house.jpg` | Reuses the approved Makao house image on staff auth. | The sign-in page no longer matches the visual source. |
| `test/models/StaffModelsTest.java` | Proves staff roles cannot see unrelated queue navigation. | UI permission regressions are not caught. |
| `test/services/StaffSessionStoreTest.java` | Proves opaque sessions and production fail-closed settings. | Token/config regressions are not caught. |
| `test/controllers/StaffWebSecurityTest.java` | Proves unauthenticated redirect, CSRF rejection, no-store and no browser token text. | Core web security behavior is unverified. |

## Classes and methods

### `StaffModels`

- `StaffRole.parse` accepts only backend staff-role names; an unexpected value fails closed.
- `StaffRole.canSee` maps each queue to its assigned role. This is UI minimization only; Spring Boot remains the authorization authority on every request.
- `StaffRole.label` produces human-readable role names.
- Record types expose only fields needed by their view, limiting accidental PII rendering.
- `items`, `text`, `shortId`, `money`, `rent`, `bytes`, `values`, `safeState` and `safeInstant` normalize backend values. Money reads integer minor units and never uses floating point for financial calculation; the decimal in `bytes` is file-size display only.

### `StaffSessionStore`

- Constructor reads the maximum staff lifetime and refuses unsafe production HTTP, cookies or default secrets.
- `saveChallenge` rejects missing/expired/oversized provider state, then replaces the backend challenge with an opaque random browser identifier and stores the expiry server-side.
- `challenge` rejects missing/expired challenges.
- `deleteChallenge` removes completed authentication state.
- `activate` rejects missing/oversized tokens and expired refresh state, stores accepted tokens only in process memory and caps the session by the configured staff lifetime.
- `active` rejects and removes expired sessions.
- `deleteActive` invalidates local sign-in state.
- `newId` uses 256 bits from `SecureRandom`, URL-safe encoding, and clears the source byte array.
- `cleanup` removes expired entries opportunistically.
- `parseInstant` fails to the caller-provided safe expiry.
- `ActiveSession` getters expose state only to server services; synchronized refresh methods ensure concurrent requests share one refresh rotation.

Security-critical activation flow, in order:

1. Generate an unrelated random session ID, so JWT contents cannot be inferred from the cookie.
2. Parse backend expiries, defaulting invalid refresh expiry to immediate expiration.
3. Cap the session at eight hours even when a provider returns a longer refresh life.
4. Store tokens in server memory and put only the random ID in Play's signed HttpOnly cookie.

### `StaffApiClient`

- Public auth methods send typed JSON to fixed backend paths.
- `get`, `getBinary` and `post` attach the server-held bearer token; mutations also send a fresh idempotency key.
- `logout` asks the backend to revoke the active family.
- `fetchTrustedUrl` accepts only HTTPS or local-development HTTP and an exact configured hostname, preventing arbitrary server-side fetches.
- `authorized` and `authorizedBinary` retry once after a 401 using refresh rotation.
- `currentToken` reuses a valid access token; otherwise one synchronized refresh runs and rotates both tokens.
- `postPublic` uses a separate authentication deadline and never automatically retries an auth POST.
- `authenticationFailure` distinguishes request timeouts (504) from other connection failures (503), returning fixed guidance and logging only the internal operation path and failure category.
- `request` rejects non-local paths, preventing callers from overriding the configured backend origin.
- Response helpers safely parse JSON/binary responses and keep provider internals out of the UI.

Security-critical refresh flow, in order:

1. Reuse an access token only when it remains valid beyond the 30-second skew.
2. Synchronize on the server-side session and join an existing refresh when present.
3. Send the refresh token only to the configured backend refresh endpoint.
4. Reject any non-success refresh as an expired staff session.
5. Atomically replace both rotated tokens and clear the in-flight marker.
6. Retry the original request once only, preventing loops and refresh storms.

### `StaffWebSupport`

- `csrf` reads Play's request-bound token for forms.
- `field` handles absent form bodies and strips surrounding whitespace.
- `checked`, `validEmail` and `validPassword` validate trust-boundary input.
- `flash` reads one-request feedback.
- `noStore` prevents browser/proxy caching of staff and document state.

### `StaffAuthController`

- `home` chooses sign-in or workspace without trusting a client role.
- `signIn` renders the first MFA step.
- `startSignIn` validates bounded credentials, calls the backend and stores only an opaque challenge ID in the browser.
- `verify` refuses expired flows and renders the masked email supplied by the backend.
- `completeSignIn` validates exactly six digits, exchanges the challenge for tokens and replaces challenge state with opaque session state.
- `signOut` deletes local state before best-effort backend revocation, so a provider outage cannot keep the browser signed in.
- `recover`, `requestRecovery` and `resetPassword` keep recovery messages/forms server-rendered and CSRF-protected.
- `acceptInvitation` and `completeInvitation` expose invite-only activation; there is no public registration route.

### `AdminController`

- `workspace` fetches live `/admin/me` state then routes by backend staff role.
- Queue methods validate filters/page values, call their fixed API, create narrow view models and render no-store responses.
- Mutation methods validate enum/UUID/text input, require CSRF, attach idempotency keys and redirect with safe backend detail.
- `document` requires the live staff session and backend assignment check on every request, accepts only PDF/JPEG/PNG and adds sandbox/no-store headers.
- `listingMedia` re-fetches the authorized listing, locates its media server-side and proxies only allowlisted image hosts.
- `payments` performs GET-only oversight; no release/refund/payment button exists.
- `withStaff` validates the opaque session and calls `/admin/me` for current backend role/permission on every page. Only 401/403 removes the session; temporary 5xx errors preserve it.
- `mutationRedirect` and `backendPageError` invalidate only an unauthorized session and otherwise show safe failures.
- Remaining helpers bound pages/text, validate enums/UUIDs/content types and build URLs from server-generated routes.

Security-critical protected-page flow, in order:

1. Read the signed HttpOnly Play session cookie and resolve its opaque ID in server memory.
2. Reject missing/expired state before any queue call.
3. Call `/api/v1/admin/me` on the backend, which enforces MFA/session/role status.
4. Build a staff identity only from that backend response, never from form/query data.
5. Call a fixed queue endpoint using the server-held token.
6. Let Spring Boot enforce the queue role, assignment and document-view audit.
7. Render narrow models with no-store headers and CSRF tokens for every mutation.

## Failure modes

- Backend unavailable: a generic 503 page is shown and the valid local staff session is retained.
- Authentication response timeout: a 504 explains that the operation may have completed; no automatic POST retry sends extra codes or repeats an acceptance/verification operation.
- Access token expired: one refresh is attempted; reuse/invalid refresh becomes a signed-out session.
- Unknown backend role or malformed `/me`: fail closed to a generic staff-access error.
- Unsupported document/media type: return 502 without reflecting private content.
- Signed media host absent from the exact allowlist: deny the server-side fetch.
- Stale moderation timestamp: backend conflict detail is displayed and no state is silently overwritten.

## Authentication timeout correction

The reported symptom was delivery of an OTP while the website still showed server
unavailability. The existing HTTP client applied the same 12-second deadline to
ordinary reads and to staff authentication. The backend performs password checks,
SMTP submission and transactional database writes before returning a challenge.
Its SMTP connection and read/write timeouts can themselves exceed that HTTP budget.
Thus email delivery does not prove the website has received the challenge needed to
render its OTP form. Read-only checks confirmed both servers were reachable; no real
password, code or extra OTP request was used during this correction.

**RECOMMENDED developer-testing default:** wait up to 60 seconds for authentication,
while retaining the existing 12-second ordinary API timeout. SMTP outages, network
failure or failed database commit still remain possible; this does not guarantee
delivery or invent recovery of a challenge whose response was lost. The remaining
manual verification is the owner's own sign-in after the website reloads.

Changed files, all under `/home/hp/Desktop/makao-hub-web/`:

- `conf/application.conf` adds `makao.api.authRequestTimeout = 60s` and the optional
  `MAKAO_AUTH_REQUEST_TIMEOUT` environment override. Removing it restores the too-short
  shared deadline. It does not control OTP expiry, MFA session duration or send limits.
- `app/services/StaffApiClient.java`: the constructor reads the additional duration
  and refuses non-positive values or an auth deadline shorter than ordinary requests.
  `postPublic` applies this deadline to the existing fixed auth routes (including
  OTP verification, invitations, recovery and refresh). It submits exactly once.
  `authenticationFailure` walks at most eight causes to classify a timeout, returns
  fixed 504/503 ProblemDetail text and logs only an internal path/category. It never
  logs credentials, emails, tokens, exception messages or response bodies. Removing
  this classification hides an ambiguous timeout behind the generic outage message.
  Protected reads and their existing authorization/refresh behavior remain intact.
- `.env.example` shows the optional duration, without credentials.
- `README.md` explains the budget and why auth requests are not automatically retried.
- `test/services/StaffAuthenticationTimeoutTest.java` uses an ephemeral localhost
  HTTP server with synthetic JSON and delayed responses. `start` connects a real
  Play test application/WS client to that server; `stop` closes both and their workers.
  `delayedLoginAndOtpStillReachAnActiveServerSideSession` submits the real CSRF-protected
  forms, checks redirection to the OTP screen despite a delay longer than the ordinary
  deadline, then checks session activation only after verification. It also proves
  tokens stay outside the browser session and that each auth POST occurs once.
  `genuineTimeoutDoesNotResendTheLoginRequest` checks a shorter test auth deadline
  fails with safe 504 guidance and one request. `backendRateLimitIsPreservedWithoutCreatingAnotherChallenge`
  passes through the backend's 429 without submitting verification or a retry.
  `csrf` reads the actual hidden token so the test does not bypass the CSRF filter;
  `respond` simulates delayed JSON without Gmail, Supabase or real credentials.
  Removing these tests loses executable proof of the delayed flow, request counts,
  browser-token protection and sending-limit preservation.

No backend, data-model, migration, permission or screen-layout changes are required.
Libraries remain Java 17, Play Framework 3.0.12, its existing WS implementation and
JUnit 4.13.2. The test server uses the JDK's built-in HTTP server; no dependency was
added. No payment, Safaricom or legal policy changed. Production mail delivery and
network timeout budgets still need measurement with the chosen provider.

Verification: `sbt test` on Java 17 passed all 9 website tests, including the three
delayed-authentication/timeout/rate-limit scenarios and the existing session,
permission-navigation and CSRF tests. The fake backend delays both successful auth
responses beyond the ordinary request deadline; only the separate auth deadline
allows the real forms to complete. Test output contains fixed operation/category
diagnostics, without real credentials or codes. No real OTP was sent by the tests.

## Staff invitation failure correction

Scope: sending an invitation from the existing staff-team form, preserving MFA,
role checks, separate staff accounts and CSRF. No database schema, stored records,
payment rules or layouts changed. During read-only checks the website returned 200
and the protected backend returned the expected anonymous 401. This establishes
reachability at that moment, not the cause of a past failed request. No real
invitation or OTP was sent during this work.

Reproduced defects before the fix:

- A synthetic invitation taking 800 ms failed under an ordinary 300 ms deadline
  despite a configured 5-second auth deadline. A true timeout returned a generic
  503 rather than an explicitly uncertain 504. The real CSRF-protected form failed
  to report success for the delayed response.
- Expected Spring `ResponseStatusException` rejections returned no JSON body and
  used servlet `sendError`. The website could not display the duplicate-account
  explanation. A protected error redispatch can also change the reported error.
- A temporary refresh failure became a 401 session-expired error, losing the
  distinction between backend unavailability and a definitively rejected token.

Design: retain existing APIs and JSON. The fixed invitation POST uses the already
configured auth timeout (60 seconds by default, **RECOMMENDED for developer testing**).
Successful results remain `INVITED`; no new invitation or delivery states are
invented. No automatic timeout retry is added. An expected rejection becomes
`application/problem+json` with its original status, a safe `detail`, and code
`STAFF_REQUEST_REJECTED`. Refresh 401/403 still requires sign-in; temporary failure
does not authorize any operation or discard the session as if credentials failed.

Changed files and method-level explanation:

- `/home/hp/Desktop/makao-hub-web/app/services/StaffApiClient.java`:
  - `post`: exact-match the fixed `/api/v1/admin/staff/invitations` route before
    selecting the longer existing deadline. All other protected POSTs keep the
    ordinary deadline. The bearer token and original idempotency key still travel
    only in headers. Removing the selection restores the reproduced short deadline.
  - `currentToken`: keep the existing refresh synchronization and rotation. On a
    failed response, only 401/403 creates `SessionExpiredException`; other statuses
    create `ApiRequestException`. No mutation runs with an invalid token. Removing
    the distinction causes unnecessary sign-outs during an outage.
  - `ApiRequestException` and its constructor carry the failed refresh response
    through the existing asynchronous chain, using a fixed exception message and
    no credentials. This is not an authentication bypass or an automatic retry.
  - `postPublic`: reuse `requestFailure` for fixed authentication operations.
  - `requestFailure` replaces `authenticationFailure`: inspect at most eight nested
    causes for Java timeout types; return 504 with a result-uncertain warning, or
    503 with backend-connectivity guidance. Log only fixed operation/category labels,
    never request paths, user identifiers, exceptions, tokens or message bodies.
    A timeout does not prove rollback or non-delivery. Removing this helper hides
    the actionable distinction and risks duplicate manual submissions.
  - `apiFailure`: first preserve genuine session rejection, then propagate a failed
    refresh response, then classify protected-operation transport failures using
    the same helper. Both JSON and binary authorized callers use this path.
- `/home/hp/Desktop/makao-hub-backend/src/main/java/com/makaohub/backend/admin/AdminController.java`:
  - `rejected(ResponseStatusException)` is controller-scoped, not a global override.
    `@ExceptionHandler` catches expected failures after service checks; it does not
    intercept/bypass the security filter. First select fixed status-specific text.
    For 409 only, compare the reason against two exact non-sensitive constants
    (existing account and self-permission change); unknown reasons are never echoed.
    Build `ProblemDetail`, attach a fixed error code, and return `ResponseEntity`
    with the original status and `Cache-Control: no-store`. This avoids `sendError`
    and protected error redispatch. These lines prevent information disclosure,
    misleading status changes and stale cached responses. Removing them restores
    the reproduced empty JSON response. Existing global mail-provider/validation
    exception handling is unchanged.
- `/home/hp/Desktop/makao-hub-web/conf/application.conf`: comment now correctly
  identifies both auth and invitation SMTP operations. No config value changed.
- `/home/hp/Desktop/makao-hub-web/README.md`: documents invitation waiting time,
  the existing override and no automatic retry. Removing it loses run guidance.
- `/home/hp/Desktop/makao-hub-web/test/services/StaffInvitationFailureTest.java`:
  - `start` creates an ephemeral JDK HTTP backend, a real Play application, counters
    and synthetic server-only tokens. Handlers simulate staff identity, invitation,
    staff-list and refresh responses; no external provider or database is contacted.
  - `stop` closes Play, the server and workers, preventing test-resource leaks.
  - `activate` seeds a synthetic valid local session, optionally with expired access
    to exercise refresh. It does not disable actual production MFA checks.
  - `submit` obtains the real team form and CSRF cookie/token, then submits that form.
  - `respond` reads/closes the request and emits delayed synthetic JSON, closing the
    exchange even on interruption.
  - `delayedInvitationCompletesThroughTheRealCsrfProtectedForm` checks the real form
    reports success after a response exceeding the ordinary deadline, with one POST.
  - `existingAccountRejectionIsShownWithoutRetrying` checks 409 guidance survives.
  - `ambiguousInvitationTimeoutIsNotRetriedOrReportedAsSuccess` checks 504 and exactly
    one POST, preventing a hidden second email on timeout.
  - `temporaryRefreshFailureDoesNotClaimTheSessionExpiredOrSendInvitation` checks
    503, no invitation, and a retained local session, never access granted on failure.
  - `invalidRefreshStillRequiresSignInAndDoesNotSendInvitation` checks 401 and no
    invitation when refresh is definitively rejected.
  - `invitationWithoutCsrfNeverReachesBackend` models a cookie-bearing browser
    request with no CSRF token; it must fail 403 before any backend invitation call.
  - `stoppedBackendReportsConnectionFailureWithoutSendingAnInvitation` stops only
    the fake backend and checks 503 connectivity guidance without a POST.
- `/home/hp/Desktop/makao-hub-web/test/services/StaffAuthenticationTimeoutTest.java`:
  `start` adds a synthetic readiness endpoint; `genuineTimeoutDoesNotResendTheLoginRequest`
  warms the HTTP connection before its deliberately tiny 250 ms response deadline.
  The original test occasionally timed out before reaching its handler under load,
  which could not prove single submission. No real auth request is used for warmup.
- `/home/hp/Desktop/makao-hub-backend/src/test/java/com/makaohub/backend/admin/AdminSecurityBoundaryTest.java`:
  `existingAccountInvitationReturnsSafeConflictWithoutErrorRedispatch` verifies
  JSON 409 and no servlet error message; `unknownFailureReasonCannotExposePrivateData`
  verifies arbitrary exception content is omitted; `servicePermissionDenialRemainsForbiddenWithSafeBody`
  preserves 403; `invitationWithoutMfaNeverReachesInvitationService` preserves the
  earlier security filter. These use synthetic inputs and mocked service/mail calls.
- `/home/hp/Desktop/makao-hub-backend/docs/admin-backend.md` links this walkthrough
  to keep backend operations and web failure handling documented together.

Libraries remain Java 17 / Play 3.0.12 / JUnit 4.13.2 for the website and Java 21 /
Spring Boot 4.1.1 with its managed Spring MVC/Security/JUnit dependencies for the
backend. No new libraries or migrations are needed. The tests are real HTTP/Play
form and MockMvc integration checks with synthetic dependencies, not a claim of a
live Gmail/Supabase end-to-end delivery test.

Verification after rebuilding: run website `sbt test` on Java 17 and backend
`./mvnw -Dtest=AdminSecurityBoundaryTest,StaffInvitationAcceptanceHttpTest,StaffBootstrapTest test`
on Java 21. Results: all 16 website tests and all 25 targeted backend tests passed,
including the previously failing timeout, empty-response and refresh-outage cases.
The existing short authentication-timeout check was stabilized with the synthetic
readiness request described above. No real email, database migration or account
change was performed by these checks.
Restart the backend to load its controller change; reload/restart Play.
Then sign in and send one invitation to an unused dedicated test-staff address.
Do not immediately resubmit an ambiguous timeout: inspect the inbox first.

Remaining risks: backend/database/SMTP outages remain possible; a longer deadline
does not guarantee delivery. SMTP submission is still synchronous and not a durable
delivery outbox; mail accepted before a later database failure can produce an
unusable invitation. No new custody, refund, privacy or provider policy is decided.
**RECOMMENDED next task:** perform that single manual invitation/acceptance check,
then separately design delivery-status/recovery if needed. Production retention and
staff-document access still need Kenyan legal review; provider delivery limits and
operational time budgets need confirmation before production. No Safaricom behavior
or money movement changed.

## Verification acknowledgement correction

Confirmed cause: the reported action was **Mark reviewed**. A read-only, case-scoped
database check returned only document types and policy/view/review booleans: all
four landlord files were under the current upload policy, but none had a current
view record for the assigned reviewer. No identity bytes, names, object paths or
credentials were printed. The page nevertheless offered an enabled acknowledgement
button, and the backend's expected 409 lost its useful explanation in the generic
conflict response. This was not evidence of a broken verification record.

Design: expose the existing server-recorded `viewed_at` alongside `reviewed_at`.
The join remains scoped to document ID, role, assigned reviewer and exact evidence
update timestamp. The UI enables acknowledgement only for a currently viewed file
on a pending case. Viewing never acknowledges or approves anything automatically.
Because evidence opens in a separate tab, the page explicitly asks the reviewer to
return and refresh review status before marking it reviewed. This uses a normal
GET link, not inline scripts or a new polling service. **RECOMMENDED:** keep these
separate human actions; a successful download alone is not proof of human review.

No data model migration, live decision, document replacement, assignment or permission
change was made. Existing not-viewed, changed-evidence and approval guards remain.
Failure states are actionable fixed messages; they do not include document contents.

Changed files and methods, and why they exist:

- `/home/hp/Desktop/makao-hub-backend/src/main/java/com/makaohub/backend/admin/AdminWorkflowService.java`:
  `verificationCase` selects `r.viewed_at` in its existing evidence query. The current
  `assignedReviewer` guard runs first. The join still requires matching role and
  reviewer and `r.evidence_updated_at=d.updated_at`; a replacement cannot inherit an
  old view/review. Only READY evidence belonging to the applicant is returned.
  No document bytes or object paths are added. Removing this field makes the UI
  unable to distinguish an unviewed file from one ready for acknowledgement.
- `/home/hp/Desktop/makao-hub-backend/src/main/java/com/makaohub/backend/admin/AdminController.java`:
  `rejected` extends its 409 reason allowlist with five fixed review instructions:
  view first; review every required current file; reload a changed record; only
  pending cases can be decided; and replace legacy evidence through protected upload.
  First test the HTTP status, then compare against exact constants, then expose only
  an exact match. Null/unknown reasons retain the generic fallback. Arbitrary exception
  text remains hidden, preventing accidental PII disclosure. This changes presentation,
  not authorization or the decision state machine.
- `/home/hp/Desktop/makao-hub-web/app/models/StaffModels.java`:
  `DocumentItem.viewed` carries only current server view state to the template. It is
  not a client-supplied permission, token or new persisted field. Removing it loses
  the template's precondition signal.
- `/home/hp/Desktop/makao-hub-web/app/controllers/AdminController.java`:
  `verification` maps non-null, present `viewed_at` into that record field, just like
  existing `reviewed_at`. Missing metadata fails closed; restart the backend when
  deploying both sides. The actual POST still relies on backend checks, not this flag.
- `/home/hp/Desktop/makao-hub-web/app/views/admin/verification.scala.html`:
  render short view/return/refresh guidance using the existing case GET route. Keep
  evidence in a separate private tab and keep the explicit CSRF-protected review form.
  Disable Mark reviewed when `!document.viewed()` or the case is no longer PENDING;
  reviewed files retain their Reviewed label. This prevents the misleading initial
  action but does not replace server-side enforcement against forged requests.
- `/home/hp/Desktop/makao-hub-web/public/stylesheets/staff.css`:
  the scoped `.evidence-actions button:disabled` rule dims the unavailable button and
  uses a not-allowed cursor. Native HTML `disabled` enforces the interaction; CSS
  merely communicates it. No layout, external asset or new script is introduced.
- `/home/hp/Desktop/makao-hub-web/test/controllers/VerificationReviewFlowTest.java`:
  `start` connects a real Play controller/template/HTTP client to a synthetic local
  backend and synthetic server-side session; no live account is used. Fake handlers
  simulate view and acknowledgement separately, exposing no real image. `stop`
  closes both applications. `get` asserts successful routed reads; `disabled`
  checks the native HTML attribute; `reply` writes/closes synthetic HTTP responses.
  `everyRoleRequiresViewingBeforeMarkingAndAcknowledgementStaysSeparate` exercises
  all three role URLs: initially disabled, successful private evidence read, refresh
  enables marking, then a real CSRF-protected POST records only acknowledgement.
  `finishedCaseCannotOfferAnotherReviewAcknowledgement` checks a decided case stays
  disabled. Removing these tests loses proof of the complete cross-role UI flow.
- `/home/hp/Desktop/makao-hub-backend/src/test/java/com/makaohub/backend/admin/AdminSecurityBoundaryTest.java`:
  `unviewedDocumentConflictKeepsItsActionableSafeReason` invokes the real MVC endpoint
  with synthetic staff authentication and mocked service rejection, preserving 409,
  exact safe guidance and no servlet error redispatch. Existing denial/leak tests stay.
- `/home/hp/Desktop/makao-hub-backend/src/test/java/com/makaohub/backend/admin/AdminWorkspaceIntegrationTest.java`:
  `assignedReviewerMustViewAndAcknowledgeEveryCurrentDocumentBeforeApproval` now also
  checks null view metadata and 409 before viewing, non-null metadata after viewing,
  and invalidation after a synthetic evidence timestamp change. The stale file blocks
  marking and approval until viewed and explicitly marked again. Existing assignment,
  all-required-evidence, idempotent decision and single-audit assertions remain.
  Tests run against a newly initialized isolated local PostgreSQL database, never the
  configured real application database; they exercise existing Flyway migrations.
- `/home/hp/Desktop/makao-hub-backend/docs/admin-backend.md`: links this explanation
  so operators can distinguish expected safeguards from application errors.

Libraries remain Java 17, Play 3.0.12 and JUnit 4.13.2 on web; Java 21, Spring Boot
4.1.1 and existing managed test/JDBC libraries on backend. PostgreSQL 15 is used for
isolated database integration tests. No dependency is added.

How to verify manually: restart the backend and website, sign in as the assigned
reviewer, open the case, click View evidence, inspect it yourself, return to the
case and click refresh review status. Mark reviewed should then be available for
that current file. Repeat for every required file. Approval/rejection remains the
authorized human reviewer's decision, never an agent action during this fix.

Verification results: all 18 website tests passed on Java 17; all 12 targeted backend
HTTP/security checks passed on Java 21; all 14 `AdminWorkspaceIntegrationTest` checks
passed against the isolated PostgreSQL 15 database. Existing Flyway migrations ran
successfully on that new database. The integration test verifies acknowledgement
before view is blocked and evidence replacement clears the current view/review
signals until a new view and explicit acknowledgement. No live document or decision
was used.

Risks remaining: a document download establishes only authorized delivery, not that
a human examined it; approval still requires their explicit acknowledgement/decision.
Evidence replaced between refresh and marking must still be blocked by the backend.
Live mail/storage/database delivery is not proven by synthetic tests. No legal,
Safaricom or payment rule changed; production document handling, staff access and
retention still require the existing provider/privacy review and Kenyan counsel.
**RECOMMENDED next task:** manually verify one view/acknowledgement sequence without
approving until the reviewer has completed the actual evidence assessment.

## Same-tab private evidence viewer correction

This supersedes the separate-tab navigation in the acknowledgement correction above.
Live browser checks reproduced View evidence doing nothing: no new tab appeared.
Direct navigation to that same authenticated image reached the browser image viewer
(1728 by 1728 pixels). This demonstrated that the observed link-navigation problem
was not proof of a missing storage object. No document contents were copied into this
document, and no live acknowledgement, approval or rejection was submitted.

Scope/design: keep the case and viewer in one tab. The existing document URL now
returns a small authenticated HTML viewer with Back to review. JPEG/PNG load through
a new same-origin `/file` route. PDFs open in the same tab for audited review; the staff
UI does not expose a download action. No PDF.js,
third-party viewer, scripts, iframes or broadened storage URLs are introduced. Merely
loading viewer HTML does not record a document view; successful file delivery still
runs the existing backend audited access path. Acknowledgement remains a separate
CSRF-protected human action.

No backend, database, role, assignment, verification decision or migration changed.

Changed files and methods:

- `/home/hp/Desktop/makao-hub-web/app/controllers/AdminController.java`:
  - `document` validates role/UUIDs before any API call. `withStaff` still checks the
    server-side session and backend MFA identity. Fetch the case through the backend's
    assigned-reviewer/ownership guard. Locate the requested ID in that case's current
    READY documents; reject absent or unsupported types. Generate file/back links
    only from validated identifiers and generated routes; render no-store HTML.
    These checks prevent changing a URL to reveal another case's metadata or active
    content. Removing them would lose the viewer's authorization boundary.
  - `documentFile` wraps **every** returned result, including 3xx/4xx failures, in
    no-store and a fixed restrictive policy: sandbox, no default resources, scripts,
    objects, base URI, forms or frame ancestors. It never returns a result before
    that wrapper. This matters because its route uses the manual CSP described below.
  - `loadDocumentFile` retains the previous byte-proxy logic: validate role/UUIDs,
    require the active staff session, call the backend document endpoint (which
    enforces assignment, ownership, current evidence and audits), handle failure
    before serving bytes, then allow only JPEG/PNG/PDF content types. Generic filenames
    now have the corresponding extension. No user filename, credentials or signed
    storage URL is exposed. `nosniff`, private no-store and no-referrer remain.
- `/home/hp/Desktop/makao-hub-web/app/services/StaffApiClient.java`:
  - `getBinary` uses the existing auth/SMTP HTTP budget (60 seconds by default,
    **RECOMMENDED developer-testing budget**) because private reads include a storage
    transfer and transactional audit. The backend storage deadline alone can exceed
    the ordinary 12-second website budget. This does not guarantee availability or
    change storage limits, JWT expiry, authorization or retry policy.
  - `toBinaryResponse` now sends non-2xx responses through the existing JSON error
    parser, preserving safe backend ProblemDetail explanations. Successful responses
    remain bytes. Removing this distinction hides storage/permission failures behind
    the generic message and risks treating an error body as evidence.
- `/home/hp/Desktop/makao-hub-web/conf/routes`: existing document GET now serves the
  viewer; new `/file` GET serves authenticated bytes. A `+ nocsp` modifier applies
  **only to `/file`** so Play's HTML CSP filter cannot overwrite the stronger manual
  policy from `documentFile`. This is not an unprotected route: the manual policy
  is applied to all results and tested, and all session/backend checks still run.
  The modifier and overwrite behavior were verified against Play 3.0.12's installed
  `reference.conf` and `DefaultCSPResultProcessor`. All other routes retain their
  existing global CSP; no global security-filter setting is disabled.
- `/home/hp/Desktop/makao-hub-web/app/views/admin/document.scala.html`: new minimal
  viewer uses the existing HTML shell and generated relative links. For images,
  `<img>` requests the protected file and a direct-open fallback exposes readable
  errors if the preview fails. PDF links use the same protected file endpoint, not
  an embedded active document. Back to review issues a fresh case GET, updating the
  viewed signal. It never submits a review or a decision.
- `/home/hp/Desktop/makao-hub-web/app/views/admin/verification.scala.html`: remove
  `target="_blank"` and explain Back to review. Existing reviewed-state controls
  and CSRF POST remain; the refresh-status link is retained as a fallback.
- `/home/hp/Desktop/makao-hub-web/public/stylesheets/staff.css`: `.private-document`
  bounds the viewer width and spacing; `.private-evidence-image` keeps the image
  within that width without distortion. No external resource or inline script/style.
- `/home/hp/Desktop/makao-hub-web/test/controllers/VerificationReviewFlowTest.java`:
  `start` now creates an actual tiny PNG with the JDK's ImageIO/BufferedImage APIs,
  and its synthetic backend supports MIME/status/delay variations. The overloaded
  `reply` sends either UTF-8 error JSON or exact file bytes; `stop` still closes both
  applications. No real file, provider credentials or email is used.
  - `everyRoleRequiresViewingBeforeMarkingAndAcknowledgementStaysSeparate` verifies
    no popup target, wrapper/back link and protected image URL for all three roles.
    Wrapper GET alone does not enable marking; the file GET returns a decodable PNG,
    after which the refreshed case permits a CSRF-protected acknowledgement.
  - `pdfHasSameTabOpenAndDownloadWithoutEmbeddingUntrustedContent` checks native
    open/download/back links, exact binary transport, PDF MIME/extension and the
    raw-file sandbox policy. Its PDF bytes are a transport fixture, **not** a visual
    PDF rendering test or a claim that this browser's native PDF reader is available.
  - `fileRejectionKeepsItsExplanationAndDoesNotEnableAcknowledgement` checks error
    text, original 400, restrictive raw-route policy and unchanged acknowledgement.
  - `activeDocumentContentIsNotServedAndAnonymousViewerRequiresSignIn` checks SVG
    metadata is rejected and both viewer/file routes redirect anonymous requests to
    sign-in, with the manual raw-file policy still present.
  - `privateFileCanOutlastOrdinaryReadDeadline` checks exact PNG bytes survive an
    800 ms transfer with a 150 ms ordinary deadline and separate 5-second file budget.
  - `finishedCaseCannotOfferAnotherReviewAcknowledgement`, `get` and `disabled`
    retain their earlier assertions/helpers.

The new CSP test initially failed and exposed Play overwriting the controller's
raw-file policy with its HTML defaults. The route-specific/manual-policy correction
was made because of that failure, not by dropping the assertion or weakening CSP.

No dependency was added: Java 17, Play 3.0.12, JUnit 4.13.2 and JDK image APIs only.
The backend remains unchanged on Java 21 / Spring Boot 4.1.1.

`/home/hp/Desktop/makao-hub-web/README.md` also documents the private-file waiting
budget alongside authentication/invitation requests; removing that explanation
would leave operators with incorrect timeout guidance.

Verification: all 22 website tests passed, including the six viewer/transport checks
and existing session, CSRF and invitation tests. Final live browser verification
was blocked by connection refusal: read-only checks found neither localhost:9000
nor localhost:8080 listening. The user must restart both services and complete the
manual check below; the updated live page is not claimed as visually verified.

Manual verification after website reload/restart: sign in as the assigned reviewer,
open the case, choose View evidence, inspect the image, choose Back to review and
then explicitly mark it reviewed yourself. For ownership PDFs, open or download
using the viewer's protected links and inspect them in a trusted reader. Do not
approve a case solely because files loaded.

Remaining risks/confirmations: actual storage/database outages can still block
delivery and must not enable review acknowledgement. Native PDF support varies;
downloaded private files must be handled under the staff document-retention policy.
No access permissions or approved-document requirements are loosened. Kenyan counsel
and the storage/deployment provider still need to confirm production handling and
retention; no payment or Safaricom behavior changed. **RECOMMENDED next task:** the
reviewer performs one real view/back/acknowledgement sequence, separately from any
verification decision.

## 20 October — sidebar-only correction

The owner clarified that only the missing navigation below “makao hub” was wanted,
not a replacement queue/review design. The unwanted redesign was reverted, including
its synthetic preview, inline-preview replacement, extra audit-summary/count API,
selection models and their redesign-only tests. Existing document-view, current-file
acknowledgement, CSRF, assignment and backend permission protections are unchanged.
No real account data or existing audit record was changed or removed. No migration
was added or edited; integration tests use only an isolated local test database.

Rollback manifest (only the preceding unwanted redesign, not earlier user work):

- `/home/hp/Desktop/makao-hub-web/app/models/StaffModels.java`: restores the prior
  queue, case and document records; removes the redesign-only selection, display
  time and audit-summary helpers because their replacement UI is no longer used.
- `/home/hp/Desktop/makao-hub-web/app/controllers/AdminController.java`:
  `verifications` and `verification` again map the previous API contracts to the
  previous templates; `reviewDocument` keeps its original redirect. No private
  file proxy, session validation or CSRF logic is removed.
- `/home/hp/Desktop/makao-hub-web/app/views/admin/verificationQueue.scala.html`:
  restores the original API-backed applicant table, filters and assignment form.
- `/home/hp/Desktop/makao-hub-web/app/views/admin/verification.scala.html`:
  restores the previous evidence cards, protected viewer links and decision form.
- `/home/hp/Desktop/makao-hub-backend/src/main/java/com/makaohub/backend/admin/AdminWorkflowService.java`:
  restores `verificationQueue` and `verificationCase` to their prior contracts;
  removes the unwanted new summary query and DTO. Existing assignment, document
  version checks, auditing and verification decisions remain intact.
- `/home/hp/Desktop/makao-hub-backend/src/main/java/com/makaohub/backend/admin/AdminController.java`:
  `verificationQueue` returns the original `AdminStore.Page` again.
- `/home/hp/Desktop/makao-hub-backend/src/test/java/com/makaohub/backend/admin/AdminSecurityBoundaryTest.java`:
  restores the original queue mock response type.
- `/home/hp/Desktop/makao-hub-backend/src/test/java/com/makaohub/backend/admin/AdminWorkspaceIntegrationTest.java`:
  removes only the newly added metadata test for the reverted summary API; the
  previous role, ownership, current-document and replay tests remain.
- Removed only the agent-added `/home/hp/Desktop/makao-hub-web/app/views/admin/verificationAudit.scala.html`
  and `/home/hp/Desktop/makao-hub-web/test/manual/StaffDesignPreview.java`. These
  contained the unwanted extra summary component and synthetic preview, not user
  data. They are recoverable from the preceding patch history if ever requested.

Changed UI files:

- `/home/hp/Desktop/makao-hub-web/app/views/staffLayout.scala.html`: retains the
  previous shell and renders all eight navigation labels beneath the brand. For
  each existing `canSee` check, the permitted branch keeps its original route;
  the denied branch emits only an `aria-disabled` span, with no URL or action.
  Removing the denied branch would hide that sidebar item again. This is display
  only: backend role/MFA/assignment checks still prevent unauthorised access.
- `/home/hp/Desktop/makao-hub-web/public/stylesheets/staff.css`: only adds styling
  for the restricted-staff label and unavailable navigation items. Previous page
  spacing, tables, fonts and colours are restored. Removing these rules loses the
  disabled visual state, but does not change backend permissions.
- `/home/hp/Desktop/makao-hub-web/test/controllers/StaffWebSecurityTest.java`:
  `fullSidebarIsVisibleButLinksKeepTheirExistingPermissions` checks every staff
  role sees the menu labels without gaining links to another permission group.
- `/home/hp/Desktop/makao-hub-web/test/controllers/VerificationReviewFlowTest.java`:
  `applicantNamesComeFromTheApiRatherThanTheDesignReference` checks that the real
  controller maps the API's `full_name` to the applicant cell, escapes HTML and
  does not substitute the mockup's sample names or references. Fixture names exist
  only inside automated tests; no dummy fallback is added to the running website.

Data path remains `users.full_name` → permission-filtered backend verification
queue → `AdminController.verifications` → `row.applicant()` in the previous table.
The queue is not an all-accounts directory: status/role filters still apply, and
reviewers see only assigned cases. Blank queues never receive fabricated rows.

Security walkthrough: (1) the server still establishes the active staff/MFA session;
(2) the template checks the existing role permission; (3) only allowed entries get
links; (4) restricted entries are inert; (5) direct requests still face backend
permission and assignment checks; (6) Twirl escapes displayed account names. These
prevent permission escalation and stored-HTML injection without hiding menu labels.

Verification: run the web test suite on Play 3.0.12 / Java 17 and the backend admin
HTTP boundary tests on Spring Boot 4.1.1 / Java 21. Manually reload the actual site,
check the sidebar, then filter LANDLORD to see backend-returned verification cases.
No live decision, invitation or document acknowledgement is performed by these edits.
Results: 24 web tests passed; 22 backend admin tests passed (14 isolated PostgreSQL
integration tests and 8 HTTP security tests). The existing migrations ran through
version 58 on a new test schema. Live authenticated visual checking remains manual;
no production account login or document review was performed.

Remaining risks: database/storage availability can still affect live records; this
change does not bypass failures. Kenyan counsel/provider review of document access,
retention and production transport remains required; Safaricom rules are unaffected.
RECOMMENDED next task: verify the restored page while signed into the real staff site.

## 21 October — approved Staff Lead access to every sidebar page

Scope: the owner explicitly approved allowing STAFF_LEAD to open all eight existing
sidebar pages. Layout, API response contracts and real-data mapping stay unchanged.
The newly available safety and payment pages are view-only for Staff Lead. Existing
staff management, audit export and reviewer assignment permissions are preserved.
No account changes, migrations, new dependencies or new money actions are required.

File and method walkthrough:

- `/home/hp/Desktop/makao-hub-web/app/models/StaffModels.java`,
  `StaffRole.canSee`: permits STAFF_LEAD for listings, flags, reports and payments,
  in addition to its existing pages. Other roles keep their existing navigation.
  The existing sidebar template consumes this method; removing the added checks
  makes these links disabled again. Unknown section names remain denied.
- `/home/hp/Desktop/makao-hub-backend/src/main/java/com/makaohub/backend/admin/StaffRole.java`,
  `Permission` and `permits`: adds SAFETY_VIEW and PAYMENTS_VIEW. Each allows only
  the Staff Lead and its existing specialist role. SAFETY, PAYMENTS and VERIFICATION
  retain their previous role checks. This separates viewing from changing records;
  granting the old action permissions instead would silently broaden authority.
- `/home/hp/Desktop/makao-hub-backend/src/main/java/com/makaohub/backend/admin/AdminWorkflowService.java`:
  `listings`, `listingDetail` and `flags` require SAFETY_VIEW; `payments` and
  `bookings` require PAYMENTS_VIEW. Removing these changes restores the former
  access denials. Existing read audits, masking, pagination, metadata-only private
  location completeness and MFA validation remain in place. No verification,
  listing-decision or flagged-message action guard is relaxed.
- `/home/hp/Desktop/makao-hub-backend/src/main/java/com/makaohub/backend/admin/UserReportService.java`,
  `queue`: requires SAFETY_VIEW so Staff Lead can read the existing report queue.
  `act` still requires SAFETY, preventing this view permission from resolving,
  escalating, notifying users or pausing listings.
- `/home/hp/Desktop/makao-hub-web/app/views/admin/listing.scala.html`: renders the
  moderation-decision form only for SAFETY_MODERATOR. Staff Lead still sees the
  listing facts and media, not an action that the backend will reject.
- `/home/hp/Desktop/makao-hub-web/app/views/admin/actionQueue.scala.html`: renders
  flagged-message and report-decision forms only for SAFETY_MODERATOR. Its previous
  server-backed record content remains visible to authorised readers. These form
  checks prevent misleading controls, not access attacks; backend guards remain
  authoritative against forged direct requests.
- `/home/hp/Desktop/makao-hub-web/test/models/StaffModelsTest.java`,
  `staffNavigationIsLimitedToAssignedQueue`: verifies every Staff Lead section,
  denies unknown section names and retains specialist isolation assertions.
- `/home/hp/Desktop/makao-hub-web/test/controllers/StaffLeadPageAccessTest.java`:
  `start` launches a synthetic loopback API and the real Play app; `stop` cleans
  both up. `staffLeadCanOpenEverySidebarRouteAndEachLoadsItsApi` exercises all eight
  real Play GET routes, all sidebar links and their API calls.
  `leadViewsKeepSystemContentButDoNotOfferSpecialistDecisions` proves returned
  content is rendered, action forms are absent for Lead and present for Safety.
  `get` asserts successful no-store responses; `page` builds test-only JSON pages.
  Removing these tests loses coverage of the original disabled-link symptom.
- `/home/hp/Desktop/makao-hub-backend/src/test/java/com/makaohub/backend/admin/StaffRoleTest.java`,
  `leadViewAccessDoesNotGrantSpecialistActions`: verifies both view permissions and
  denies action permissions or cross-specialist reads.
- `/home/hp/Desktop/makao-hub-backend/src/test/java/com/makaohub/backend/admin/AdminWorkspaceIntegrationTest.java`,
  `staffLeadCanReadEverySectionWithoutGainingSpecialistActions`: uses real staff MFA
  and an isolated PostgreSQL schema to check all page services, listing details,
  absent exact-location fields, unchanged payment/booking counts and rejection of
  direct document acknowledgements, private case review and safety actions. Existing
  tests retain assignment, current-document and idempotency/replay coverage.

Security-critical sequence: (1) each API still establishes current enabled staff
and MFA proof; (2) read methods ask for the new specific view permission; (3) the
permission allowlist admits only Lead or the relevant specialist; (4) existing
masked/limited queries return their previous fields and record read audits;
(5) write methods still ask for specialist action permissions; (6) private identity
documents still require the verification reviewer role, document permission and
matching role-specific assignment; (7) financial writes remain unavailable. This
prevents privilege escalation through read-only access and forged client controls.

Versions: Play 3.0.12 / Java 17 and Spring Boot 4.1.1 / Java 21; no library API or
provider behaviour is changed. Verification uses the web suite plus StaffRoleTest,
AdminSecurityBoundaryTest and AdminWorkspaceIntegrationTest. Existing Flyway
migrations are tested on a new local schema, never on the application database.
Results on 21 October: 26 web tests passed; 24 backend admin tests passed (15
PostgreSQL integration tests, 8 HTTP security tests and 1 permission unit test).
Both projects compiled, and existing migrations reached version 58 in the isolated
test schema. No live staff account, inbox or verification decision was used.

Remaining risks: all page views now intentionally expose operational records to
Staff Lead, so invite/enable that role only for trusted staff. Provider/storage
outages can still stop reads; no fallback data or bypass is introduced. Counsel
must still confirm identity/financial-record access and retention. No new Safaricom
or custody behaviour is introduced, and refund/release rules remain TBC.
RECOMMENDED next task: restart the backend, reload the real website as Staff Lead
and check all eight sidebar pages without making live review decisions.

## What still needs a decision

- `TBC`: deployment provider and protected distributed session store.
- `TBC`: KMS and production private-document encryption arrangement.
- `TBC`: regulated custody, refund and release rules; consequently the payment page stays read-only.
- `TBC`: notification/delivery channel for staff invitations.
- Kenyan counsel must review identity-document retention, reviewer access and audit retention under the Kenya Data Protection Act, 2019.
- Safaricom/Tende Pay must confirm callback verification, reconciliation and regulated-custody responsibilities before money actions are added.

## Landlord verification decision controls

The landlord review page now exposes a top-of-page `Approve or reject
verification` link for pending cases. It moves to the existing decision form;
it does not add a second action or bypass the required reason, CSRF token,
optimistic `expectedUpdatedAt` value, assigned-reviewer check, MFA check or backend
audit. The decision form is rendered only while the server state is `PENDING`, so
completed cases do not offer repeat decisions.

- `/home/hp/Desktop/makao-hub-web/app/views/admin/verification.scala.html`:
  the header shortcut points to `#verification-decision`; the existing decision
  panel now has that ID and remains the only approve/reject form. Removing the
  shortcut recreates the discoverability problem. Removing the pending-state
  condition would advertise an action that the backend correctly rejects.
- `/home/hp/Desktop/makao-hub-web/test/controllers/VerificationReviewFlowTest.java`:
  the synthetic API now records decision calls separately from document review
  calls. `rejectButtonSendsAnExplicitNegativeDecisionWithCsrfAndReason` submits
  the real Play form with `approve=false`, a reason, version and CSRF cookie/token,
  then proves it records `REJECTED`, never calls document acknowledgement and no
  longer renders a repeat Reject control. Existing role-loop assertions prove the
  shortcut and negative submit button appear for Landlord, Sublessor and Renter
  pending cases; the completed-case assertion proves both disappear afterward.

`Mark reviewed` still acknowledges one current evidence file; it cannot approve
or reject the role. The assigned verification reviewer must use the decision form
after reviewing the required evidence. Staff Lead retains queue/assignment access
but cannot view private identity documents or submit a verification decision.
No production API, permission, schema or migration changed.

Risks: a stale browser case can still receive a 409 from the version guard and
must reload; this prevents overwriting another reviewer's decision. Test the page
with an assigned test reviewer and synthetic evidence before using real identity
documents. Identity-review access, reason retention and applicant disclosure need
Kenyan privacy-law review. No payment/provider behavior changed. Suggested next
task: add a small non-sensitive decision-status indicator to the verification
queue if reviewers need to distinguish recently completed cases more quickly.
