# Assistant navigation and chat interaction implementation plan

**Goal:** Follow the latest activity request and make verified routes, the chat column, and draft cards easier to use.

**Architecture:** Keep the existing Vue 3 and Spring Boot boundaries. Build navigation links from structured geographic events, animate only new reply fragments, and give messages and the draft one shared scroll container.

**Spec:** User request and screenshot in this chat, 2026-10-23.

**Constraints:** Keep group map identities and GCJ-02 coordinates; never infer live traffic or parking facts; explicit message instructions override saved preferences and earlier replies. No changes to synced project sources.

- [x] Navigation: validate coordinates, expose structured route links, use the previous stop as the start of each later stop's navigation. Test all travel modes, missing locations, invalid coordinates, and route names.
- [x] Chat: a single scroll region for messages and draft, fixed composer, accessible expand/collapse buttons, no automatic modal interruption. Preserve editing state and allow reading older messages during streaming.
- [x] Motion: append stable text fragments with a 460 ms blur/opacity reveal; finish pending text before Markdown; respect reduced motion and cancel timers on unmount.
- [x] Prompt: distinguish historical reference from the latest user request, preserve current time and requested travel mode, restrict factual route claims to tool evidence.
- [x] Verification: navigation tests, frontend suite and build, backend tests, browser checks of scrolling/collapse/reveal. Record any unavailable checks.

**Review focus:** Empty/invalid coordinates; explicit walking with a driving preference; tomorrow relative to Shanghai time; saved draft restored; scrolling up while output continues.

**Execution:** Implement in the current clean checkout. User requested completing these changes; no additional design approval or automatic commit is needed.

## Verification results

- Frontend: 50 tests passed; production build passed. Existing large bundle warning remains.
- Backend: 185 tests passed, zero failures/errors/skips; Maven build succeeded. Test teardown emitted database/Redis reconnect messages and a Surefire fork exit timeout warning; these did not fail the suite. Final source was compiled again successfully after the prompt wording adjustments.
- Browser: real Vue components exercised with isolated fixture data, without creating activities or calling a live LLM. Verified shared scrolling, preserved reading position while streaming and completing, saved draft collapse/expand, correct navigation endpoints/mode, route duration rounding, and narrow layout.
- Browser prefers reduced motion, and the component correctly skips animations under that setting. A separate static probe confirmed that the 4 px blur renders on inline text. Normal animation uses stable appended fragments and a 460 ms blur/opacity transition.
- Review: corrected stale segment data when replacing a draft; retained the guard against late route responses. Changed both main and preview confidence labels from 实测 to 高德路线.
- Live model compliance and launching the Amap mobile app were not tested. URI parameters were checked against the official Amap navigation guide and automated URL tests.
- Running local backend was not restarted; updated prompt rules take effect on its next restart. Changes remain uncommitted for review.
