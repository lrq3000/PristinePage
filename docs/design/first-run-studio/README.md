# First-run studio

Three interactive PristinePage onboarding mockups, designed and built with **GPT-6.2 Astra** in OpenCode.

Open [`index.html`](index.html) from a local checkout in a browser. No build, server, or network connection is needed. Switch designs with the tabs at the top; each remembers its own choices for the current browser session.

- **Field Guide** — Compare a pressed-sage specimen with its corrected scan. Save the page, change a correction, and find it again by searching for “sage”.
- **Scan Bench** — Photograph a café receipt, accept the suggested album name, and reopen the saved page to correct it. Search for “toast”, a word printed on the receipt but absent from the album title.
- **Library Atlas** — Try opening an existing library where it lives, then compare that with moving a library or importing a backup. Follow the rail ticket through the scan example and search for “Meridian”.

Choose **Settings → Replay first-run wizard** to revisit a tour with its current choices. **Preview first launch** resets only the selected design. Camera permission, OCR, and folder operations are simulated with fictional documents; the mockups do not access the camera or personal files.

For the optional desktop and phone-width checks, run `python validation/ui/first_run_studio_qa.py` from the repository root with Python Playwright, Pillow and Microsoft Edge available. The check opens the mockups offline and writes screenshots to a temporary directory.
